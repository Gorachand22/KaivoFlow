package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatesTest {
    private val ist = SCOPE + ("run" to mapOf("id" to "r", "timezone" to "Asia/Kolkata"))
    private val ny = SCOPE + ("run" to mapOf("id" to "r", "timezone" to "America/New_York"))

    @Test fun formatting() = checkCases(listOf(
        "now().format(\"yyyy-MM-dd HH:mm\")" to "2026-11-13 04:30", "now().format(\"date\")" to "2026-11-13", "now().format(\"datetime\")" to "2026-11-13 04:30:00",
        "now().format(\"iso\")" to "2026-11-13T04:30:00Z", "now().format(\"EEEE, MMMM d\")" to "Friday, November 13", "now().format(\"EEE MMM dd yy\")" to "Fri Nov 13 26",
        "now().format(\"hh:mm a\")" to "04:30 AM", "now().format(\"HH:mm\", \"America/New_York\")" to "23:30", "now().format(\"HH:mm XXX\", \"Asia/Tokyo\")" to "13:30 +09:00",
        "now().toIso()" to "2026-11-13T04:30:00Z", "today().toIso()" to "2026-11-13T00:00:00Z", "now().toMillis()" to 1794544200000.0,
        "Date.format(now(), \"yyyy\")" to "2026", "Date.format(\"2026-03-05\", \"d/M/yyyy\")" to "5/3/2026", "\$now.format(\"date\")" to "2026-11-13", "\$today.format(\"MMM\")" to "Nov",
    ))

    @Test fun parsing() = checkCases(listOf(
        "Date.parse(\"2026-11-13\").toIso()" to "2026-11-13T00:00:00Z", "Date.parse(\"2026-11-13T10:15:30\").toIso()" to "2026-11-13T10:15:30Z",
        "Date.parse(\"2026-11-13 10:15\").toIso()" to "2026-11-13T10:15:00Z", "Date.parse(\"2026-11-13T10:00:00+05:30\").format(\"HH:mm\")" to "04:30",
        "Date.parse(\"2026-11-13T10:00:00Z\").format(\"HH:mm\")" to "10:00", "Date.parse(1700000000000).format(\"date\")" to "2023-11-14", "Date.fromMillis(0).format(\"date\")" to "1970-01-01",
        "Date.parse(\"garbage\")" to null, "Date.parse(\"\")" to null, "Date.parse(\"2026-13-45\")" to null, "Date.parse(null)" to null,
        "Date.parse(\"2026-11-13T10:00\", \"Asia/Kolkata\").toIso()" to "2026-11-13T10:00:00+05:30", "Date.now().toIso()" to "2026-11-13T04:30:00Z", "Date.today().format(\"date\")" to "2026-11-13",
    ))

    @Test fun arithmetic() = checkCases(listOf(
        "now().plus(1, \"day\").format(\"date\")" to "2026-11-14", "now().plus(2, \"months\").format(\"date\")" to "2027-01-13", "now().plus(1, \"year\").format(\"date\")" to "2027-11-13",
        "now().minus(1, \"hour\").format(\"HH:mm\")" to "03:30", "now().plus(90, \"minutes\").format(\"HH:mm\")" to "06:00", "now().plus(1, \"week\").format(\"date\")" to "2026-11-20",
        "now().plus(1, \"quarter\").format(\"date\")" to "2027-02-13", "now().plus(500, \"ms\").format(\"ss.SSS\")" to "00.500", "now().minus(30, \"s\").format(\"mm:ss\")" to "29:30",
        "Date.parse(\"2026-03-31\").plus(1, \"month\").format(\"date\")" to "2026-04-30", "Date.parse(\"2024-02-29\").plus(1, \"year\").format(\"date\")" to "2025-02-28", "now().plus(-1, \"d\").format(\"date\")" to "2026-11-12",
        "Date.plus(now(), 1, \"day\").format(\"date\")" to "2026-11-14", "\"2026-11-13T10:00:00\".plus(1, \"hour\")" to "2026-11-13T11:00:00Z", "\$now.minus(1, \"day\").format(\"date\")" to "2026-11-12",
        "Date.parse(\"2026-11-20\").diff(\"2026-11-13\", \"days\")" to 7.0, "Date.parse(\"2026-11-13\").diff(\"2026-11-20\", \"days\")" to -7.0, "now().diff(now().minus(3, \"hours\"), \"hours\")" to 3.0,
        "Date.parse(\"2026-12-01\").diff(\"2026-11-01\", \"month\")" to 1.0, "Date.parse(\"2028-01-01\").diff(\"2026-01-01\", \"years\")" to 2.0, "Date.parse(\"2026-11-14\").diff(\"2026-11-13\")" to 86_400_000.0,
        "Date.parse(\"2026-11-13T10:00\").diff(\"2026-11-13T09:30\", \"min\")" to 30.0, "Date.parse(\"2026-12-13\").diff(\"2026-03-13\", \"quarter\")" to 3.0, "Date.diff(\"2026-11-20\", \"2026-11-13\", \"w\")" to 1.0,
    ))

    @Test fun startAndEndOf() = checkCases(listOf(
        "now().startOf(\"day\").format(\"HH:mm\")" to "00:00", "now().startOf(\"week\").format(\"date\")" to "2026-11-09", "now().startOf(\"month\").format(\"date\")" to "2026-11-01",
        "now().startOf(\"year\").format(\"date\")" to "2026-01-01", "now().startOf(\"quarter\").format(\"date\")" to "2026-10-01", "now().startOf(\"hour\").format(\"mm\")" to "00",
        "now().endOf(\"month\").format(\"yyyy-MM-dd HH:mm:ss.SSS\")" to "2026-11-30 23:59:59.999", "now().endOf(\"day\").format(\"HH:mm:ss\")" to "23:59:59", "now().endOf(\"week\").format(\"date\")" to "2026-11-15",
        "now().endOf(\"year\").format(\"date\")" to "2026-12-31", "Date.endOf(\"2024-02-10\", \"month\").format(\"date\")" to "2024-02-29", "Date.startOf(\"2026-11-13T10:20:30\", \"minute\").format(\"ss\")" to "00",
    ))

    @Test fun propertiesAndComparison() = checkCases(listOf(
        "now().year" to 2026.0, "now().month" to 11.0, "now().day" to 13.0, "now().hour" to 4.0, "now().minute" to 30.0, "now().dayOfWeek" to 5.0, "now().dayOfYear" to 317.0,
        "now().zone" to "UTC", "now().timestamp" to 1794544200000.0, "now() > Date.parse(\"2026-01-01\")" to true, "now() < Date.parse(\"2026-01-01\")" to false,
        "now() == Date.parse(\"2026-11-13T04:30:00Z\")" to true, "now().isBefore(\"2027-01-01\")" to true, "now().isAfter(\"2027-01-01\")" to false, "\$now > \"2026-01-01\"" to true,
        "now().inZone(\"Asia/Tokyo\").hour" to 13.0, "now().plus(1, \"day\").day" to 14.0,
    ))

    @Test fun timeZoneFromScope() {
        assertEquals("10:00", ev("now().format(\"HH:mm\")", ist))
        assertEquals("2026-11-13", ev("today().format(\"date\")", ist))
        assertEquals("2026-11-13T00:00:00+05:30", ev("today().toIso()", ist))
        assertEquals("2026-11-13T10:00:00+05:30", ev("now().toIso()", ist))
        assertEquals("2026-11-12T23:30:00-05:00", ev("now().toIso()", ny))
        assertEquals("2026-11-12", ev("now().format(\"date\")", ny))
        assertEquals("2026-11-13T00:00:00+05:30", ev("Date.parse(\"2026-11-13\").toIso()", ist))
        assertEquals("UTC", ev("now().zone", SCOPE - "run"))
    }

    @Test fun dailySavingTimeKeepsLocalTimeForDays() {
        val scope = SCOPE + ("now" to "2026-03-07T15:00:00Z") + ("run" to mapOf("timezone" to "America/New_York"))
        assertEquals("10:00", ev("now().plus(1, \"day\").format(\"HH:mm\")", scope))
        assertEquals("11:00", ev("now().plus(24, \"hours\").format(\"HH:mm\")", scope))
    }

    @Test fun invalidDatesAndUnitsGiveClearErrors() {
        val bad = failure("\"nope\".plus(1, \"day\")")
        assertKind(ErrorKind.Runtime, bad)
        assertTrue(bad.message!!.contains("not a valid date"))
        val unit = failure("now().plus(1, \"dya\")")
        assertKind(ErrorKind.Unknown, unit)
        assertEquals("day", unit.suggestion)
        assertKind(ErrorKind.Runtime, failure("now().format(\"qqqqqqq\")"))
        assertKind(ErrorKind.Unknown, failure("now().format(\"HH\", \"Mars/Base\")"))
        assertKind(ErrorKind.Type, failure("now().plus(1.5, \"day\")"))
    }

    @Test fun datesNeverLeakAsObjects() {
        assertEquals("2026-11-13T04:30:00Z", ev("now()"))
        assertEquals(listOf("2026-11-13T04:30:00Z"), ev("[now()]"))
        assertEquals(mapOf("d" to "2026-11-14T04:30:00Z"), ev("{d: now().plus(1, \"day\")}"))
        assertEquals("at 2026-11-13T04:30:00Z", tpl("at {{ now() }}"))
    }
}
