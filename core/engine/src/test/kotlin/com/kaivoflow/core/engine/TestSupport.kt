package com.kaivoflow.core.engine

import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/** A deliberately dumb expression evaluator: supports `$json.path` and `$vars.path` only. */
class StubEvaluator : ExpressionEvaluator {
    override fun evaluate(code: String, scope: Map<String, Any?>): Any? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("$")) {
            val path = trimmed.drop(1)
            val head = path.substringBefore('.').substringBefore('[')
            val rest = path.removePrefix(head).trimStart('.', '[').trimEnd(']')
            val root = scope[head] ?: return null
            return if (rest.isEmpty()) root else Values.deepGet(root, rest)
        }
        return trimmed
    }
}

object TestSpecs {
    val manualTrigger = NodeSpec(
        id = "spark.manual",
        title = "Manual Spark",
        category = StepCategory.Trigger,
        subtitle = "Start the Flow when you tap Run",
        iconKey = "touch",
        kind = NodeKind.Trigger,
        canStartFlow = true,
    )

    val pass = NodeSpec("test.pass", "Pass", StepCategory.Transform, "Pass items through", "arrow")

    val setName = NodeSpec(
        id = "test.set",
        title = "Set fields",
        category = StepCategory.Transform,
        subtitle = "Set a field on each item",
        iconKey = "edit",
        params = persistentListOf(
            ParamSpec("field", "Field", ParamType.Text, required = true),
            ParamSpec("value", "Value", ParamType.Text),
        ),
    )

    val ifStep = NodeSpec(
        id = "test.if",
        title = "If",
        category = StepCategory.Logic,
        subtitle = "Split items by a condition",
        iconKey = "branch",
        outputs = 2,
        outputLabels = persistentListOf("true", "false"),
        params = persistentListOf(ParamSpec("field", "Field", ParamType.Text, required = true)),
    )

    val fail = NodeSpec("test.fail", "Fail", StepCategory.Logic, "Always fails", "warning")

    val failWithRetry = NodeSpec("test.flaky", "Flaky", StepCategory.Logic, "Fails then succeeds", "warning")

    val slow = NodeSpec(
        id = "test.slow",
        title = "Slow",
        category = StepCategory.Logic,
        subtitle = "Sleeps",
        iconKey = "clock",
        params = persistentListOf(ParamSpec("ms", "Milliseconds", ParamType.Number, required = true)),
    )
}

/** The manual Spark: emits whatever the Run was triggered with. */
class ManualTriggerExecutor : StepExecutor {
    override val spec = TestSpecs.manualTrigger
    override suspend fun execute(ctx: StepContext): StepResult = StepResult(mapOf(0 to ctx.input))
}

class PassExecutor : StepExecutor {
    override val spec = TestSpecs.pass
    override suspend fun execute(ctx: StepContext) = StepResult(mapOf(0 to ctx.input))
}

class SetExecutor : StepExecutor {
    override val spec = TestSpecs.setName
    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val field = Values.asString(params["field"])
        val value = params["value"]
        return ctx.output(ctx.input.map { it.copy(json = it.json + (field to value)) })
    }
}

/** Two outputs: port 0 when the item has a truthy [field], port 1 otherwise. */
class IfExecutor : StepExecutor {
    override val spec = TestSpecs.ifStep
    override suspend fun execute(ctx: StepContext): StepResult {
        val field = Values.asString(ctx.params()["field"])
        val (yes, no) = ctx.input.partition { Values.asBoolean(it.json[field]) }
        return StepResult(mapOf(0 to yes, 1 to no))
    }
}

class FailingExecutor(private val message: String = "boom") : StepExecutor {
    override val spec = TestSpecs.fail
    override suspend fun execute(ctx: StepContext): StepResult = throw KaivoException(message)
}

class FlakyExecutor(private val failuresBeforeSuccess: Int) : StepExecutor {
    val calls = AtomicInteger(0)
    override val spec = TestSpecs.failWithRetry
    override suspend fun execute(ctx: StepContext): StepResult {
        val n = calls.incrementAndGet()
        if (n <= failuresBeforeSuccess) throw KaivoException("flaky failure #$n")
        return StepResult(mapOf(0 to ctx.input.map { it.copy(json = it.json + ("attempts" to n)) }))
    }
}

class SlowExecutor : StepExecutor {
    override val spec = TestSpecs.slow
    override suspend fun execute(ctx: StepContext): StepResult {
        delay(Values.asDouble(ctx.params()["ms"])?.toLong() ?: 10L)
        return StepResult(mapOf(0 to ctx.input))
    }
}

class NullHttpGateway : HttpGateway {
    override suspend fun request(request: HttpRequestSpec): HttpResponseSpec =
        HttpResponseSpec(status = 200, body = mapOf("ok" to true))
}

class InMemoryCredentials : CredentialProvider {
    private val vault = mutableMapOf<String, Map<String, String>>(
        "cred-1" to mapOf("apiKey" to "sk-test")
    )
    override suspend fun resolve(ref: String): Map<String, String> =
        vault[ref] ?: throw KaivoException("Credential not found: $ref")
    override suspend fun list(): List<CredentialRef> = listOf(CredentialRef("cred-1", "apiKey", "Test key"))
}

fun registry(vararg extra: StepExecutor): NodeRegistry = NodeRegistry(
    listOf(ManualTriggerExecutor(), PassExecutor(), SetExecutor(), IfExecutor(), FailingExecutor(), SlowExecutor()) + extra
)

fun step(
    id: String,
    type: String,
    name: String = id,
    params: Map<String, Any?> = emptyMap(),
    x: Float = 0f,
    y: Float = 0f,
    disabled: Boolean = false,
): StepNode = StepNode(
    id = id,
    type = type,
    name = name,
    params = persistentMapOf(*params.entries.map { it.key to it.value }.toTypedArray()),
    x = x,
    y = y,
    disabled = disabled,
)

fun item(vararg pairs: Pair<String, Any?>) = FlowData(mapOf(*pairs))
