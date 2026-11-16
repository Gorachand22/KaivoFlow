package com.kaivoflow.core.nodes

/**
 * Sparks (triggers).
 *
 * A Spark executor does almost nothing at run time — it simply forwards the items the Run was
 * triggered with. All the interesting work (registering a schedule, receiving a webhook, listening
 * for notifications) happens in `:core:work`, driven by the same params. Keeping the two in sync
 * through [TriggerSpecs] is what stops "the trigger is configured but never fires" bugs.
 */
object TriggerSpecs {
    const val MANUAL = "trigger.manual"
    const val SCHEDULE = "trigger.schedule"
    const val WEBHOOK = "trigger.webhook"
    const val NOTIFICATION = "trigger.notification"
    const val SMS = "trigger.sms"
    const val SHARE = "trigger.share"
    const val BATTERY_LOW = "trigger.battery"
    const val GEOFENCE = "trigger.geofence"
    const val BLUETOOTH = "trigger.bluetooth"
    const val WIFI = "trigger.wifi"
    const val HEADSET = "trigger.headset"
    const val BOOT = "trigger.boot"

    val all = listOf(
        MANUAL, SCHEDULE, WEBHOOK, NOTIFICATION, SMS, SHARE, BATTERY_LOW,
        GEOFENCE, BLUETOOTH, WIFI, HEADSET, BOOT,
    )

    val timeZones = listOf("Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Europe/London", "America/New_York", "UTC")
}
