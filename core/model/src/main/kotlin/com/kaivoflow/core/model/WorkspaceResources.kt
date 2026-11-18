package com.kaivoflow.core.model

/**
 * Sovereign Workspace Credential Item representation:
 * - Decoupled from workflows
 * - Referenced by id
 * - Securely stored in encrypted Keystore vault
 */
data class CredentialItem(
    val id: String,
    val type: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val usedInFlowCount: Int = 0,
)

/**
 * Built-in Credential Type metadata for UI display, form rendering, and icons.
 */
data class CredentialFieldSpec(
    val key: String,
    val label: String,
    val isSecret: Boolean = true,
    val placeholder: String = "",
    val required: Boolean = true,
)

data class CredentialTypeInfo(
    val type: String,
    val displayName: String,
    val description: String,
    val iconKey: String,
    val accentColor: Long,
    val fields: List<CredentialFieldSpec>,
) {
    companion object {
        val OPENAI = CredentialTypeInfo(
            type = "openAiApi",
            displayName = "OpenAI & Compatible API",
            description = "API key and Base URL for OpenAI, NVIDIA NIM, OpenRouter, Groq, Mistral",
            iconKey = "sparkles",
            accentColor = 0xFF3B82F6, // Electric Indigo / Sapphire
            fields = listOf(
                CredentialFieldSpec("apiKey", "API Key", isSecret = true, placeholder = "sk-... or nvapi-..."),
                CredentialFieldSpec("baseUrl", "Base URL", isSecret = false, placeholder = "https://api.openai.com/v1 (or compatible URL)", required = false),
            ),
        )

        val GEMINI = CredentialTypeInfo(
            type = "geminiApi",
            displayName = "Google Gemini API",
            description = "API key authentication for Gemini 2.0 Flash / Pro models",
            iconKey = "sparkles",
            accentColor = 0xFF4285F4, // Google Blue
            fields = listOf(
                CredentialFieldSpec("apiKey", "API Key", isSecret = true, placeholder = "AIzaSy..."),
                CredentialFieldSpec("baseUrl", "Base URL", isSecret = false, placeholder = "https://generativelanguage.googleapis.com", required = false),
            ),
        )

        val CLAUDE = CredentialTypeInfo(
            type = "anthropicApi",
            displayName = "Anthropic Claude API",
            description = "API key for Claude 3.5 Sonnet and Haiku models",
            iconKey = "sparkles",
            accentColor = 0xFFD97706, // Amber / Claude
            fields = listOf(
                CredentialFieldSpec("apiKey", "API Key", isSecret = true, placeholder = "sk-ant-api..."),
                CredentialFieldSpec("baseUrl", "Base URL", isSecret = false, placeholder = "https://api.anthropic.com/v1", required = false),
                CredentialFieldSpec("anthropicVersion", "Anthropic Version", isSecret = false, placeholder = "2023-06-01", required = false),
            ),
        )

        val OLLAMA = CredentialTypeInfo(
            type = "ollamaApi",
            displayName = "Ollama Local API",
            description = "Connect to local models running on device or local network",
            iconKey = "bolt",
            accentColor = 0xFF6366F1, // Indigo
            fields = listOf(
                CredentialFieldSpec("baseUrl", "Host URL", isSecret = false, placeholder = "http://localhost:11434"),
            ),
        )

        val TELEGRAM = CredentialTypeInfo(
            type = "telegramBot",
            displayName = "Telegram Bot API",
            description = "Bot token from @BotFather for sending channel alerts and bot messages",
            iconKey = "send",
            accentColor = 0xFF06B6D4, // Cyan / Topaz
            fields = listOf(
                CredentialFieldSpec("botToken", "Bot Token", isSecret = true, placeholder = "123456789:ABCDefghIJKlmno..."),
            ),
        )

        val HTTP_BASIC = CredentialTypeInfo(
            type = "httpBasicAuth",
            displayName = "HTTP Basic Auth",
            description = "Standard username and password authentication",
            iconKey = "lock",
            accentColor = 0xFFF59E0B, // Amber
            fields = listOf(
                CredentialFieldSpec("username", "Username", isSecret = false, placeholder = "admin"),
                CredentialFieldSpec("password", "Password", isSecret = true, placeholder = "••••••••"),
            ),
        )

        val HEADER_AUTH = CredentialTypeInfo(
            type = "httpHeaderAuth",
            displayName = "Header Auth / API Key",
            description = "Custom header authorization (e.g. X-API-Key or Bearer)",
            iconKey = "key",
            accentColor = 0xFFD4AF37, // Champagne Gold
            fields = listOf(
                CredentialFieldSpec("headerName", "Header Name", isSecret = false, placeholder = "X-API-Key"),
                CredentialFieldSpec("headerValue", "Header Value", isSecret = true, placeholder = "secret_key_xxx"),
            ),
        )

        val BEARER_TOKEN = CredentialTypeInfo(
            type = "bearerToken",
            displayName = "Bearer Token",
            description = "Bearer token passed directly in the Authorization header",
            iconKey = "shield",
            accentColor = 0xFF8B5CF6, // Amethyst / Purple
            fields = listOf(
                CredentialFieldSpec("token", "Token", isSecret = true, placeholder = "eyJhbGciOi..."),
            ),
        )

        val ALL = listOf(OPENAI, GEMINI, CLAUDE, OLLAMA, TELEGRAM, HTTP_BASIC, HEADER_AUTH, BEARER_TOKEN)

        fun find(type: String): CredentialTypeInfo? = ALL.firstOrNull { it.type.equals(type, ignoreCase = true) }
    }
}

/**
 * Sovereign Data Table representation:
 * - Embedded schema with typed columns (string, number, boolean, date)
 * - Rows stored locally on device
 * - Flows can insert, query, update and delete records
 */
data class DataTableColumn(
    val id: String,
    val name: String,
    val type: String = "string", // "string", "number", "boolean", "date"
)

data class DataTable(
    val id: String,
    val name: String,
    val description: String = "",
    val columns: List<DataTableColumn> = emptyList(),
    val rowCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class DataTableRow(
    val id: String,
    val tableId: String,
    val data: Map<String, Any?> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)
