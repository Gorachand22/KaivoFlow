package com.kaivoflow.core.model

/** One second-level category. [planned] subcategories exist in the tree but may hold no Steps yet. */
data class SubCategory(val key: String, val label: String, val planned: Boolean = false)

/**
 * One top-level category. [hue] is the accent family the canvas uses; it is also what today's
 * six-value [StepCategory] is derived from, so existing colour code keeps working.
 */
data class TopCategory(
    val key: String,
    val label: String,
    val hue: StepCategory,
    val subs: List<SubCategory>,
)

/** One line of the placement rule (taxonomy spec section 2). The first matching question wins. */
data class PlacementRule(val order: Int, val question: String, val target: String)

/**
 * The Step category tree: ten top levels, two levels maximum. Pure data, no Android.
 * A category is addressed by a path string `top/sub`, for example `device/sensors`.
 */
object CategoryTree {

    const val SEPARATOR = '/'

    val top: List<TopCategory> = listOf(
        TopCategory(
            "spark", "Sparks", StepCategory.Trigger,
            listOf(
                SubCategory("start", "Start and time"),
                SubCategory("incoming", "Incoming"),
                SubCategory("device_events", "Device events"),
            ),
        ),
        TopCategory(
            "flow", "Flow control", StepCategory.Logic,
            listOf(
                SubCategory("branching", "Branching"),
                SubCategory("looping", "Looping"),
                SubCategory("timing", "Timing and errors"),
                SubCategory("composition", "Composition"),
            ),
        ),
        TopCategory(
            "data", "Data", StepCategory.Transform,
            listOf(
                SubCategory("items", "Fields and items"),
                SubCategory("text", "Text and formats"),
                SubCategory("time", "Date and time"),
                SubCategory("storage", "Tables", planned = true),
            ),
        ),
        TopCategory(
            "files", "Files and binary", StepCategory.Transform,
            listOf(
                SubCategory("convert", "Read and convert"),
                SubCategory("archive", "Compress", planned = true),
                SubCategory("document", "Documents"),
            ),
        ),
        TopCategory(
            "net", "Network", StepCategory.Saas,
            listOf(
                SubCategory("web", "HTTP and web"),
                SubCategory("protocols", "Protocols", planned = true),
            ),
        ),
        TopCategory(
            "app", "Apps", StepCategory.Saas,
            listOf(
                SubCategory("messaging", "Messaging"),
                SubCategory("productivity", "Productivity and docs"),
            ),
        ),
        TopCategory(
            "ai", "AI", StepCategory.Ai,
            listOf(
                SubCategory("chains", "Chains"),
                SubCategory("agents", "Agents", planned = true),
                SubCategory("models", "Chat models", planned = true),
                SubCategory("memory", "Memory", planned = true),
                SubCategory("tools", "Tools", planned = true),
                SubCategory("parsers", "Output parsers", planned = true),
                SubCategory("rag", "Retrieval", planned = true),
            ),
        ),
        TopCategory(
            "code", "Code", StepCategory.Logic,
            listOf(SubCategory("script", "Scripts")),
        ),
        TopCategory(
            "security", "Security", StepCategory.Device,
            listOf(
                SubCategory("crypto", "Hashing and keys"),
                SubCategory("auth", "One-time codes"),
            ),
        ),
        TopCategory(
            "device", "Device", StepCategory.Device,
            listOf(
                SubCategory("sensors", "Sensors"),
                SubCategory("connectivity", "Connectivity"),
                SubCategory("telephony", "Telephony and messaging"),
                SubCategory("notifications", "Notifications"),
                SubCategory("files_media", "Files and media"),
                SubCategory("system", "System and apps"),
                SubCategory("location", "Location"),
                SubCategory("contacts_calendar", "Contacts and calendar"),
                SubCategory("io", "Input and output"),
            ),
        ),
    )

    /** The placement rule, in order. Data only; the picker and reviewers read it, nothing executes it. */
    val placementRules: List<PlacementRule> = listOf(
        PlacementRule(1, "Does it start a Flow (kind Spark)?", "spark"),
        PlacementRule(2, "Does it read or change phone hardware, OS state or an Android system service?", "device"),
        PlacementRule(3, "Does it talk to one named third-party product?", "app"),
        PlacementRule(4, "Is it a generic protocol or web client used with any server?", "net"),
        PlacementRule(5, "Does it run an AI model or is it an AI building block?", "ai"),
        PlacementRule(6, "Does it run user-written code?", "code"),
        PlacementRule(7, "Is its main job cryptography, secrets or authentication codes?", "security"),
        PlacementRule(8, "Does it mainly operate on file or binary bytes?", "files"),
        PlacementRule(9, "Does it only control routing, timing, error paths or composition without reshaping data?", "flow"),
        PlacementRule(10, "Otherwise it reshapes data.", "data"),
    )

    /** Names that must never be used as a category or subcategory key or label. */
    val forbiddenNames: Set<String> = setOf("misc", "miscellaneous", "utilities", "other")

    fun path(top: String, sub: String): String = "$top$SEPARATOR$sub"

    fun topOf(path: String): TopCategory? = top.firstOrNull { it.key == path.substringBefore(SEPARATOR) }

    fun subOf(path: String): SubCategory? {
        if (!path.contains(SEPARATOR)) return null
        val sub = path.substringAfter(SEPARATOR)
        return topOf(path)?.subs?.firstOrNull { it.key == sub }
    }

    /** True only for a full `top/sub` path that exists in the tree. */
    fun exists(path: String): Boolean = subOf(path) != null

    /** Every leaf as a `top/sub` path, in tree order. */
    fun leaves(): List<String> = top.flatMap { t -> t.subs.map { path(t.key, it.key) } }

    /** Today's six-value category for a path; unknown paths fall back like [StepCategory.fromKey]. */
    fun legacyCategoryOf(path: String): StepCategory = topOf(path)?.hue ?: StepCategory.Transform

    /** Breadcrumb for UI, for example "Device > Sensors". */
    fun breadcrumb(path: String): String {
        val t = topOf(path) ?: return path
        val s = subOf(path) ?: return t.label
        return "${t.label} > ${s.label}"
    }
}
