package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test fun safeNavigationByDefault() = checkCases(listOf(
        "\$json.a.b.c" to 42.0, "\$json.a.b" to mapOf("c" to 42.0), "\$json.a.x.c" to null, "\$json.missing.deep.deeper" to null,
        "\$json.nil.x" to null, "\$json.nil.x.y.z" to null, "\$json.tags[10]" to null, "\$json.tags[-1]" to null,
        "\$json.merchant.x" to null, "\$json.qty.x" to null, "\$json.flag.x" to null, "\$json.missing[0]" to null,
        "\$json.missing[\"k\"]" to null, "\$json[\"missing\"][\"k\"]" to null, "\$json.nil?.x" to null, "\$json?.a?.b?.c" to 42.0,
        "\$json.missing?.[0]" to null, "\$json.missing?.trim()" to null, "\$json.missing.trim()" to null,
    ))

    @Test fun indexAndBracketAccess() = checkCases(listOf(
        "\$items[1].id" to 2, "\$items[0]" to mapOf("id" to 1), "\$json.tags[0]" to "x", "\$json[\"a\"][\"b\"][\"c\"]" to 42.0,
        "\$json['name'].trim()" to "Ada Lovelace", "\"abc\"[1]" to "b", "\"abc\"[5]" to null, "\$json.tags[\"1\"]" to "y",
        "\$json.tags[1.5]" to null, "\$json.rows[1].n" to "b", "\$json.rows[0 + 2].v" to 3.0,
        "\$json.tags.length" to 3.0, "\$json.merchant.length" to 4.0, "\$json.tags.first" to "x", "\$json.tags.last" to "x",
    ))

    @Test fun whitespaceAroundDotsAndDollar() {
        assertEquals(500, tpl("{{ \$json . amount }}", mapOf("json" to mapOf("amount" to 500))))
        assertEquals(mapOf("amount" to 1), tpl("{{\$ json}}", mapOf("json" to mapOf("amount" to 1))))
    }

    @Test fun strictModeReportsMissingPathsWithSuggestion() {
        val e = failure("\$json.merchnt", strict = true)
        assertKind(ErrorKind.Unknown, e)
        assertEquals("merchant", e.suggestion)
        assertTrue(e.message!!.contains("Did you mean 'merchant'"))
    }

    @Test fun strictModeNullReceiverAndOptionalChaining() {
        assertKind(ErrorKind.Type, failure("\$json.nil.x", strict = true))
        assertNull(ev("\$json.nil?.x", strict = true))
        assertNull(ev("\$json.nil?.x.y", strict = true))
        assertKind(ErrorKind.Unknown, failure("\$json.tags[9]", strict = true))
        assertKind(ErrorKind.Type, failure("\$json.nil.trim()", strict = true))
        assertNull(ev("\$json.nil?.trim()", strict = true))
    }

    @Test fun strictModeStillAcceptsExistingPaths() {
        assertEquals(42.0, ev("\$json.a.b.c", strict = true))
        assertEquals("Cafe", ev("\$json.merchant", strict = true))
        assertEquals(3.0, ev("\$json.tags.length", strict = true))
    }

    @Test fun existingNullValueIsNotMissing() {
        assertNull(ev("\$json.nil", strict = true))
    }

    @Test fun stepStreamMembers() = checkCases(listOf(
        "\$step(\"Two\").count" to 2.0, "\$step(\"Two\").length" to 2.0, "\$step(\"Two\").items.length" to 2.0,
        "\$step(\"Two\").first.v" to 1.0, "\$step(\"Two\").last.w" to "q", "\$step(\"Two\")[1].v" to 2.0,
        "\$step(\"Two\").map(i => i.v)" to listOf(1.0, 2.0), "\$step(\"Two\").filter(i => i.v > 1).length" to 1.0,
        "\$step(\"Two\", \"w\")" to listOf("p", "q"), "\$step(\"Nope\")" to null, "\$step(\"Nope\").x" to null,
        "\$step(\"Parse SMS\").first.text" to "debited", "\$step(\"Parse SMS\").count" to 1.0,
        "\$step(\"Counted\").count" to 5.0, "\$step(\"Empty\")" to emptyList<Any?>(), "\$step(\"Two\").x" to null,
    ))
}
