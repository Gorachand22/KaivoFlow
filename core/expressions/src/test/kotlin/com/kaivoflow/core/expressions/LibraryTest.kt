package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test fun stringHelpers() = checkCases(listOf(
        "\$json.name.trim()" to "Ada Lovelace", "\$json.name.trimStart()" to "Ada Lovelace ", "\$json.name.trimEnd()" to "  Ada Lovelace",
        "\$json.merchant.upper()" to "CAFE", "\$json.merchant.lower()" to "cafe", "\"ab\".toUpperCase()" to "AB", "\"AB\".toLowerCase()" to "ab",
        "\"ada\".capitalize()" to "Ada", "\"a-b-c\".replace(\"-\", \"+\")" to "a+b+c", "\"a-b-c\".replaceAll(\"-\", \"\")" to "abc",
        "\"a-b-c\".replaceFirst(\"-\", \"+\")" to "a+b-c", "\"abc\".replace(\"\", \"x\")" to "abc",
        "\"a,b,c\".split(\",\")" to listOf("a", "b", "c"), "\"a,b,c\".split(\",\", 2)" to listOf("a", "b"), "\"abc\".split(\"\")" to listOf("a", "b", "c"),
        "\"hello\".includes(\"ell\")" to true, "\"hello\".includes(\"z\")" to false, "\"hello\".startsWith(\"he\")" to true, "\"hello\".endsWith(\"lo\")" to true,
        "\"hello\".slice(1, 3)" to "el", "\"hello\".slice(-3)" to "llo", "\"hello\".slice(2)" to "llo", "\"hello\".slice(3, 1)" to "",
        "\"hello\".substring(1, 3)" to "el", "\"hello\".substring(3, 1)" to "el",
        "\"7\".padStart(3, \"0\")" to "007", "\"7\".padEnd(3, \"0\")" to "700", "\"abc\".padStart(2)" to "abc", "\"x\".padStart(4)" to "   x",
        "\"abc\".length" to 3.0, "\"abc\".length()" to 3.0, "\"abcabc\".indexOf(\"c\")" to 2.0, "\"abcabc\".lastIndexOf(\"c\")" to 5.0, "\"abc\".indexOf(\"z\")" to -1.0,
        "\"ab\".repeat(3)" to "ababab", "\"abc\".charAt(1)" to "b", "\"abc\".at(-1)" to "c", "\"a\".concat(\"b\", 1)" to "ab1", "\"abc\".reverse()" to "cba",
        "\"\".isEmpty()" to true, "\" \".isEmpty()" to false, "\"12.5\".toNumber()" to 12.5, "\"x\".toNumber()" to null, "\"-7.9\".toInt()" to -7.0,
        "\"yes\".toBoolean()" to true, "\"no\".toBoolean()" to false,
        "upper(\$json.merchant)" to "CAFE", "trim(\$json.name)" to "Ada Lovelace", "replace(\"aXb\", \"X\", \"-\")" to "a-b",
        "\"Total: \" + \$json.amount.toFixed(2)" to "Total: 1250.50",
    ))

    @Test fun numberAndMathHelpers() = checkCases(listOf(
        "round(2.567, 2)" to 2.57, "round(2.5)" to 3.0, "round(-2.5)" to -3.0, "round(1234.5, -2)" to 1200.0, "(1.005).round(2)" to 1.01,
        "floor(2.9)" to 2.0, "floor(-2.1)" to -3.0, "ceil(2.1)" to 3.0, "ceil(-2.9)" to -2.0, "abs(-4)" to 4.0, "(-4).abs()" to 4.0,
        "min(3, 1, 2)" to 1.0, "max(3, 1, 2)" to 3.0, "min([5, 2, 9])" to 2.0, "max([5, 2, 9])" to 9.0, "Math.max(1, 7)" to 7.0, "Math.min(\$json.nums)" to 1.0,
        "(3.14159).toFixed(2)" to "3.14", "(2).toFixed(2)" to "2.00", "(0.5).toFixed(0)" to "1", "\$json.price.toFixed(1)" to "20.0",
        "Number.parse(\"12.5\")" to 12.5, "Number.parse(\"abc\")" to null, "Number(\"7\")" to 7.0, "Number(true)" to 1.0, "Number.isNumber(1)" to true, "Number.isNumber(\"1\")" to false,
        "Math.pow(2, 8)" to 256.0, "Math.sqrt(81)" to 9.0, "Math.floor(2.7)" to 2.0, "Math.round(2.45, 1)" to 2.5, "Math.abs(-1)" to 1.0, "Math.trunc(-2.7)" to -2.0,
        "Math.clamp(15, 0, 10)" to 10.0, "(5).clamp(1, 3)" to 3.0, "Math.PI" to Math.PI, "(9).sqrt()" to 3.0, "(2).pow(3)" to 8.0, "(-3).sign()" to -1.0, "(2.9).toInt()" to 2.0, "(4).isInteger()" to true,
        "round(\$json.missing)" to null, "Math.floor(\$json.nil)" to null,
    ))

    @Test fun arrayHelpers() = checkCases(listOf(
        "\$json.nums.map(x => x * 2)" to listOf(6.0, 2.0, 4.0), "\$json.nums.map((x, i) => x + i)" to listOf(3.0, 2.0, 4.0),
        "\$json.nums.filter(x => x > 1)" to listOf(3.0, 2.0), "\$json.rows.find(r => r.v == 2).n" to "b", "\$json.rows.find(r => r.v == 9)" to null,
        "\$json.rows.findIndex(r => r.n == \"c\")" to 2.0, "\$json.nums.some(x => x > 2)" to true, "\$json.nums.some(x => x > 5)" to false,
        "\$json.nums.every(x => x > 0)" to true, "\$json.nums.every(x => x > 1)" to false,
        "\$json.nums.reduce((a, b) => a + b, 0)" to 6.0, "\$json.nums.reduce((a, b) => a + b)" to 6.0, "[].reduce((a, b) => a + b)" to null, "\$json.words.reduce((acc, w) => acc + w.length, 0)" to 12.0,
        "\$json.tags.join(\"-\")" to "x-y-x", "\$json.tags.join()" to "x,y,x", "[1, null, 2].join(\"|\")" to "1||2",
        "\$json.tags.first()" to "x", "\$json.nums.last()" to 2.0, "[].first()" to null, "first(\$json.words)" to "pear", "last(\$json.words)" to "fig",
        "\$json.tags.unique()" to listOf("x", "y"), "\$json.dupes.unique()" to listOf(1, 2, "1"), "\$json.rows.unique(r => r.v > 1).length" to 2.0,
        "\$json.nums.sort()" to listOf(1.0, 2.0, 3.0), "\$json.nums.sort(\"desc\")" to listOf(3.0, 2.0, 1.0), "\$json.words.sort()" to listOf("apple", "fig", "pear"),
        "\$json.words.sort(w => w.length)" to listOf("fig", "pear", "apple"), "\$json.words.sort(w => w.length, \"desc\")" to listOf("apple", "pear", "fig"),
        "\$json.nums.sort((a, b) => b - a)" to listOf(3.0, 2.0, 1.0), "\$json.mixed.sort()" to listOf(1.0, 2.0, "10"), "[3, null, 1].sort()" to listOf(1.0, 3.0, null),
        "\$json.nums.slice(1)" to listOf(1.0, 2.0), "\$json.nums.slice(0, 2)" to listOf(3.0, 1.0), "\$json.nums.slice(-1)" to listOf(2.0), "\$json.nums.take(2)" to listOf(3.0, 1.0),
        "\$json.nested.flat()" to listOf(1.0, 2.0, 3.0, listOf(4.0)), "\$json.nested.flat(2)" to listOf(1.0, 2.0, 3.0, 4.0), "\$json.nested.flat(0).length" to 2.0,
        "\$json.nums.sum()" to 6.0, "sum(\$json.nums)" to 6.0, "[\"1\", 2, null].sum()" to 3.0, "\$json.nums.avg()" to 2.0, "[].avg()" to null,
        "\$json.tags.length" to 3.0, "length(\$json.tags)" to 3.0, "\$json.tags.length()" to 3.0, "\$json.tags.includes(\"y\")" to true, "\$json.tags.includes(\"q\")" to false,
        "\$json.tags.indexOf(\"y\")" to 1.0, "[1, 2].concat([3], 4)" to listOf(1.0, 2.0, 3.0, 4.0), "[1].append(2, 3)" to listOf(1.0, 2.0, 3.0),
        "\$json.gaps.compact()" to listOf("a", "b"), "\$json.rows.pluck(\"n\")" to listOf("a", "b", "c"), "\$json.rows.count(r => r.v >= 2)" to 2.0, "\$json.rows.count()" to 3.0,
        "\$json.nested.flatMap(x => x)" to listOf(1.0, 2.0, 3.0, listOf(4.0)), "\$json.nums.reverse()" to listOf(2.0, 1.0, 3.0), "[].isEmpty()" to true,
        "\$json.rows.map(r => r.v * 10).filter(v => v > 10).sum()" to 50.0, "\$json.nums.min()" to 1.0, "\$json.nums.max()" to 3.0, "\$json.nums.at(-1)" to 2.0,
        "Array.range(3)" to listOf(0.0, 1.0, 2.0), "Array.range(1, 7, 2)" to listOf(1.0, 3.0, 5.0), "range(2).map(i => i * i)" to listOf(0.0, 1.0), "Array.isArray([1])" to true, "Array.isArray(\"a\")" to false,
        "\$json.missing.map(x => x)" to null, "[1, 2].map(x => [x, x * 2])" to listOf(listOf(1.0, 2.0), listOf(2.0, 4.0)), "[1,2,3].map(x => ((y) => y + x)(10))" to listOf(11.0, 12.0, 13.0),
    ))

    @Test fun objectHelpers() = checkCases(listOf(
        "keys(\$json.obj)" to listOf("x", "y"), "values(\$json.obj)" to listOf(1.0, 2.0), "entries(\$json.obj)" to listOf(listOf("x", 1.0), listOf("y", 2.0)),
        "\$json.obj.keys()" to listOf("x", "y"), "hasKey(\$json.obj, \"x\")" to true, "hasKey(\$json.obj, \"z\")" to false, "\$json.obj.hasKey(\"y\")" to true,
        "merge({a: 1}, {b: 2})" to mapOf("a" to 1.0, "b" to 2.0), "merge({a: 1}, {a: 2})" to mapOf("a" to 2.0), "\$json.obj.merge({z: 3}).z" to 3.0, "Object.merge({a: 1}, null, {b: 2})" to mapOf("a" to 1.0, "b" to 2.0),
        "\$json.obj.pick(\"x\")" to mapOf("x" to 1.0), "\$json.obj.omit(\"x\")" to mapOf("y" to 2.0), "\$json.obj.pick([\"x\", \"y\"])" to mapOf("x" to 1.0, "y" to 2.0),
        "\$json.obj.get(\"x\")" to 1.0, "\$json.a.get(\"b.c\")" to 42.0, "\$json.obj.get(\"zz\", \"dflt\")" to "dflt", "\$json.obj.isEmpty()" to false, "{}.isEmpty()" to true, "\$json.obj.length()" to 2.0,
        "Object.keys(\$json.obj)" to listOf("x", "y"), "Object.values(\$json.obj)" to listOf(1.0, 2.0), "Object.entries({a: 1})" to listOf(listOf("a", 1.0)), "Object.hasKey({a: 1}, \"a\")" to true, "Object.keys(null)" to emptyList<Any?>(),
        "keys(\$json.missing)" to emptyList<Any?>(), "length(\$json.obj)" to 2.0,
    ))

    @Test fun jsonHelpers() = checkCases(listOf(
        "JSON.parse(\$json.jsonText).a[2].b" to null, "JSON.parse(\$json.jsonText).a[1]" to 2.0, "JSON.parse(\"[1, \\\"x\\\", true, null]\")" to listOf(1.0, "x", true, null),
        "JSON.parse(\"{\\\"k\\\": {\\\"z\\\": -1.5e1}}\").k.z" to -15.0, "JSON.parse(\"\\\"s\\\"\")" to "s", "JSON.parse(\$json.missing)" to null,
        "JSON.stringify({a: [1, \"x\"]})" to "{\"a\":[1,\"x\"]}", "JSON.stringify(\"q\\\"\")" to "\"q\\\"\"", "JSON.stringify(null)" to "null", "JSON.stringify([1, 2.5])" to "[1,2.5]",
        "JSON.stringify({a: 1}, true).includes(\"\\n\")" to true, "{a: 1}.toJson()" to "{\"a\":1}", "[1, 2].toString()" to "[1,2]", "JSON.parse(JSON.stringify(\$json.obj)).y" to 2.0,
    ))

    @Test fun invalidJsonGivesRuntimeError() {
        val e = failure("JSON.parse(\"{bad\")")
        assertKind(ErrorKind.Runtime, e)
        assertTrue(e.message!!.contains("invalid JSON"))
    }

    @Test fun coercionHelpers() = checkCases(listOf(
        "toNumber(\"5\")" to 5.0, "toNumber(\"x\")" to null, "toNumber(true)" to 1.0, "toNumber(null)" to null, "toInt(\"7.9\")" to 7.0, "toInt(-7.9)" to -7.0,
        "toString(5)" to "5", "toString(1.5)" to "1.5", "toString(null)" to "", "toString([1, 2])" to "[1,2]", "toString({a: 1})" to "{\"a\":1}", "String(12)" to "12", "String(true)" to "true",
        "toBoolean(\"true\")" to true, "toBoolean(0)" to false, "toBoolean(\"\")" to false, "Boolean(\"x\")" to true, "Boolean(0)" to false, "Boolean([])" to true,
        "typeOf(1)" to "number", "typeOf(\"a\")" to "string", "typeOf(null)" to "null", "typeOf([])" to "array", "typeOf({})" to "object", "typeOf(true)" to "boolean", "typeOf(now())" to "date",
        "isNull(null)" to true, "isNull(0)" to false, "isEmpty(\"\")" to true, "isEmpty([])" to true, "isEmpty(null)" to true, "isEmpty(0)" to false, "isEmpty({a: 1})" to false,
        "coalesce(null, null, 3)" to 3.0, "coalesce(null)" to null, "length(null)" to 0.0, "length(\"abc\")" to 3.0, "length(5)" to 1.0,
        "\$json.numStr + 1" to "421", "\$json.numStr * 1" to 42.0, "(\$json.numStr).toNumber() + 1" to 43.0,
    ))

    @Test fun methodOnNullIsNullInSafeMode() {
        assertNull(ev("\$json.nil.upper()"))
        assertNull(ev("upper(\$json.nil)"))
        assertNull(ev("\$json.missing.map(x => x)"))
    }

    @Test fun wrongArgumentsAreTypeErrors() {
        assertKind(ErrorKind.Type, failure("\"a\".repeat()"))
        assertKind(ErrorKind.Type, failure("[1].map(5)"))
        assertKind(ErrorKind.Type, failure("\"a\".padStart(\"x\")"))
        assertTrue(failure("\"a\".trim(1)").message!!.contains("0 argument"))
        assertKind(ErrorKind.Type, failure("[1, \"x\"].sum()"))
        assertKind(ErrorKind.Type, failure("[1].sort(1)"))
        assertKind(ErrorKind.Unknown, failure("{a: 1}.map(x => x)"))
    }

    @Test fun libraryCatalogForAutocomplete() {
        val lib = KaivoExpressionEvaluator.library()
        assertTrue(lib.any { it.name == "trim" && it.receiver == "text" })
        assertTrue(lib.any { it.name == "round" && it.receiver == "Math" })
        assertTrue(lib.any { it.name == "now" && it.receiver == "" })
        assertTrue(lib.size > 120)
    }
}
