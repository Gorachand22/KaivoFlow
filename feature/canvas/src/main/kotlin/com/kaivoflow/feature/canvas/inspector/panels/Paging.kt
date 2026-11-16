package com.kaivoflow.feature.canvas.inspector.panels

import kotlin.math.max
import kotlin.math.min

/** What the pager bar shows. [page] is 1-based; with no items [pageCount] is 1. */
data class PagerInfo(
    val page: Int,
    val pageCount: Int,
    val from: Int,
    val to: Int,
    val total: Int,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    val prevOffset: Int,
    val nextOffset: Int,
) {
    val pageLabel: String get() = "$page / $pageCount"
    val rangeLabel: String get() = if (total == 0) "0" else "$from-$to"
}

object Paging {
    fun info(offset: Int, size: Int, total: Int): PagerInfo {
        val s = max(1, size)
        val t = max(0, total)
        val pages = max(1, (t + s - 1) / s)
        val off = clampOffset(offset, s, t)
        val page = off / s + 1
        return PagerInfo(
            page = page, pageCount = pages,
            from = if (t == 0) 0 else off + 1, to = min(t, off + s), total = t,
            hasPrev = off > 0, hasNext = off + s < t,
            prevOffset = max(0, off - s), nextOffset = if (off + s < t) off + s else off,
        )
    }

    /** Offset of the first item of 1-based [page], kept inside the data. */
    fun offsetForPage(page: Int, size: Int, total: Int): Int = clampOffset((max(1, page) - 1) * max(1, size), size, total)

    fun clampOffset(offset: Int, size: Int, total: Int): Int {
        val s = max(1, size)
        if (total <= 0) return 0
        val last = ((total - 1) / s) * s
        return min(max(0, offset) / s * s, last)
    }

    /** Offset that keeps the first visible item on screen after the page size changes. */
    fun offsetAfterSizeChange(offset: Int, newSize: Int, total: Int): Int = clampOffset(offset, newSize, total)

    /** `12 items`, `12 of 480` when the stored data is capped, `4 of 25 rows` is built by [matchLabel]. */
    fun countLabel(stored: Int, trueTotal: Int): String {
        val noun = if (stored == 1) "item" else "items"
        return if (trueTotal > stored) "$stored of $trueTotal items" else "$stored $noun"
    }

    /** Banner for a search: `4 of 25 rows`. */
    fun matchLabel(matching: Int, stored: Int): String = "$matching of $stored rows"
}
