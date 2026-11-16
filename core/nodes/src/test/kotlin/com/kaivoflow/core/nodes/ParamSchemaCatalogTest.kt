package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.ValidationContext
import com.kaivoflow.core.model.ParamValidator
import com.kaivoflow.core.model.toSchema
import com.kaivoflow.core.engine.ParamResolver
import com.kaivoflow.core.model.ShowWhen
import com.kaivoflow.core.model.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ParamSpec compat bridge on the real catalog: nothing throws, shapes stay consistent. */
class ParamSchemaCatalogTest {

    private val specs = NodeCatalog.executors().map { it.spec }

    @Test fun `every spec converts and keeps its param keys in order`() {
        specs.forEach { assertEquals(it.id, it.params.map { p -> p.key }, it.toSchema().params.map { p -> p.key }) }
    }

    @Test fun `validator runs on the full catalog with defaults and with empty values`() {
        specs.forEach { spec ->
            val schema = spec.toSchema()
            val defaults = spec.params.mapNotNull { p -> ParamValueCodec.decodeLegacy(p.default)?.let { p.key to it } }.toMap()
            ParamValidator.validate(schema, defaults, ValidationContext())
            ParamValidator.validate(schema, emptyMap(), ValidationContext())
            ParamValidator.validate(schema, schema.params.associate { it.key to ParamValue.Fixed("") })
        }
    }

    @Test fun `every show path resolves to a sibling and every select default is an option`() {
        specs.forEach { spec ->
            val keys = spec.params.map { it.key }.toSet()
            spec.toSchema().params.forEach { def ->
                fun paths(w: ShowWhen): List<String> = when (w) {
                    is ShowWhen.In -> listOf(w.path)
                    is ShowWhen.Any -> w.of.flatMap { paths(it) }
                    else -> emptyList()
                }
                paths(def.show).forEach { assertTrue("${spec.id}.${def.key} -> $it", it in keys) }
                val kind = def.kind
                val default = (def.default as? ParamValue.Fixed)?.value
                if (kind is ParamKind.Choice && default is String && default.isNotEmpty()) {
                    val opts = (kind.options as com.kaivoflow.core.model.OptionSource.Static).options
                    if (opts.isNotEmpty()) assertTrue("${spec.id}.${def.key} default '$default'", opts.any { it.value == default })
                }
            }
        }
    }

    @Test fun `resolving every spec with its defaults gives visible keys only`() {
        val resolver = ParamResolver(TestEvaluator())
        specs.forEach { spec ->
            val raw = spec.params.filter { it.default != null }.associate { it.key to it.default }
            val r = resolver.resolveStored(spec.toSchema(), raw, emptyMap())
            assertTrue(spec.id, r.keys.all { it in spec.params.map { p -> p.key } })
        }
    }
}
