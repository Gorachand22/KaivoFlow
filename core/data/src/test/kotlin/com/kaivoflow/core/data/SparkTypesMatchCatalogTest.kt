package com.kaivoflow.core.data

import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.nodes.TriggerSpecs
import org.junit.Assert.assertEquals
import org.junit.Test

/** core:engine mirrors the Spark ids because core:nodes depends on it. This keeps the copies honest. */
class SparkTypesMatchCatalogTest {
    @Test fun engineSparkIdsEqualTheCatalogIds() {
        assertEquals(TriggerSpecs.MANUAL, SparkTypes.MANUAL)
        assertEquals(TriggerSpecs.SCHEDULE, SparkTypes.SCHEDULE)
        assertEquals(TriggerSpecs.WEBHOOK, SparkTypes.WEBHOOK)
        assertEquals(TriggerSpecs.NOTIFICATION, SparkTypes.NOTIFICATION)
        assertEquals(TriggerSpecs.SMS, SparkTypes.SMS)
        assertEquals(TriggerSpecs.SHARE, SparkTypes.SHARE)
        assertEquals(TriggerSpecs.BATTERY_LOW, SparkTypes.BATTERY)
        assertEquals(TriggerSpecs.GEOFENCE, SparkTypes.GEOFENCE)
        assertEquals(TriggerSpecs.BLUETOOTH, SparkTypes.BLUETOOTH)
        assertEquals(TriggerSpecs.WIFI, SparkTypes.WIFI)
        assertEquals(TriggerSpecs.HEADSET, SparkTypes.HEADSET)
        assertEquals(TriggerSpecs.BOOT, SparkTypes.BOOT)
    }
}
