package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorsTest {
    @Test fun literals() = checkCases(listOf(
        "42" to 42.0, "1.5" to 1.5, "1e3" to 1000.0, "2.5E-1" to 0.25,
        "\"s\"" to "s", "'s'" to "s", "\"a\\nb\"" to "a\nb", "\"\\u0041\"" to "A", "'it\\'s'" to "it's",
        "true" to true, "false" to false, "null" to null, "undefined" to null,
        "[1, 2, 3]" to listOf(1.0, 2.0, 3.0), "[]" to emptyList<Any?>(), "[1, [2, 3],]" to listOf(1.0, listOf(2.0, 3.0)),
        "{a: 1, \"b c\": 2}" to mapOf("a" to 1.0, "b c" to 2.0), "{}" to emptyMap<String, Any?>(),
        "{[\"k\" + 1]: true}" to mapOf("k1" to true), "{a: 1}.a" to 1.0, "[10, 20][1]" to 20.0,
        "`plain`" to "plain", "`a\${1 + 1}b`" to "a2b", "`x\${'}'}y`" to "x}y", "`\${\$json.merchant}!`" to "Cafe!",
    ))

    @Test fun arithmetic() = checkCases(listOf(
        "1 + 2" to 3.0, "5 - 8" to -3.0, "4 * 2.5" to 10.0, "9 / 4" to 2.25, "9 % 4" to 1.0, "2 ** 10" to 1024.0,
        "-5" to -5.0, "+\"5\"" to 5.0, "--3" to 3.0, "-(2 + 3)" to -5.0,
        "2 + 3 * 4" to 14.0, "(2 + 3) * 4" to 20.0, "-2 ** 2" to -4.0, "2 ** 3 ** 2" to 512.0, "10 - 2 - 3" to 5.0, "100 / 10 / 5" to 2.0,
        "\"5\" * 2" to 10.0, "\"10\" - 4" to 6.0, "\$json.qty * \$json.price" to 3 * 19.99,
        "\$json.amount + 1" to 1251.5, "\$vars.n / 4" to 2.5, "7.5 % 2" to 1.5,
    ))

    @Test fun comparison() = checkCases(listOf(
        "1 < 2" to true, "2 < 1" to false, "2 <= 2" to true, "3 > 2" to true, "2 >= 3" to false,
        "1 == 1" to true, "1 == 1.0" to true, "\"a\" == \"a\"" to true, "\"1\" == 1" to false, "1 != 2" to true,
        "1 === 1" to true, "1 !== 2" to true, "null == null" to true, "null == 0" to false,
        "\"a\" < \"b\"" to true, "\"b\" < \"a\"" to false, "\"10\" > 9" to true,
        "[1, 2] == [1, 2]" to true, "{a: 1} == {a: 1}" to true, "[1] == [2]" to false,
        "\$json.nil < 1" to false, "\$json.missing >= 0" to false,
    ))

    @Test fun logical() = checkCases(listOf(
        "true && false" to false, "true || false" to true, "!true" to false, "!0" to true, "!\"x\"" to false,
        "1 && 2" to 2.0, "0 && 2" to 0.0, "null || \"d\"" to "d", "\"a\" || \"b\"" to "a", "!!\"\"" to false, "![]" to false,
        "false || false || 3" to 3.0, "true && true && \"ok\"" to "ok",
        "true || (1 / 0)" to true, "false && (1 / 0)" to false,
    ))

    @Test fun ternaryAndNullish() = checkCases(listOf(
        "true ? 1 : 2" to 1.0, "false ? 1 : 2" to 2.0, "\$json.flag ? \"y\" : \"n\"" to "y",
        "1 > 2 ? \"a\" : 2 > 1 ? \"b\" : \"c\"" to "b", "true ? false ? 1 : 2 : 3" to 2.0,
        "null ?? 5" to 5.0, "0 ?? 5" to 0.0, "\"\" ?? \"x\"" to "", "\$json.missing ?? \"fallback\"" to "fallback",
        "\$json.nil ?? \$json.missing ?? 7" to 7.0, "\$json.merchant ?? \"none\"" to "Cafe",
    ))

    @Test fun stringConcatenation() = checkCases(listOf(
        "\"a\" + \"b\"" to "ab", "\"a\" + 1" to "a1", "1 + \"a\"" to "1a", "\"n=\" + 1.5" to "n=1.5", "\"t:\" + true" to "t:true",
        "\"x\" + null" to "x", "null + \"x\"" to "x", "\$json.merchant + \"!\"" to "Cafe!", "\"a\" + \"b\" + \"c\" + 1 + 2" to "abc12",
        "1 + 2 + \"x\"" to "3x", "\"Total: \" + \$json.qty * 2" to "Total: 6",
    ))

    @Test fun nullPropagatesInSafeMode() {
        assertNull(ev("\$json.nil + 1"))
        assertNull(ev("1 - \$json.missing"))
        assertNull(ev("-\$json.nil"))
    }

    @Test fun strictModeRejectsNullArithmetic() {
        assertKind(ErrorKind.Type, failure("\$json.nil + 1", strict = true))
        assertKind(ErrorKind.Type, failure("-\$json.nil", strict = true))
        assertKind(ErrorKind.Type, failure("\$json.nil < 1", strict = true))
    }

    @Test fun divisionByZeroAndNonFinite() {
        assertTrue(failure("1 / 0").message!!.contains("Division by zero"))
        assertTrue(failure("5 % 0").message!!.contains("Division by zero"))
        assertKind(ErrorKind.Runtime, failure("10 ** 1000"))
    }

    @Test fun typeErrorsNameTheOperands() {
        assertTrue(failure("\"abc\" * 2").message!!.contains("'abc'"))
        assertKind(ErrorKind.Type, failure("[1] - 1"))
        assertKind(ErrorKind.Type, failure("\"a\" + [1]"))
        assertKind(ErrorKind.Type, failure("[1] < 2"))
    }

    @Test fun integerInputsFromScopeStayUsable() {
        assertEquals(6.0, ev("\$items[0].id + \$items[1].id + 3"))
        assertEquals(true, ev("\$json.qty == 3"))
    }
}
