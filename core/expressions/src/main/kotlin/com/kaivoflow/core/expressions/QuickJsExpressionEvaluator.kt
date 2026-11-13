package com.kaivoflow.core.expressions

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.kaivoflow.core.engine.ExpressionEvaluator
import com.kaivoflow.core.engine.Values
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production expression engine: QuickJS in an isolated context.
 *
 * Design notes
 *  • Only JSON crosses the boundary (`__kaivoDefineScope` / `__kaivoEval`), so a sandbox escape
 *    has nothing to reach. No host objects, no reflection, no file access.
 *  • Evaluations are serialised on a single thread (a QuickJS context is not thread-safe) and
 *    bounded by a hard timeout. On timeout the context is discarded and rebuilt, which is the
 *    only safe way to stop a runaway `while(true)` in a JS sandbox.
 *  • Any failure to initialise QuickJS (unsupported ABI, stripped .so) permanently falls back to
 *    [FallbackExpressionEvaluator], so the app never hard-crashes because of an expression.
 *
 * The prelude itself (resources/js/kaivo_expression_prelude.js) is the same file the Node test
 * harness runs, so semantics are verified on every build.
 */
class QuickJsExpressionEvaluator(
    private val contextFactory: () -> QuickJs = { QuickJs.create() },
    private val timeoutMs: Long = 400,
    private val prelude: String = PreludeSource.load(),
) : ExpressionEvaluator, AutoCloseable {

    private val fallback = FallbackExpressionEvaluator()
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kaivo-js").apply { isDaemon = true }
    }
    private var engine: QuickJs? = null
    private var scopeSeeded = false
    private val degraded = AtomicBoolean(false)

    override fun evaluate(code: String, scope: Map<String, Any?>): Any? {
        if (code.isBlank()) return null
        if (degraded.get()) return fallback.evaluate(code, scope)

        return try {
            evaluateInSandbox(code, scope)
        } catch (e: ExpressionTimeoutException) {
            Log.w(TAG, "Expression timed out; running context recycled: $code")
            throw IllegalStateException("This expression took too long to run (${timeoutMs}ms).", e)
        } catch (e: Exception) {
            if (e is ExpressionTimeoutException) throw e
            // Sandbox genuinely unavailable → degrade for the rest of the process.
            if (engine == null) {
                degraded.set(true)
                Log.w(TAG, "QuickJS unavailable, falling back to the built-in evaluator", e)
                return fallback.evaluate(code, scope)
            }
            throw IllegalStateException(e.message ?: "Expression failed", e)
        }
    }

    private fun evaluateInSandbox(code: String, scope: Map<String, Any?>): Any? {
        val scopeJson = Values.stringify(scope)
        val script = buildString {
            append("globalThis.__kaivoDefineScope(")
            append(quote(scopeJson))
            append(");\nglobalThis.__kaivoEval(")
            append(quote(code))
            append(");")
        }

        val task = java.util.concurrent.FutureTask {
            val js = ensureEngine()
            js.evaluate<String>(script)
        }
        executor.execute(task)
        val envelope = try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            recycleEngine()
            throw ExpressionTimeoutException()
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            throw IllegalStateException(cause.message ?: "Expression failed", cause)
        }

        return decodeEnvelope(code, envelope)
    }

    private fun decodeEnvelope(code: String, raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null

        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) {
            val message = obj["error"]?.jsonPrimitive?.content ?: "Expression failed"
            throw IllegalStateException("Expression `$code` failed: $message")
        }
        obj["warning"]?.jsonPrimitive?.content?.let { Log.i(TAG, it) }
        val valueElement = obj["value"] ?: return null
        return JsonElementConverter.toValue(valueElement)
    }

    private fun ensureEngine(): QuickJs {
        engine?.let { js ->
            if (!scopeSeeded) {
                js.evaluate<Unit>(prelude)
                scopeSeeded = true
            }
            return js
        }
        val created = contextFactory()
        created.evaluate<Unit>(prelude)
        engine = created
        scopeSeeded = true
        return created
    }

    private fun recycleEngine() {
        runCatching { engine?.close() }
        engine = null
        scopeSeeded = false
    }

    override fun close() {
        recycleEngine()
        executor.shutdownNow()
    }

    private fun quote(value: String): String = Values.stringify(value)

    private class ExpressionTimeoutException : RuntimeException("Expression timed out")

    companion object {
        private const val TAG = "KaivoExpr"
    }
}

/** Loads the shared prelude from resources. */
object PreludeSource {
    fun load(): String =
        PreludeSource::class.java.classLoader
            ?.getResourceAsStream("js/kaivo_expression_prelude.js")
            ?.bufferedReader()
            ?.readText()
            ?: error("Expression prelude resource is missing (js/kaivo_expression_prelude.js)")
}

/** Converts kotlinx JSON elements into the engine's plain-Kotlin JSON model. */
object JsonElementConverter {
    fun toValue(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { toValue(it.value) }
        is kotlinx.serialization.json.JsonArray -> element.map { toValue(it) }
        else -> {
            val prim = element.jsonPrimitive
            when {
                prim.isString -> prim.content
                prim.content == "null" -> null
                prim.content == "true" -> true
                prim.content == "false" -> false
                else -> prim.content.toDoubleOrNull()
            }
        }
    }
}
