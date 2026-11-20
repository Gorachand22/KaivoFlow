package com.kaivoflow.core.model

/**
 * The single description of credential types: which types exist, what family each belongs to, which field
 * names each one stores (including the names older versions saved), and which Steps accept which type in
 * which slot. The credential sheet, the pickers, the Settings screen and the Step executors all read it, so
 * a credential made in the UI is one a Step can actually use.
 *
 * Pure Kotlin. Behaviour was derived from the audit (docs/audit-v2/03, I-01 and I-10), not from n8n code.
 */
object CredentialRegistry {
    /** Row that stores the assistant's provider choice. A pointer, never a credential the person made. */
    const val AI_PROVIDER_CONFIG_ID = "ai_provider_config"

    /** Rows with these ids or types are internal bookkeeping: no list, picker or count may show them. */
    val INTERNAL_IDS: Set<String> = setOf(AI_PROVIDER_CONFIG_ID)
    val INTERNAL_TYPES: Set<String> = setOf("ai_assistant", "ai_provider_config")

    /** Type ids that older versions saved and that no longer exist. Rows are removed once by a migration (DB-18). */
    const val LEGACY_GOOGLE_SHEETS_TYPE = "googleSheetsOAuth2"

    /** Types written by the old Settings screen. Not in [CredentialTypeInfo.ALL] so the new-credential list is unchanged. */
    val GENERIC_API_KEY = CredentialTypeInfo(
        type = "apiKey",
        displayName = "API Key",
        description = "A single secret key",
        iconKey = "key",
        accentColor = 0xFFD4AF37,
        fields = listOf(CredentialFieldSpec("apiKey", "API Key", isSecret = true)),
    )
    val GENERIC_OAUTH2 = CredentialTypeInfo(
        type = "oauth2",
        displayName = "Connected account (OAuth2)",
        description = "Access and refresh token for a connected account",
        iconKey = "shield",
        accentColor = 0xFF8B5CF6,
        fields = listOf(
            CredentialFieldSpec("accessToken", "Access token", isSecret = true, required = false),
            CredentialFieldSpec("refreshToken", "Refresh token", isSecret = true, required = false),
            CredentialFieldSpec("clientId", "Client ID", isSecret = false, required = false),
            CredentialFieldSpec("clientSecret", "Client secret", isSecret = true, required = false),
            CredentialFieldSpec("tokenUrl", "Token URL", isSecret = false, required = false),
        ),
    )

    private val all: List<CredentialTypeInfo> = CredentialTypeInfo.ALL + GENERIC_API_KEY + GENERIC_OAUTH2

    private val kinds: Map<String, CredentialKind> = mapOf(
        "openAiApi" to CredentialKind.ApiKey,
        "geminiApi" to CredentialKind.ApiKey,
        "anthropicApi" to CredentialKind.ApiKey,
        "ollamaApi" to CredentialKind.Custom,
        "telegramBot" to CredentialKind.Bearer,
        "httpBasicAuth" to CredentialKind.Basic,
        "httpHeaderAuth" to CredentialKind.Header,
        "bearerToken" to CredentialKind.Bearer,
        "apiKey" to CredentialKind.ApiKey,
        "oauth2" to CredentialKind.OAuth2,
    )

    /** Spellings found in saved data: lower-cased on lookup. */
    private val aliases: Map<String, String> = mapOf(
        "credential_type_openai" to "openAiApi",
        "credential_type_gemini" to "geminiApi",
        "credential_type_claude" to "anthropicApi",
        "credential_type_ollama" to "ollamaApi",
        "openai" to "openAiApi",
        "gemini" to "geminiApi",
        "claude" to "anthropicApi",
        "anthropic" to "anthropicApi",
        "ollama" to "ollamaApi",
        "groq" to "openAiApi",
        "deepseek" to "openAiApi",
        "openrouter" to "openAiApi",
    )

    private val aiTypes = setOf("openAiApi", "geminiApi", "anthropicApi", "ollamaApi", "apiKey")
    private val authTypes = setOf("httpBasicAuth", "httpHeaderAuth", "bearerToken", "apiKey", "oauth2")
    private val tokenTypes = setOf("bearerToken", "apiKey", "oauth2")

