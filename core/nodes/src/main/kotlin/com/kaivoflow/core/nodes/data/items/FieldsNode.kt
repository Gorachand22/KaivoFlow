package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

/** Rename, remove or copy fields without writing an expression. */
class FieldsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.fields",
        title = "Rename or remove fields",
        category = StepCategory.Transform,
        subtitle = "Tidy up the shape of your items",
        iconKey = "rename",
        params = persistentListOf(
            ParamSpec("rename", "Rename (old → new)", ParamType.KeyValue, hint = "from = to"),
            ParamSpec("remove", "Remove fields", ParamType.Text, placeholder = "internalId, debug"),
            ParamSpec("keepOnly", "Keep only these fields", ParamType.Text, placeholder = "id, amount, merchant"),
        ),
        keywords = persistentListOf("rename", "remove", "drop", "keep", "pick"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val rename = Values.asMap(params["rename"]).mapValues { Values.asString(it.value) }
        val remove = Values.asString(params["remove"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val keepOnly = Values.asString(params["keepOnly"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val out = ctx.input.map { item ->
            var json = LinkedHashMap(item.json)
            if (keepOnly.isNotEmpty()) json = LinkedHashMap(json.filterKeys { it in keepOnly })
            remove.forEach { json.remove(it) }
            rename.forEach { (from, to) ->
                if (json.containsKey(from) && to.isNotBlank()) {
                    json[to] = json.remove(from)
                }
            }
            item.copy(json = json)
        }
        return ctx.output(out)
    }
}
