package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import java.time.ZoneId

/**
 * Facts about the device that the validator cannot read itself. The caller fills them from the
 * platform; the validator stays pure. There are no defaults on purpose: a forgotten fact must not
 * quietly pass a check. Use [AllGood] in tests.
 */
data class PlatformFacts(
    val receiveSmsGranted: Boolean,
    val fineLocationGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val notificationAccessBound: Boolean,
    val webhookTokenExists: Boolean,
    val batteryOptimisationExempt: Boolean,
    val exactAlarmAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val oemAutostartVerified: Boolean,
) {
    companion object {
        val AllGood = PlatformFacts(true, true, true, true, true, true, true, true, true)
    }
}

/** Schedule precision modes from spec 3.9. */
enum class SchedulePrecision(val label: String) { OnTime("on-time"), Exact("exact"), Flexible("flexible") }

/** The synchronous arming dry run (spec 4.3 rule 5). Only called when every other check passed. */
fun interface ArmingDryRun {
    fun run(flow: Flow, sparks: List<StepNode>): List<RegistrationFailure>
}

data class PublishContext(
    val platform: PlatformFacts,
    /** Other Flows that are Published (or in Error), excluding the one being validated. */
    val otherLiveFlows: List<Flow> = emptyList(),
    val dryRun: ArmingDryRun? = null,
)

data class PublishReport(val issues: List<ValidationIssue>) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == IssueSeverity.Blocking }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == IssueSeverity.Warning }

    /** Warnings never block: they need the user's "Publish anyway", not a fix. */
    val canPublish: Boolean get() = errors.isEmpty()
    val needsAcknowledgement: Boolean get() = canPublish && warnings.isNotEmpty()
}

/**
 * Spec section 4.3. Blocking errors (structure, at least one live Spark, per-Spark configuration,
 * missing permissions, arming dry run) and warnings (environment and risk). Messages name the Step.
 *
 * Parameter keys read from Spark Steps beyond the current catalog: `precision` (schedule),
 * `acknowledgeAll` (Notification and SMS: "I want every notification/message").
 */
class PublishValidator(private val structure: FlowValidator) {

    fun validate(flow: Flow, ctx: PublishContext): PublishReport {
        val issues = mutableListOf<ValidationIssue>()

        // 1. Structure, from the shared validator (its own warnings pass through as warnings).
        issues += structure.validate(flow).issues

        // 2. At least one enabled non-Manual Spark.
        val sparks = flow.steps.filter { it.isTrigger && !it.disabled && it.type != SparkTypes.MANUAL }
        if (sparks.isEmpty()) {
            issues += error(
                "no_live_spark",
                "Publish needs at least one enabled Spark that is not Manual, such as a schedule or webhook.",
                fixHint = "Add a Spark or enable one.",
            )
        }

        // 3 and 4. Per-Spark configuration and permissions.
        val webhookPaths = mutableMapOf<String, String>()
        sparks.forEach { step ->
            when (step.type) {
                SparkTypes.SCHEDULE -> issues += schedule(step)
                SparkTypes.WEBHOOK -> issues += webhook(step, ctx, webhookPaths)
                SparkTypes.NOTIFICATION -> issues += notification(step, ctx.platform)
                SparkTypes.SMS -> issues += sms(step, ctx.platform)
                SparkTypes.GEOFENCE -> issues += geofence(step, ctx.platform)
                SparkTypes.BLUETOOTH -> issues += bluetooth(step)
                SparkTypes.BATTERY -> issues += battery(step)
            }
        }

        // 5. Arming dry run, only when nothing else blocks (it has side effects on the platform).
        if (issues.none { it.severity == IssueSeverity.Blocking }) {
            ctx.dryRun?.run(flow, sparks).orEmpty().forEach { f ->
                val step = flow.step(f.sparkId)
                issues += error(
                    "arm_failed",
                    "\"${step?.name ?: f.sparkId}\" could not be armed: ${f.detail.ifBlank { f.code.name }}.",
                    f.sparkId,
                    fixHint = "Fix the cause, then publish again.",
                )
            }
        }

        issues += warnings(flow, sparks, ctx)
        return PublishReport(issues)
    }

    // ---- Rule 3: Spark configuration ----------------------------------------------------------

