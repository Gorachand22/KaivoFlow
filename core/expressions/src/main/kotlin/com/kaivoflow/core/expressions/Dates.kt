package com.kaivoflow.core.expressions

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Date helpers.
 *
 * Dates are instants plus a time zone. The zone comes from `$run.timezone` in the scope (UTC when
 * absent) unless a function takes an explicit zone id such as `"Asia/Kolkata"`.
 *
 * Parsing accepts: ISO instants and offsets (`2026-11-13T04:30:00Z`, `...+05:30`), local date-times
 * (`2026-11-13T10:00`, `2026-11-13 10:00:00`, read in the scope zone), dates (`2026-11-13`, start of
 * day in the scope zone) and numbers (epoch milliseconds). Anything else is invalid.
 *
 * Units for plus, minus, diff, startOf and endOf: `millisecond`, `second`, `minute`, `hour`, `day`,
 * `week` (starts Monday), `month`, `quarter`, `year`; plurals and the short forms ms, s, min, h, d, w,
 * y are accepted. plus/minus take whole numbers.
 *
 * Format patterns are `java.time` pattern letters with English names: yyyy year, MM month number,
 * MMM short month, MMMM month name, dd day, EEE short weekday, EEEE weekday name, HH hour 00-23,
 * hh hour 01-12, a AM/PM, mm minute, ss second, SSS millis, XXX offset like +05:30 (Z for UTC),
 * z zone abbreviation, VV zone id. Quote literals with single quotes. The special pattern `iso`
 * gives ISO-8601 with offset, `date` gives yyyy-MM-dd and `datetime` gives yyyy-MM-dd HH:mm:ss.
 */
internal object DateOps {

    private val ISO_LOCAL_SPACE = Regex("""^\d{4}-\d{2}-\d{2} \d.*$""")

    fun zoneOf(c: CallCtx, id: String): ZoneId =
        try { ZoneId.of(id.trim()) } catch (e: DateTimeException) { c.fail("unknown time zone '$id'", ErrorKind.Unknown) }