    /**
     * Steps and the types each credential slot accepts. A Step that is not listed accepts nothing here, so a
     * new Step must be added on purpose. The slot key is the Step param that holds the credential id.
     */
    private val slots: Map<String, Map<String, Set<String>>> = mapOf(
        "net.http" to mapOf("credential" to authTypes),
        "saas.connector" to mapOf("credential" to authTypes),
        "logic.poll" to mapOf("credential" to authTypes),
        "net.graphql" to mapOf("credential" to authTypes),
        "app.telegram" to mapOf("credential" to setOf("telegramBot")),
        "app.slack" to mapOf("credential" to tokenTypes),
        "app.discord" to mapOf("credential" to tokenTypes),
        "app.notion" to mapOf("credential" to tokenTypes),
        "app.google_sheets" to mapOf("credential" to setOf("oauth2", "bearerToken")),
        "ai.agent" to mapOf("credential" to aiTypes),
        "ai.chat_model" to mapOf("credential" to aiTypes),
        "ai.basic_chain" to mapOf("credential" to aiTypes),
        "ai.chat" to mapOf("credential" to aiTypes),
        "ai.extract" to mapOf("credential" to aiTypes),
        "ai.classify" to mapOf("credential" to aiTypes),
    )

    /** The registered form for [type] (any known spelling), or null. */
    fun info(type: String): CredentialTypeInfo? {
        val canonical = canonicalType(type) ?: return null
        return all.firstOrNull { it.type == canonical }
    }

    /** What the edit sheet renders: the registered form, or the generic single-key form for unknown types. */
    fun infoForEditing(type: String): CredentialTypeInfo = info(type) ?: GENERIC_API_KEY

    /** The registered spelling of [raw] (case-insensitive, old spellings mapped), or null when unknown. */
    fun canonicalType(raw: String): String? {
        val t = raw.trim()
        all.firstOrNull { it.type.equals(t, ignoreCase = true) }?.let { return it.type }
        return aliases[t.lowercase()]
    }

    fun kindOf(type: String): CredentialKind = canonicalType(type)?.let { kinds[it] } ?: CredentialKind.Custom

    fun isInternal(id: String, type: String? = null): Boolean =
        id in INTERNAL_IDS || (type != null && type in INTERNAL_TYPES)

    /** Types that [stepId] accepts in [slotKey]; empty when the Step or slot is not registered. */
    fun acceptedTypes(stepId: String, slotKey: String = "credential"): Set<String> =
        slots[stepId]?.get(slotKey).orEmpty()

    fun accepts(stepId: String, slotKey: String, type: String): Boolean {
        val canonical = canonicalType(type) ?: return false
        return canonical in acceptedTypes(stepId, slotKey)
    }

    /** Steps that have at least one credential slot. */
    fun stepsWithSlots(): Set<String> = slots.keys

    /**
     * What a picker may show for a slot: internal rows removed, then only accepted types. A Step with no
     * registered slot shows every non-internal row, so an unlisted Step is never silently empty.
     */
    fun pickable(refs: List<CredentialRef>, stepId: String?, slotKey: String = "credential"): List<CredentialRef> {
        val visible = refs.filterNot { isInternal(it.id, it.type) || it.type == LEGACY_GOOGLE_SHEETS_TYPE }
        val accepted = stepId?.let { acceptedTypes(it, slotKey) }.orEmpty()
        if (accepted.isEmpty()) return visible
        return visible.filter { canonicalType(it.type)?.let { c -> c in accepted } == true }
    }

    /** Picker rows for a [CredentialKind] filter (the schema-driven picker). Internal rows are always removed. */
    fun pickableByKind(refs: List<CredentialRef>, kinds: Set<CredentialKind>): List<CredentialRef> {
        val visible = refs.filterNot { isInternal(it.id, it.type) || it.type == LEGACY_GOOGLE_SHEETS_TYPE }
        if (kinds.isEmpty()) return visible
        return visible.filter { kindOf(it.type) in kinds }
    }

