package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishValidatorTest {

    private class Stub(override val spec: NodeSpec) : StepExecutor {
        override suspend fun execute(ctx: StepContext) = StepResult(mapOf(0 to ctx.input))
    }

    private fun trig(id: String) = Stub(NodeSpec(id, id, StepCategory.Trigger, "t", "i", kind = NodeKind.Trigger, canStartFlow = true))
    private fun act(id: String) = Stub(NodeSpec(id, id, StepCategory.Transform, "t", "i"))

    private val validator = PublishValidator(
        FlowValidator(
            registry(
                *(SparkTypes.all - SparkTypes.MANUAL).map { trig(it) }.toTypedArray(),
                act("device.sms"), act("net.http"), act("device.clipboard"), act("device.open"), act("logic.approval"),
            ),
        ),
    )

    private fun spark(type: String, params: Map<String, Any?> = emptyMap(), id: String = "sp", name: String = "My Spark", disabled: Boolean = false) =
        step(id, type, name, params, disabled = disabled)

    private fun flow(vararg steps: StepNode, id: String = "f", streams: List<StreamEdge> = emptyList()) =
        Flow(id = id, name = "Flow $id", steps = persistentListOf(*steps), streams = persistentListOf(*streams.toTypedArray()))

    private fun check(f: Flow, platform: PlatformFacts = PlatformFacts.AllGood, others: List<Flow> = emptyList(), dry: ArmingDryRun? = null) =
        validator.validate(f, PublishContext(platform, others, dry))

    private fun PublishReport.errorCodes() = errors.map { it.code }
    private fun PublishReport.warningCodes() = warnings.map { it.code }

    private fun assertBlocks(code: String, f: Flow, platform: PlatformFacts = PlatformFacts.AllGood, others: List<Flow> = emptyList()): ValidationIssue {
        val r = check(f, platform, others)
        assertFalse("$code should block", r.canPublish)
        return r.errors.firstOrNull { it.code == code } ?: error("expected $code, got ${r.errorCodes()}")
    }

    private fun assertClean(f: Flow, platform: PlatformFacts = PlatformFacts.AllGood, others: List<Flow> = emptyList()) {
        val r = check(f, platform, others)
        assertEquals(emptyList<String>(), r.errorCodes())
        assertTrue(r.canPublish)
    }

    private val schedule = spark(SparkTypes.SCHEDULE, mapOf("frequency" to "daily", "time" to "08:00", "timezone" to "UTC"))

    // ---- Rule 1 and 2 -----------------------------------------------------------------------

    @Test fun `rule 1 structure errors from the shared validator block, a clean flow passes`() {
        assertClean(flow(schedule))
        val bad = flow(schedule, streams = listOf(StreamEdge("e", "sp", "ghost", 0)))
        assertBlocks("dangling_stream", bad)
        assertBlocks("unknown_step_type", flow(schedule, step("x", "nope.nothing")))
        assertBlocks("empty_flow", Flow(id = "e", name = "e"))
    }

    @Test fun `rule 1 structure warnings stay warnings`() {
        val f = flow(schedule, step("lonely", "device.open", name = "Lonely"))
        val r = check(f)
        assertTrue(r.canPublish)
        assertTrue("unconnected_step" in r.warningCodes())
    }

    @Test fun `rule 2 needs an enabled non-manual spark`() {
        assertBlocks("no_live_spark", flow(spark(SparkTypes.MANUAL)))
        assertBlocks("no_live_spark", flow(spark(SparkTypes.SCHEDULE, disabled = true)))
        assertClean(flow(schedule))
        assertClean(flow(spark(SparkTypes.BOOT)))
    }

    // ---- Rule 3: schedule ------------------------------------------------------------------

    @Test fun `schedule time zone frequency and interval rules`() {
        assertBlocks("schedule_time", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "daily", "time" to "25:00"))))
        assertClean(flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "daily", "time" to "7:05"))))
        assertBlocks("schedule_zone", flow(spark(SparkTypes.SCHEDULE, mapOf("timezone" to "Mars/Base"))))
        assertClean(flow(spark(SparkTypes.SCHEDULE, mapOf("timezone" to "device"))))
        assertBlocks("schedule_frequency", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "fortnightly"))))
        assertBlocks("schedule_interval", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "minutes", "interval" to 0))))
        assertClean(flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "minutes", "interval" to "2", "precision" to "exact"))))
        assertClean(flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "minutes"))))
    }

    @Test fun `interval floor depends on precision and the message states it`() {
        val flexible = mapOf("frequency" to "minutes", "interval" to 5, "precision" to "flexible")
        val issue = assertBlocks("schedule_interval", flow(spark(SparkTypes.SCHEDULE, flexible)))
        assertTrue(issue.message, issue.message.contains("15 minutes"))
        assertClean(flow(spark(SparkTypes.SCHEDULE, flexible + ("interval" to 15))))
        assertBlocks("schedule_interval", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "minutes", "interval" to 0.5, "precision" to "ontime"))))
        assertClean(flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "minutes", "interval" to 1, "precision" to "OnTime"))))
    }

    @Test fun `weekly days and monthly day of month`() {
        assertBlocks("schedule_days", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "weekly", "daysOfWeek" to "1,8"))))
        assertBlocks("schedule_days", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "weekly", "daysOfWeek" to "mon"))))
        assertClean(flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "weekly", "daysOfWeek" to "1, 3,7"))))
        assertBlocks("schedule_day_of_month", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "monthly", "dayOfMonth" to 31))))
        assertBlocks("schedule_day_of_month", flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "monthly", "dayOfMonth" to 0))))
        assertClean(flow(spark(SparkTypes.SCHEDULE, mapOf("frequency" to "monthly", "dayOfMonth" to 28))))
    }

    // ---- Rule 3: webhook -------------------------------------------------------------------

    @Test fun `webhook path rules`() {
        assertBlocks("webhook_path", flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "bad path!"))))
        assertBlocks("webhook_path", flow(spark(SparkTypes.WEBHOOK, mapOf("path" to ""))))
        assertBlocks("webhook_path", flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "a//b"))))
        assertClean(flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "/Orders/New/"))))
        assertEquals("orders/new", PublishValidator.normalizeWebhookPath(" /Orders/New/ "))
        assertEquals(null, PublishValidator.normalizeWebhookPath("a b"))
    }

    @Test fun `webhook path must be unique inside the flow and across published flows`() {
        val a = spark(SparkTypes.WEBHOOK, mapOf("path" to "hook"), id = "a", name = "Hook A")
        val b = spark(SparkTypes.WEBHOOK, mapOf("path" to "/HOOK/"), id = "b", name = "Hook B")
        assertBlocks("webhook_path_duplicate", flow(a, b))
        val other = flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "hook")), id = "other")
        val issue = assertBlocks("webhook_path_taken", flow(a), others = listOf(other))
        assertTrue(issue.message, issue.message.contains("Flow other") && issue.message.contains("Hook A"))
        assertClean(flow(a), others = listOf(flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "different")), id = "o2")))
        // A disabled webhook elsewhere does not hold the path.
        assertClean(flow(a), others = listOf(flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "hook"), disabled = true), id = "o3")))
    }

    @Test fun `webhook method and token`() {
        assertBlocks("webhook_method", flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "x", "method" to "DELETE"))))
        assertClean(flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "x", "method" to "any"))))
        assertBlocks("webhook_token", flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "x"))), PlatformFacts.AllGood.copy(webhookTokenExists = false))
        assertClean(flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "x"))))
    }

    // ---- Rule 3: notification and SMS ------------------------------------------------------

    @Test fun `notification regex filter and access rules`() {
        val n = SparkTypes.NOTIFICATION
        assertBlocks("notification_regex", flow(spark(n, mapOf("textMatches" to "(unclosed"))))
        assertBlocks("notification_regex", flow(spark(n, mapOf("textMatches" to "a".repeat(201)))))
        assertClean(flow(spark(n, mapOf("textMatches" to "(?i)(credited|debited)"))))
        assertBlocks("notification_no_filter", flow(spark(n)))
        assertClean(flow(spark(n, mapOf("acknowledgeAll" to true))))
        assertClean(flow(spark(n, mapOf("packageNames" to "com.whatsapp"))))
        assertBlocks("notification_access", flow(spark(n, mapOf("titleContains" to "x"))), PlatformFacts.AllGood.copy(notificationAccessBound = false))
    }

    @Test fun `sms filter and permission rules`() {
        val s = SparkTypes.SMS
        assertBlocks("sms_no_filter", flow(spark(s)))
        assertClean(flow(spark(s, mapOf("senderContains" to "HDFC"))))
        assertClean(flow(spark(s, mapOf("bodyContains" to "debited"))))
        assertClean(flow(spark(s, mapOf("acknowledgeAll" to true))))
        assertBlocks("sms_permission", flow(spark(s, mapOf("senderContains" to "x"))), PlatformFacts.AllGood.copy(receiveSmsGranted = false))
    }

    // ---- Rule 3: geofence, bluetooth, battery ----------------------------------------------

    @Test fun `geofence coordinates radius and permissions`() {
        val g = SparkTypes.GEOFENCE
        val ok = mapOf("latitude" to 20.29, "longitude" to 85.82, "radiusMeters" to 150)
        assertClean(flow(spark(g, ok)))
        assertBlocks("geofence_coordinates", flow(spark(g, ok + ("latitude" to 95))))
        assertBlocks("geofence_coordinates", flow(spark(g, ok + ("longitude" to -181))))
        assertBlocks("geofence_coordinates", flow(spark(g, mapOf("radiusMeters" to 100))))
        assertBlocks("geofence_radius", flow(spark(g, ok + ("radiusMeters" to 0))))
        assertBlocks("geofence_radius", flow(spark(g, ok + ("radiusMeters" to 500_000))))
        assertBlocks("geofence_permission", flow(spark(g, ok)), PlatformFacts.AllGood.copy(backgroundLocationGranted = false))
        assertBlocks("geofence_permission", flow(spark(g, ok)), PlatformFacts.AllGood.copy(fineLocationGranted = false))
    }

    @Test fun `a small geofence radius is only a warning`() {
        val r = check(flow(spark(SparkTypes.GEOFENCE, mapOf("latitude" to 1, "longitude" to 1, "radiusMeters" to 99))))
        assertTrue(r.canPublish)
        assertTrue("geofence_small_radius" in r.warningCodes())
        assertFalse("geofence_small_radius" in check(flow(spark(SparkTypes.GEOFENCE, mapOf("latitude" to 1, "longitude" to 1, "radiusMeters" to 100)))).warningCodes())
    }

    @Test fun `bluetooth needs a device and battery needs a sane threshold`() {
        assertBlocks("bluetooth_device", flow(spark(SparkTypes.BLUETOOTH)))
        assertClean(flow(spark(SparkTypes.BLUETOOTH, mapOf("deviceNameContains" to "Car"))))
        assertClean(flow(spark(SparkTypes.BLUETOOTH, mapOf("deviceAddress" to "AA:BB:CC:DD:EE:FF"))))
        assertBlocks("battery_threshold", flow(spark(SparkTypes.BATTERY, mapOf("threshold" to 0))))
        assertBlocks("battery_threshold", flow(spark(SparkTypes.BATTERY, mapOf("threshold" to 100))))
        assertClean(flow(spark(SparkTypes.BATTERY, mapOf("threshold" to 20))))
        assertClean(flow(spark(SparkTypes.BATTERY)))
    }

    // ---- Rule 5: arming dry run ------------------------------------------------------------

    @Test fun `dry run failures block and name the spark, passing dry run is clean`() {
        val f = flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "x"), name = "Orders hook"))
        val failing = ArmingDryRun { _, sparks -> listOf(RegistrationFailure(sparks.single().id, FailureCode.PortInUse, "port 8080 is busy")) }
        val r = check(f, dry = failing)
        assertFalse(r.canPublish)
        val e = r.errors.single { it.code == "arm_failed" }
        assertEquals("sp", e.stepId)
        assertTrue(e.message, e.message.contains("Orders hook") && e.message.contains("port 8080 is busy"))
        assertTrue(check(f, dry = ArmingDryRun { _, _ -> emptyList() }).canPublish)
    }

    @Test fun `dry run only runs when nothing else blocks`() {
        var calls = 0
        val dry = ArmingDryRun { _, _ -> calls++; emptyList() }
        check(flow(spark(SparkTypes.SCHEDULE, mapOf("time" to "99:99"))), dry = dry)
        assertEquals(0, calls)
        check(flow(schedule), dry = dry)
        assertEquals(1, calls)
    }

    // ---- Warnings --------------------------------------------------------------------------

    @Test fun `no warnings when the environment is healthy`() {
        assertEquals(emptyList<ValidationIssue>(), check(flow(schedule)).warnings)
    }

    @Test fun `battery optimisation warning is critical only for engine flows`() {
        val p = PlatformFacts.AllGood.copy(batteryOptimisationExempt = false)
        val web = check(flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "x"))), p).warnings.single { it.code == "battery_optimisation" }
        assertTrue("critical" in web.params)
        val sched = check(flow(schedule), p).warnings.single { it.code == "battery_optimisation" }
        assertFalse("critical" in sched.params)
    }

    @Test fun `exact alarm warning names the schedule, notifications warning only for engine flows`() {
        val noAlarm = PlatformFacts.AllGood.copy(exactAlarmAllowed = false)
        val w = check(flow(spark(SparkTypes.SCHEDULE, name = "Morning report")), noAlarm).warnings.single { it.code == "exact_alarm_missing" }
        assertTrue(w.message.contains("Morning report"))
        assertFalse("exact_alarm_missing" in check(flow(spark(SparkTypes.BOOT)), noAlarm).warningCodes())
        val noNotif = PlatformFacts.AllGood.copy(notificationsAllowed = false)
        assertTrue("notifications_blocked" in check(flow(spark(SparkTypes.WEBHOOK, mapOf("path" to "x"))), noNotif).warningCodes())
        assertFalse("notifications_blocked" in check(flow(schedule), noNotif).warningCodes())
    }

    @Test fun `oem autostart unverified is a warning`() {
        val r = check(flow(schedule), PlatformFacts.AllGood.copy(oemAutostartVerified = false))
        assertTrue(r.canPublish)
        assertEquals(listOf("oem_autostart_unverified"), r.warningCodes())
    }

    @Test fun `side effect after an attacker controllable spark warns until an approval step exists`() {
        val sms = spark(SparkTypes.SMS, mapOf("senderContains" to "BANK"))
        val post = step("post", "net.http", "Send to server", mapOf("method" to "POST"))
        val risky = check(flow(sms, post, streams = listOf(StreamEdge("e", "sp", "post", 0))))
        val w = risky.warnings.single { it.code == "side_effect_without_approval" }
        assertEquals("post", w.stepId)
        assertTrue(w.message.contains("Send to server"))
        assertTrue(risky.canPublish)
        val get = step("get", "net.http", "Read", mapOf("method" to "GET"))
        assertFalse("side_effect_without_approval" in check(flow(sms, get)).warningCodes())
        assertFalse("side_effect_without_approval" in check(flow(schedule, post)).warningCodes())
        assertFalse("side_effect_without_approval" in check(flow(sms, post, step("ok", "logic.approval"))).warningCodes())
        val hook = spark(SparkTypes.WEBHOOK, mapOf("path" to "x"))
        assertTrue("side_effect_without_approval" in check(flow(hook, post)).warningCodes())
    }

    @Test fun `sms flow that sends sms gets a loop warning`() {
        val sms = spark(SparkTypes.SMS, mapOf("senderContains" to "BANK"))
        val send = step("send", "device.sms", "Reply")
        val r = check(flow(sms, send))
        assertTrue("sms_loop" in r.warningCodes())
        assertFalse("sms_loop" in check(flow(schedule, send)).warningCodes())
        assertFalse("sms_loop" in check(flow(sms)).warningCodes())
    }

    @Test fun `foreground only steps warn`() {
        assertTrue("foreground_only_step" in check(flow(schedule, step("c", "device.clipboard", "Read clipboard", params = mapOf("operation" to "read")))).warningCodes())
        assertFalse("copying is fine in the background", "foreground_only_step" in check(flow(schedule, step("c", "device.clipboard", "Copy", params = mapOf("operation" to "write")))).warningCodes())
        assertTrue("foreground_only_step" in check(flow(schedule, step("o", "device.open"))).warningCodes())
        assertFalse("foreground_only_step" in check(flow(schedule, step("d", "device.open", disabled = true))).warningCodes())
    }

    @Test fun `duplicate listener warning for the same notification or sms filter`() {
        val mine = flow(spark(SparkTypes.SMS, mapOf("senderContains" to "HDFC")))
        val twin = flow(spark(SparkTypes.SMS, mapOf("senderContains" to " hdfc ")), id = "twin")
        val w = check(mine, others = listOf(twin)).warnings.single { it.code == "duplicate_listener" }
        assertTrue(w.message.contains("Flow twin"))
        val different = flow(spark(SparkTypes.SMS, mapOf("senderContains" to "ICICI")), id = "d")
        assertFalse("duplicate_listener" in check(mine, others = listOf(different)).warningCodes())
        val n1 = flow(spark(SparkTypes.NOTIFICATION, mapOf("packageNames" to "com.a", "textContains" to "otp")))
        val n2 = flow(spark(SparkTypes.NOTIFICATION, mapOf("packageNames" to "com.a", "textContains" to "otp")), id = "n2")
        assertTrue("duplicate_listener" in check(n1, others = listOf(n2)).warningCodes())
        assertFalse("duplicate_listener" in check(n1, others = listOf(twin)).warningCodes())
    }

    @Test fun `warnings never block and need acknowledgement`() {
        val allBad = PlatformFacts.AllGood.copy(
            batteryOptimisationExempt = false, exactAlarmAllowed = false, notificationsAllowed = false, oemAutostartVerified = false,
        )
        val r = check(flow(schedule, step("o", "device.open")), allBad)
        assertTrue(r.warnings.size >= 3)
        assertTrue(r.errors.isEmpty())
        assertTrue(r.canPublish)
        assertTrue(r.needsAcknowledgement)
        assertFalse(check(flow(schedule)).needsAcknowledgement)
        assertNotNull(r.warnings.first().fixHint)
    }

    @Test fun `errors outrank warnings in the report`() {
        val r = check(flow(spark(SparkTypes.SMS)), PlatformFacts.AllGood.copy(oemAutostartVerified = false))
        assertFalse(r.canPublish)
        assertFalse(r.needsAcknowledgement)
        assertTrue(r.warnings.isNotEmpty())
    }
}