    fun parse(text: String, zone: ZoneId): KDate? {
        val t = text.trim()
        if (t.isEmpty()) return null
        try {
            return KDate(ZonedDateTime.parse(t, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant(), zone)
        } catch (_: DateTimeException) {}
        try {
            val fixed = if (ISO_LOCAL_SPACE.matches(t)) t.replaceFirst(' ', 'T') else t
            return KDate(LocalDateTime.parse(fixed, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).toInstant(), zone)
        } catch (_: DateTimeException) {}
        try {
            return KDate(LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(zone).toInstant(), zone)
        } catch (_: DateTimeException) {}
        return null
    }

    fun coerce(c: CallCtx, v: Any?): KDate = when (v) {
        is KDate -> v
        is String -> parse(v, c.st.zone) ?: c.fail("'${v.take(40)}' is not a valid date (use ISO text such as 2026-11-13 or 2026-11-13T10:00:00Z)", ErrorKind.Runtime)
        is Number -> KDate(Instant.ofEpochMilli(v.toLong()), c.st.zone)
        null -> c.fail("a date is required but got null")
        else -> c.fail("a date is required but got ${Rt.typeName(v)}")
    }

    fun now(st: EvalState): KDate {
        val raw = st.scope["now"]
        val instant = (raw as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
        return KDate(instant, st.zone)
    }

    private val UNITS = mapOf(
        "ms" to "millisecond", "millisecond" to "millisecond", "milliseconds" to "millisecond",
        "s" to "second", "sec" to "second", "second" to "second", "seconds" to "second",
        "min" to "minute", "minute" to "minute", "minutes" to "minute",
        "h" to "hour", "hour" to "hour", "hours" to "hour",
        "d" to "day", "day" to "day", "days" to "day",
        "w" to "week", "week" to "week", "weeks" to "week",
        "month" to "month", "months" to "month",
        "quarter" to "quarter", "quarters" to "quarter",
        "y" to "year", "year" to "year", "years" to "year",
    )

    private fun unitError(c: CallCtx, raw: String): Nothing =
        throw c.ip.err(c.span, ErrorKind.Unknown, "${c.name}(): unknown unit '$raw'", suggest(raw.lowercase(), UNITS.keys))

    private fun u(c: CallCtx, raw: String): String = UNITS[raw.trim().lowercase()] ?: unitError(c, raw)

    private fun shift(c: CallCtx, d: KDate, n: Double, unit: String): KDate {
        if (n != Math.floor(n) || Math.abs(n) > 1e9) c.fail("the amount must be a whole number")
        val k = n.toLong()
        val z = d.zdt
        return try {
            val out = when (unit) {
                "millisecond" -> z.plus(k, ChronoUnit.MILLIS)
                "second" -> z.plus(k, ChronoUnit.SECONDS)
                "minute" -> z.plus(k, ChronoUnit.MINUTES)
                "hour" -> z.plus(k, ChronoUnit.HOURS)
                "day" -> z.plusDays(k)
                "week" -> z.plusWeeks(k)
                "month" -> z.plusMonths(k)
                "quarter" -> z.plusMonths(k * 3)
                else -> z.plusYears(k)
            }
            KDate(out.toInstant(), d.zone)
        } catch (e: DateTimeException) {
            c.fail("date out of range", ErrorKind.Runtime)
        }
    }

    private fun startOf(d: KDate, unit: String): ZonedDateTime {
        val z = d.zdt
        return when (unit) {
            "millisecond" -> z
            "second" -> z.truncatedTo(ChronoUnit.SECONDS)
            "minute" -> z.truncatedTo(ChronoUnit.MINUTES)
            "hour" -> z.truncatedTo(ChronoUnit.HOURS)
            "day" -> z.truncatedTo(ChronoUnit.DAYS)
            "week" -> z.minusDays((z.dayOfWeek.value - 1).toLong()).truncatedTo(ChronoUnit.DAYS)
            "month" -> z.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            "quarter" -> z.withMonth((z.get(IsoFields.QUARTER_OF_YEAR) - 1) * 3 + 1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            else -> z.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS)
        }
    }

    fun property(ip: Interp, d: KDate, name: String, span: Span): Any? {
        val z = d.zdt
        return when (name) {
            "year" -> z.year.toDouble()
            "month" -> z.monthValue.toDouble()
            "day" -> z.dayOfMonth.toDouble()
            "hour" -> z.hour.toDouble()
            "minute" -> z.minute.toDouble()
            "second" -> z.second.toDouble()
            "millisecond" -> (z.nano / 1_000_000).toDouble()
            "dayOfWeek" -> z.dayOfWeek.value.toDouble()
            "dayOfYear" -> z.dayOfYear.toDouble()
            "timestamp" -> d.instant.toEpochMilli().toDouble()
            "zone" -> d.zone.id
            else -> {
                if (ip.st.strict) throw ip.err(span, ErrorKind.Unknown, "Dates have no property '$name'",
                    suggest(name, listOf("year", "month", "day", "hour", "minute", "second", "millisecond", "dayOfWeek", "dayOfYear", "timestamp", "zone")))
                null
            }
        }
    }

    private fun formatDate(c: CallCtx, d: KDate, pattern: String, zone: ZoneId): String {
        val z = d.instant.atZone(zone)
        if (pattern.length > 100) c.fail("pattern is too long")
        return when (pattern) {
            "iso" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(z)
            "date" -> DateTimeFormatter.ISO_LOCAL_DATE.format(z)
            "datetime" -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(z)
            else -> try {
                DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(z)
            } catch (e: IllegalArgumentException) {
                c.fail("invalid date pattern '$pattern': ${e.message}", ErrorKind.Runtime)
            } catch (e: DateTimeException) {
                c.fail("cannot format with '$pattern': ${e.message}", ErrorKind.Runtime)
            }
        }
    }

    /** Methods whose receiver is a [KDate]. */
    val methods: Map<String, Fn> = mapOf(
        "format" to Fn(1, 2) { c, r, a ->
            val d = r as KDate
            val zone = if (a.size > 1) zoneOf(c, c.text(a, 1)) else d.zone
            formatDate(c, d, c.text(a, 0), zone)
        },
        "plus" to Fn(2, 2) { c, r, a -> shift(c, r as KDate, c.num(a, 0), u(c, c.text(a, 1))) },
        "minus" to Fn(2, 2) { c, r, a -> shift(c, r as KDate, -c.num(a, 0), u(c, c.text(a, 1))) },
        "diff" to Fn(1, 2) { c, r, a ->
            val d = r as KDate
            val other = coerce(c, a[0])
            val unit = if (a.size > 1) u(c, c.text(a, 1)) else "millisecond"
            val x = d.zdt
            val y = other.instant.atZone(d.zone)
            // Whole units from `other` to this date, truncated toward zero.
            val v = when (unit) {
                "millisecond" -> ChronoUnit.MILLIS.between(y, x)
                "second" -> ChronoUnit.SECONDS.between(y, x)
                "minute" -> ChronoUnit.MINUTES.between(y, x)
                "hour" -> ChronoUnit.HOURS.between(y, x)
                "day" -> ChronoUnit.DAYS.between(y, x)
                "week" -> ChronoUnit.WEEKS.between(y, x)
                "month" -> ChronoUnit.MONTHS.between(y, x)
                "quarter" -> ChronoUnit.MONTHS.between(y, x) / 3
                else -> ChronoUnit.YEARS.between(y, x)
            }
            v.toDouble()
        },
        "startOf" to Fn(1, 1) { c, r, a ->
            val d = r as KDate
            KDate(startOf(d, u(c, c.text(a, 0))).toInstant(), d.zone)
        },
        "endOf" to Fn(1, 1) { c, r, a ->
            val d = r as KDate
            val unit = u(c, c.text(a, 0))
            val start = KDate(startOf(d, unit).toInstant(), d.zone)
            val next = shift(c, start, 1.0, unit)
            KDate(next.instant.minusMillis(1), d.zone)
        },
        "toIso" to Fn(0, 0) { _, r, _ -> (r as KDate).iso() },
        "toMillis" to Fn(0, 0) { _, r, _ -> (r as KDate).instant.toEpochMilli().toDouble() },
        "inZone" to Fn(1, 1) { c, r, a -> KDate((r as KDate).instant, zoneOf(c, c.text(a, 0))) },
        "isBefore" to Fn(1, 1) { c, r, a -> (r as KDate).instant.isBefore(coerce(c, a[0]).instant) },
        "isAfter" to Fn(1, 1) { c, r, a -> (r as KDate).instant.isAfter(coerce(c, a[0]).instant) },
    )
}
