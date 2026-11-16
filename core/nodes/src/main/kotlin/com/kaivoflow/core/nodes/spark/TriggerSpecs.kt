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
    const val MANUAL = "spark.manual"
    const val SCHEDULE = "spark.schedule"
    const val WEBHOOK = "spark.webhook"
    const val NOTIFICATION = "spark.notification"
    const val SMS = "spark.sms"
    const val SHARE = "spark.share"
    const val BATTERY_LOW = "spark.battery"
    const val GEOFENCE = "spark.geofence"
    const val BLUETOOTH = "spark.bluetooth"
    const val WIFI = "spark.wifi"
    const val HEADSET = "spark.headset"
    const val BOOT = "spark.boot"

    val all = listOf(
        MANUAL, SCHEDULE, WEBHOOK, NOTIFICATION, SMS, SHARE, BATTERY_LOW,
        GEOFENCE, BLUETOOTH, WIFI, HEADSET, BOOT,
    )

    val timeZones = listOf("Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Europe/London", "America/New_York", "UTC")
}
