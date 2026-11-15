package com.kaivoflow.core.model

/**
 * Sovereign Workspace Credential Item representation.
 * Learned from n8n's Credentials architecture:
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
        val GOOGLE_SHEETS = CredentialTypeInfo(
            type = "googleSheetsOAuth2",
            displayName = "Google Sheets OAuth2 API",
            description = "OAuth2 access to read and append rows in Google Spreadsheets",
            iconKey = "sheets",
            accentColor = 0xFF10B981, // Emerald
            fields = listOf(
                CredentialFieldSpec("clientId", "Client ID", isSecret = false, placeholder = "e.g. 1303358986-xxx.apps.googleusercontent.com"),
                CredentialFieldSpec("clientSecret", "Client Secret", isSecret = true, placeholder = "GOCSPX-xxx"),
                CredentialFieldSpec("email", "Account Email", isSecret = false, placeholder = "user@gmail.com", required = false),
            ),
        )

        val OPENAI = CredentialTypeInfo(
            type = "openAiApi",
            displayName = "OpenAI API",
            description = "API Key authentication for GPT-4o, embeddings and vision",
            iconKey = "sparkle",
            accentColor = 0xFF3B82F6, // Electric Indigo / Sapphire
            fields = listOf(
                CredentialFieldSpec("apiKey", "API Key", isSecret = true, placeholder = "sk-proj-xxx"),
                CredentialFieldSpec("organizationId", "Organization ID", isSecret = false, placeholder = "org-xxx (optional)", required = false),
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

        val ALL = listOf(GOOGLE_SHEETS, OPENAI, TELEGRAM, HTTP_BASIC, HEADER_AUTH, BEARER_TOKEN)

        fun find(type: String): CredentialTypeInfo? = ALL.firstOrNull { it.type.equals(type, ignoreCase = true) }
    }
}

/**
 * Sovereign Data Table representation.
 * Learned from n8n's Data Tables:
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
