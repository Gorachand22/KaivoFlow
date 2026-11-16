package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamValue

/** Which credential keys of a Step point at an entry that still exists in the vault. Pure. */
object CredentialFill {
    fun filled(schema: ParamSchema, values: Map<String, ParamValue?>, refs: List<CredentialRef>?): Set<String> {
        val ids = refs.orEmpty().map { it.id }.toSet()
        if (ids.isEmpty()) return emptySet()
        val keys = schema.credentialSlots.map { it.key } + schema.params.filter { it.kind is ParamKind.CredentialPick }.map { it.key }
        return keys.filter { k -> (values[k] as? ParamValue.Fixed)?.value?.toString().orEmpty().let { it.isNotBlank() && it in ids } }.toSet()
    }
}