    /** Field keys whose values are secret for [type]; unknown types fall back to key-name hints. */
    fun secretKeys(type: String, fields: Map<String, String>): Set<String> {
        val spec = info(type)?.fields
        if (spec != null) {
            val declared = spec.filter { it.isSecret }.map { it.key }.toSet()
            val hinted = fields.keys.filter { looksSecret(it) }.toSet()
            return declared + hinted
        }
        return fields.keys.filter { looksSecret(it) }.toSet()
    }

    /** Every secret value in a stored credential, for the redactor. Values shorter than 4 are skipped. */
    fun secretValues(type: String, fields: Map<String, String>): List<String> =
        secretKeys(type, fields).mapNotNull { fields[it]?.trim() }.filter { it.length >= 4 }

    private val secretHint = Regex("(?i)(key|token|secret|password|passwd|credential|authorization|headervalue|^value$|cookie)")
    private fun looksSecret(key: String) = secretHint.containsMatchIn(key)
}

/**
 * Reads the secret and the auth shape out of a stored credential, whichever field names it was saved with.
 * Every Step that builds an auth header goes through this, so Telegram, Bearer and Header-auth credentials
 * made in the sheet work, and rows saved with the older field names keep working.
 */
object CredentialFields {
    /** One header to send: `Authorization: Bearer ...` or a custom `X-Api-Key: ...`. */
    data class HeaderAuth(val name: String, val value: String)

    private fun Map<String, String>.firstOf(vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> this[k]?.takeIf { it.isNotBlank() } }.orEmpty()

    /**
     * A bearer-style secret: OAuth access token, then API key, then the Bearer type's `token`. The Telegram
     * `botToken` is deliberately not here so a Telegram token is never sent to another service as Bearer.
     */
    fun bearer(fields: Map<String, String>): String = fields.firstOf("accessToken", "apiKey", "token", "bearer")

    /** Telegram bot token: the sheet stores `botToken`; the old Settings screen stored `apiKey`. */
    fun botToken(fields: Map<String, String>): String = fields.firstOf("botToken", "apiKey", "token")

    /** Header auth: sheet names `headerName` and `headerValue`; older Steps read `header` and `value`. */
    fun header(fields: Map<String, String>): HeaderAuth? {
        val name = fields.firstOf("headerName", "header")
        val value = fields.firstOf("headerValue", "value")
        if (name.isBlank() || value.isBlank()) return null
        if (!isHeaderName(name)) return null
        return HeaderAuth(name, value)
    }

    /** Username and password for HTTP Basic, or null when there is no username. */
    fun basic(fields: Map<String, String>): Pair<String, String>? {
        val user = fields["username"]?.takeIf { it.isNotBlank() } ?: return null
        return user to fields["password"].orEmpty()
    }

    /** `Bearer x`, left alone when it already starts with `Bearer `. */
    fun bearerHeaderValue(token: String): String =
        if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"

    /** RFC 7230 token characters only; stops a stored name from injecting a second header line. */
    fun isHeaderName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }

    /**
     * Auth headers for a stored credential, in priority order basic, header, bearer, matching what the HTTP
     * Step did before but through the shared names. Empty when the credential carries no usable secret.
     */
    fun authHeaders(fields: Map<String, String>): Map<String, String> {
        basic(fields)?.let { (u, p) ->
            val raw = "$u:$p".toByteArray(Charsets.UTF_8)
            return mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString(raw))
        }
        header(fields)?.let { return mapOf(it.name to it.value) }
        val token = bearer(fields)
        if (token.isNotBlank()) return mapOf("Authorization" to bearerHeaderValue(token))
        return emptyMap()
    }
}

/**
 * Called when the person saves an Ollama (local model) credential: its host is then allowed through the network
 * guard. The network layer implements it and only accepts loopback and LAN hosts; anything else is ignored.
 */
fun interface LocalHostRegistrar {
    fun allowLocalHostFor(baseUrl: String)

    companion object { val None = LocalHostRegistrar { } }
}