    private fun schedule(step: StepNode): List<ValidationIssue> {
        val out = mutableListOf<ValidationIssue>()
        val name = step.name
        val frequency = step.paramString("frequency", "daily")
        if (frequency !in FREQUENCIES) {
            out += error("schedule_frequency", "\"$name\" has an unknown frequency \"$frequency\".", step.id)
            return out
        }
        if (frequency in setOf("daily", "weekly", "monthly")) {
            val time = step.paramString("time", "08:00")
            if (!TIME_OF_DAY.matches(time)) {
                out += error("schedule_time", "\"$name\": \"$time\" is not a time. Use 24-hour HH:MM, like 08:00.", step.id)
            }
        }
        val zone = step.paramString("timezone", "").trim()
        if (zone.isNotEmpty() && !zone.equals("device", ignoreCase = true) && !validZone(zone)) {
            out += error("schedule_zone", "\"$name\": \"$zone\" is not a known time zone.", step.id)
        }
        if (frequency == "minutes") {
            val interval = number(step, "interval") ?: 15.0
            val precision = precision(step)
            val floor = SCHEDULE_FLOOR_MINUTES.getValue(precision)
            if (interval < floor) {
                out += error(
                    "schedule_interval",
                    "\"$name\": the shortest interval for ${precision.label} precision is $floor " +
                        (if (floor == 1) "minute" else "minutes") + ".",
                    step.id,
                    fixHint = "Raise the interval to $floor or more, or pick a more exact precision.",
                )
            }
        }
        if (frequency == "weekly") {
            val days = step.paramString("daysOfWeek", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (days.any { it.toIntOrNull() == null || it.toInt() !in 1..7 }) {
                out += error("schedule_days", "\"$name\": days must be numbers from 1 (Monday) to 7 (Sunday).", step.id)
            }
        }
        if (frequency == "monthly") {
            val day = number(step, "dayOfMonth") ?: 1.0
            // Days 29 to 31 would be moved silently; refuse instead of rounding.
            if (day != Math.floor(day) || day < 1 || day > 28) {
                out += error(
                    "schedule_day_of_month",
                    "\"$name\": day of month must be 1 to 28, so the Flow fires in every month.",
                    step.id,
                )
            }
        }
        return out
    }

    private fun webhook(step: StepNode, ctx: PublishContext, seen: MutableMap<String, String>): List<ValidationIssue> {
        val out = mutableListOf<ValidationIssue>()
        val name = step.name
        val path = normalizeWebhookPath(step.paramString("path"))
        if (path == null) {
            out += error(
                "webhook_path",
                "\"$name\": the path must use letters, digits, dots, dashes or underscores, with optional / between parts.",
                step.id,
            )
        } else {
            val mine = seen.put(path, step.id)
            if (mine != null) {
                out += error("webhook_path_duplicate", "\"$name\" uses the path \"$path\" that another Spark in this Flow already uses.", step.id)
            }
            val clash = ctx.otherLiveFlows.firstOrNull { other ->
                activeSparks(other).any { it.type == SparkTypes.WEBHOOK && normalizeWebhookPath(it.paramString("path")) == path }
            }
            if (clash != null) {
                out += error("webhook_path_taken", "\"$name\": the path \"$path\" is already used by the Flow \"${clash.name}\".", step.id)
            }
        }
        val method = step.paramString("method", "POST").trim().uppercase()
        if (method.isNotEmpty() && method !in setOf("POST", "GET", "ANY")) {
            out += error("webhook_method", "\"$name\": method \"$method\" is not supported. Use POST, GET or ANY.", step.id)
        }
        if (!ctx.platform.webhookTokenExists) {
            out += error(
                "webhook_token",
                "\"$name\" needs an access token before it can listen.",
                step.id,
                fixHint = "Create the webhook token in Settings.",
            )
        }
        return out
    }

    private fun notification(step: StepNode, p: PlatformFacts): List<ValidationIssue> {
        val out = mutableListOf<ValidationIssue>()
        val name = step.name
        val pattern = step.paramString("textMatches").trim()
        if (pattern.isNotEmpty()) {
            when {
                pattern.length > MAX_PATTERN_LENGTH ->
                    out += error("notification_regex", "\"$name\": the pattern is longer than $MAX_PATTERN_LENGTH characters.", step.id)
                runCatching { Regex(pattern) }.isFailure ->
                    out += error("notification_regex", "\"$name\": the pattern is not a valid regular expression.", step.id)
            }
        }
        val hasFilter = listOf("packageNames", "titleContains", "textContains", "textMatches").any { step.paramString(it).isNotBlank() }
        if (!hasFilter && !step.paramBoolean("acknowledgeAll")) {
            out += error(
                "notification_no_filter",
                "\"$name\" would fire for every notification. Pick an app or text filter, or confirm you want all of them.",
                step.id,
            )
        }
        if (!p.notificationAccessBound) {
            out += error(
                "notification_access",
                "\"$name\" needs notification access, and the listener is not connected.",
                step.id,
                fixHint = "Allow notification access for KaivoFlow in Settings.",
            )
        }
        return out
    }

    private fun sms(step: StepNode, p: PlatformFacts): List<ValidationIssue> {
        val out = mutableListOf<ValidationIssue>()
        val hasFilter = step.paramString("senderContains").isNotBlank() || step.paramString("bodyContains").isNotBlank()
        if (!hasFilter && !step.paramBoolean("acknowledgeAll")) {
            out += error(
                "sms_no_filter",
                "\"${step.name}\" would fire for every SMS. Add a sender or text filter, or confirm you want all messages.",
                step.id,
            )
        }
        if (!p.receiveSmsGranted) {
            out += error(
                "sms_permission",
                "\"${step.name}\" needs the permission to receive SMS.",
                step.id,
                fixHint = "Allow SMS access for KaivoFlow.",
            )
        }
        return out
    }

    private fun geofence(step: StepNode, p: PlatformFacts): List<ValidationIssue> {
        val out = mutableListOf<ValidationIssue>()
        val name = step.name
        val lat = number(step, "latitude")
        val lon = number(step, "longitude")
        if (lat == null || lat !in -90.0..90.0 || lon == null || lon !in -180.0..180.0) {
            out += error("geofence_coordinates", "\"$name\": latitude must be -90 to 90 and longitude -180 to 180.", step.id)
        }
        val radius = number(step, "radiusMeters") ?: 150.0
        if (radius <= 0.0 || radius > MAX_GEOFENCE_RADIUS_M) {
            out += error("geofence_radius", "\"$name\": the radius must be above 0 and at most ${MAX_GEOFENCE_RADIUS_M.toInt()} metres.", step.id)
        }
        if (radius > 0.0 && radius < MIN_RELIABLE_GEOFENCE_RADIUS_M) {
            out += warning(
                "geofence_small_radius",
                "\"$name\": a radius under ${MIN_RELIABLE_GEOFENCE_RADIUS_M.toInt()} metres is often missed by the phone's location sensors.",
                step.id,
                fixHint = "Use ${MIN_RELIABLE_GEOFENCE_RADIUS_M.toInt()} metres or more.",
            )
        }
        if (!p.fineLocationGranted || !p.backgroundLocationGranted) {
            out += error(
                "geofence_permission",
                "\"$name\" needs precise location with \"Allow all the time\".",
                step.id,
                fixHint = "Open location permission and choose Allow all the time.",
            )
        }
        return out
    }

    private fun bluetooth(step: StepNode): List<ValidationIssue> =
        if (step.paramString("deviceNameContains").isBlank() && step.paramString("deviceAddress").isBlank()) {
            listOf(error("bluetooth_device", "\"${step.name}\" needs a Bluetooth device name or address.", step.id))
        } else emptyList()

    private fun battery(step: StepNode): List<ValidationIssue> {
        val t = number(step, "threshold") ?: 20.0
        return if (t < 1.0 || t > 99.0) {
            listOf(error("battery_threshold", "\"${step.name}\": the battery level must be between 1 and 99 percent.", step.id))
        } else emptyList()
    }

    // ---- Warnings -----------------------------------------------------------------------------

    private fun warnings(flow: Flow, sparks: List<StepNode>, ctx: PublishContext): List<ValidationIssue> {
        if (sparks.isEmpty()) return emptyList()
        val out = mutableListOf<ValidationIssue>()
        val p = ctx.platform
        val needsEngine = EngineNeeds.compute(listOf(EngineFlow(flow, LifecycleState.Published))).engineNeeded

        if (!p.batteryOptimisationExempt) {
            out += warning(
                "battery_optimisation",
                if (needsEngine) "Battery optimisation is on. Screen-off network for this Flow is not guaranteed." else "Battery optimisation is on. Schedules may run late.",
                fixHint = "Exempt KaivoFlow from battery optimisation.",
                params = if (needsEngine) listOf("critical") else emptyList(),
            )
        }
        if (!p.exactAlarmAllowed) {
            sparks.filter { it.type == SparkTypes.SCHEDULE }.forEach {
                out += warning(
                    "exact_alarm_missing",
                    "\"${it.name}\" will run with flexible timing because exact alarms are not allowed.",
                    it.id,
                    fixHint = "Allow alarms and reminders for KaivoFlow.",
                )
            }
        }
        if (needsEngine && !p.notificationsAllowed) {
            out += warning(
                "notifications_blocked",
                "Notifications are blocked, so the background notice for this Flow will be hidden.",
                fixHint = "Allow notifications for KaivoFlow.",
            )
        }
        if (!p.oemAutostartVerified) {
            out += warning(
                "oem_autostart_unverified",
                "Autostart for this phone brand is not confirmed. The Flow may stop after a restart.",
                fixHint = "Open the Reliability Center and follow the steps for your phone.",
            )
        }

        val risky = sparks.filter { it.type in ATTACKER_CONTROLLABLE }
        val sideEffects = flow.steps.filter { !it.disabled && isSideEffect(it) }
        val hasApproval = flow.steps.any { !it.disabled && it.type in APPROVAL_STEPS }
        if (risky.isNotEmpty() && sideEffects.isNotEmpty() && !hasApproval) {
            sideEffects.forEach {
                out += warning(
                    "side_effect_without_approval",
                    "\"${it.name}\" acts in the outside world and can be started by ${risky.first().name} without any approval.",
                    it.id,
                    fixHint = "Add an approval Step, or narrow the Spark filters.",
                )
            }
        }
        val sms = sparks.firstOrNull { it.type == SparkTypes.SMS }
        if (sms != null) {
            flow.steps.filter { !it.disabled && it.type == SMS_SEND }.forEach {
                out += warning(
                    "sms_loop",
                    "\"${it.name}\" sends an SMS from a Flow started by an SMS. If the sender is your own number this can loop.",
                    it.id,
                )
            }
        }
        flow.steps.filter { !it.disabled && (it.type in FOREGROUND_ONLY || (it.type == "device.clipboard" && it.paramString("operation") == "read")) }.forEach {
            out += warning(
                "foreground_only_step",
                "\"${it.name}\" needs the app on screen and may fail when a Spark starts the Flow in the background.",
                it.id,
            )
        }
        sparks.filter { it.type == SparkTypes.NOTIFICATION || it.type == SparkTypes.SMS }.forEach { mine ->
            val key = listenerKey(mine)
            val twin = ctx.otherLiveFlows.firstOrNull { other -> activeSparks(other).any { it.type == mine.type && listenerKey(it) == key } }
            if (twin != null) {
                out += warning(
                    "duplicate_listener",
                    "\"${mine.name}\" matches the same ${if (mine.type == SparkTypes.SMS) "SMS" else "notification"} filter as the Flow \"${twin.name}\". Both would act.",
                    mine.id,
                )
            }
        }
        return out
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun activeSparks(f: Flow) = f.steps.filter { it.isTrigger && !it.disabled }

    private fun isSideEffect(s: StepNode): Boolean = when (s.type) {
        SMS_SEND -> true
        HTTP -> s.paramString("method", "GET").uppercase() !in setOf("GET", "HEAD")
        else -> false
    }

    private fun listenerKey(s: StepNode): List<String> = when (s.type) {
        SparkTypes.SMS -> listOf("senderContains", "bodyContains")
        else -> listOf("packageNames", "titleContains", "textContains", "textMatches")
    }.map { s.paramString(it).trim().lowercase() }

    private fun number(step: StepNode, key: String): Double? = when (val v = step.params[key]) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun precision(step: StepNode): SchedulePrecision =
        SchedulePrecision.entries.firstOrNull { it.name.equals(step.paramString("precision", "exact").trim(), ignoreCase = true) }
            ?: SchedulePrecision.Exact

    private fun validZone(id: String) = runCatching { ZoneId.of(id) }.isSuccess

    private fun error(code: String, message: String, stepId: String? = null, fixHint: String? = null) =
        ValidationIssue(code, message, IssueSeverity.Blocking, stepId, fixHint = fixHint)

    private fun warning(code: String, message: String, stepId: String? = null, fixHint: String? = null, params: List<String> = emptyList()) =
        ValidationIssue(code, message, IssueSeverity.Warning, stepId, params, fixHint)

    companion object {
        /** Shortest sub-daily interval per mode. Flexible rides on WorkManager's 15 minute floor. */
        val SCHEDULE_FLOOR_MINUTES: Map<SchedulePrecision, Int> =
            mapOf(SchedulePrecision.OnTime to 1, SchedulePrecision.Exact to 1, SchedulePrecision.Flexible to 15)

        const val MAX_PATTERN_LENGTH = 200
        const val MAX_GEOFENCE_RADIUS_M = 100_000.0
        const val MIN_RELIABLE_GEOFENCE_RADIUS_M = 100.0
        const val SMS_SEND = "device.sms"
        const val HTTP = "net.http"

        private val FREQUENCIES = setOf("minutes", "hourly", "daily", "weekly", "monthly")
        private val TIME_OF_DAY = Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")
        private val PATH = Regex("^[a-z0-9][a-z0-9._~-]*(/[a-z0-9][a-z0-9._~-]*)*$")
        private val ATTACKER_CONTROLLABLE = setOf(SparkTypes.SMS, SparkTypes.NOTIFICATION, SparkTypes.WEBHOOK, SparkTypes.SHARE)
        private val FOREGROUND_ONLY = setOf("device.clipboard_read", "device.open")
        /** No approval Step exists yet; the id is reserved so the warning clears when one ships. */
        private val APPROVAL_STEPS = setOf("logic.approval")

        /** Trim, drop outer slashes, lower-case; null when the result is not a safe URL path. */
        fun normalizeWebhookPath(raw: String): String? {
            val p = raw.trim().trim('/').lowercase()
            return if (p.length in 1..128 && PATH.matches(p)) p else null
        }
    }
}
