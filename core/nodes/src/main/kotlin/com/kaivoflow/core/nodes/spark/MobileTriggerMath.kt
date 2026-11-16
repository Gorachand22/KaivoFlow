package com.kaivoflow.core.nodes

/**
 * A light validation helper shared by Sparks with numeric coordinates/intervals. Kept here so the
 * core:nodes module stays free of Android and can be unit-tested on the JVM.
 */
internal object MobileTriggerMath {
    /** Great-circle distance in metres, used to double-check a proximity event. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        fun radians(d: Double) = d * Math.PI / 180.0
        val dLat = radians(lat2 - lat1)
        val dLon = radians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(radians(lat1)) * Math.cos(radians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * radius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
