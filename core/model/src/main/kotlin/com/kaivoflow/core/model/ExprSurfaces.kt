package com.kaivoflow.core.model

/** Whether an input outside a node accepts expressions. Mirrors docs/specs/P3-universal-expressions.md. */
enum class ExprDecision { Expression, FixedOnly, Never }

/** Which data an expression in the input may read. */
enum class ExprScopeKind {
    /** Upstream schema of the node (inspector only). */
    Upstream,
    /** `$vars`, `$now`, `$today`, `$run` flow info. No item data, no environment. */
    Global,
    None,
}

/** When the expression text is evaluated. */
enum class ExprWhen { PerRun, PerItem, PerCall, Never }

data class ExprSurface(
    val id: String,
    val screen: String,
    val label: String,
    val decision: ExprDecision,
    val scope: ExprScopeKind,
    val resolved: ExprWhen,
    val reason: String,
)

/** Single source of truth for which non-node inputs take expressions; UI adoption and runtime guards read it. */
object ExprSurfaces {
    private fun x(id: String, screen: String, label: String, scope: ExprScopeKind, at: ExprWhen, reason: String) =
        ExprSurface(id, screen, label, ExprDecision.Expression, scope, at, reason)
    private fun f(id: String, screen: String, label: String, reason: String) =
        ExprSurface(id, screen, label, ExprDecision.FixedOnly, ExprScopeKind.None, ExprWhen.Never, reason)
    private fun n(id: String, screen: String, label: String, reason: String) =
        ExprSurface(id, screen, label, ExprDecision.Never, ExprScopeKind.None, ExprWhen.Never, reason)

    val all: List<ExprSurface> = listOf(
        x("agent.instructions", "Agent Builder", "Instructions (system prompt)", ExprScopeKind.Global, ExprWhen.PerCall, "Read each time the agent is called"),
        x("agent.knowledge.content", "Agent Builder", "Knowledge document content", ExprScopeKind.Global, ExprWhen.PerCall, "Added to the prompt on every call"),
        f("agent.trigger.schedule", "Agent Builder", "Trigger schedule (cron)", "Registered with the OS before any data exists"),
        f("agent.trigger.webhook", "Agent Builder", "Trigger webhook path", "A route is registered once; it cannot depend on a request"),
        f("agent.settings.numbers", "Agent Builder", "Iterations, memory depth, concurrency, timeout", "Limits must be known before a call starts"),
        n("agent.name", "Agent Builder", "Agent name, skill name, document title", "Names and titles are labels"),
        n("agent.testPrompt", "Agent Builder", "Test prompt", "A chat message box"),
        n("assistant.chat", "Ask AI", "Chat message box", "A chat message box"),
        n("assistant.modelSearch", "Ask AI", "Model search", "A search box"),
        x("variable.value", "Variables", "Variable value", ExprScopeKind.Global, ExprWhen.PerRun, "Evaluated once when a Run starts"),
        n("variable.key", "Variables", "Variable key", "An identifier other values refer to"),
        x("credential.field", "Credentials", "Non-secret credential field", ExprScopeKind.Global, ExprWhen.PerCall, "Evaluated each time a node asks for the credential"),
        n("credential.secret", "Credentials", "Secret credential field", "Secrets are never evaluated or previewed"),
        n("credential.name", "Credentials", "Credential name", "A label"),
        f("oauth.setup", "Settings", "Token address, client id", "Used by background token refresh without a Run"),
        n("oauth.secret", "Settings", "Client secret, refresh token, API key", "Secret values"),
        n("flow.name", "Flow list and canvas", "Flow, group and table names, descriptions", "Names and titles"),
        f("flow.settings", "Flow settings", "Timezone, timeouts, limits", "Read by the engine before a Run has data"),
        x("step.notes", "Step inspector", "Step note (Behaviour tab)", ExprScopeKind.Global, ExprWhen.PerCall, "Shown in the inspector with a live preview of the variables and the time; the engine never reads it"),
        f("step.behaviour", "Step inspector", "On error, retries, wait, time limit", "Read by the engine before the Step's data exists; chosen from fixed options"),
        n("step.name", "Step inspector", "Step name", "Names are labels; other Steps refer to them"),
        f("note.sticky", "Canvas", "Sticky note title and text", "Documentation; shown as typed, never evaluated"),
        n("search.any", "All screens", "Search boxes", "Filters, not configuration"),
        f("table.row", "Data tables", "Row cell values", "Literal data entry"),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): ExprSurface? = byId[id]

    /** Unknown surfaces are not expression-capable. */
    fun allows(id: String): Boolean = byId[id]?.decision == ExprDecision.Expression

    /** A credential field takes expressions only when it is not secret. */
    fun credentialFieldAllows(spec: CredentialFieldSpec): Boolean = !spec.isSecret
}
