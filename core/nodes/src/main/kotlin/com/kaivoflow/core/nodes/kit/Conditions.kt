package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.Values
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * Condition engine shared by If, Filter, Switch and Ai Classify.
 *
 * Conditions are plain data (so they round-trip through Room and the inspector):
 * `{ "left": "{{ ${'$'}json.amount }}", "operator": "greater", "right": 500 }`
 */
enum class Operator(val label: String, val argCount: Int) {
    Equals("is equal to", 1),
    NotEquals("is not equal to", 1),
    Contains("contains", 1),
    NotContains("does not contain", 1),
    StartsWith("starts with", 1),
    EndsWith("ends with", 1),
    Greater("is greater than", 1),
    GreaterOrEqual("is greater than or equal to", 1),
    Less("is less than", 1),
    LessOrEqual("is less than or equal to", 1),
    Between("is between", 2),
    Matches("matches pattern", 1),
    IsEmpty("is empty", 0),
    IsNotEmpty("is not empty", 0),
    IsTrue("is true", 0),
    IsFalse("is false", 0),
    Exists("exists", 0),
    NotExists("does not exist", 0),
    In("is one of", 1),
    NotIn("is not one of", 1),
    ;

    companion object {
        fun fromKey(key: String): Operator = entries.firstOrNull { it.name.equals(key, true) } ?: Equals
    }
}

data class Condition(
    val left: String = "",
    val operator: Operator = Operator.Equals,
    val right: Any? = null,
    val right2: Any? = null,
    val ignoreCase: Boolean = true,
) {
    fun evaluate(itemJson: Map<String, Any?>, resolve: (String, Map<String, Any?>) -> Any?): Boolean {
        val leftValue = if (left.contains("{{")) resolve(left, itemJson) else Values.deepGet(itemJson, left.trim().removePrefix("${'$'}json."))
        val rightValue = right
        return compare(leftValue, operator, rightValue, right2, ignoreCase)
    }

    companion object {
        fun compare(
            leftValue: Any?,
            operator: Operator,
            rightValue: Any?,
            right2: Any? = null,
            ignoreCase: Boolean = true,
        ): Boolean {
            fun text(v: Any?): String = Values.asString(v).let { if (ignoreCase) it.lowercase() else it }
            val leftNum = Values.asDouble(leftValue)
            val rightNum = Values.asDouble(rightValue)

            return when (operator) {
                Operator.Equals -> when {
                    leftNum != null && rightNum != null -> leftNum == rightNum
                    leftValue is Boolean || rightValue is Boolean -> Values.asBoolean(leftValue) == Values.asBoolean(rightValue)
                    else -> text(leftValue) == text(rightValue)
                }
                Operator.NotEquals -> !compare(leftValue, Operator.Equals, rightValue, right2, ignoreCase)
                Operator.Contains -> text(leftValue).contains(text(rightValue))
                Operator.NotContains -> !text(leftValue).contains(text(rightValue))
                Operator.StartsWith -> text(leftValue).startsWith(text(rightValue))
                Operator.EndsWith -> text(leftValue).endsWith(text(rightValue))
                Operator.Greater -> leftNum != null && rightNum != null && leftNum > rightNum
                Operator.GreaterOrEqual -> leftNum != null && rightNum != null && leftNum >= rightNum
                Operator.Less -> leftNum != null && rightNum != null && leftNum < rightNum
                Operator.LessOrEqual -> leftNum != null && rightNum != null && leftNum <= rightNum
                Operator.Between -> {
                    val lo = leftNum?.let { rightNum } ?: return false
                    val hi = Values.asDouble(right2) ?: return false
                    val value = Values.asDouble(leftValue) ?: return false
                    value in minOf(lo, hi)..maxOf(lo, hi)
                }
                Operator.Matches -> runCatching {
                    Regex(Values.asString(rightValue), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                        .containsMatchIn(Values.asString(leftValue))
                }.getOrDefault(false)
                Operator.IsEmpty -> Values.isBlank(leftValue)
                Operator.IsNotEmpty -> !Values.isBlank(leftValue)
                Operator.IsTrue -> Values.asBoolean(leftValue)
                Operator.IsFalse -> !Values.asBoolean(leftValue)
                Operator.Exists -> leftValue != null
                Operator.NotExists -> leftValue == null
                Operator.In -> Values.asList(rightValue).any { text(it) == text(leftValue) }
                Operator.NotIn -> Values.asList(rightValue).none { text(it) == text(leftValue) }
            }
        }
    }
}

data class ConditionGroup(
    val combinator: Combinator = Combinator.All,
    val conditions: ImmutableList<Condition> = persistentListOf(),
) {
    enum class Combinator { All, Any }

    fun matches(itemJson: Map<String, Any?>, resolve: (String, Map<String, Any?>) -> Any?): Boolean {
        if (conditions.isEmpty()) return true
        return when (combinator) {
            Combinator.All -> conditions.all { it.evaluate(itemJson, resolve) }
            Combinator.Any -> conditions.any { it.evaluate(itemJson, resolve) }
        }
    }

    companion object {
        /** Reads the inspector's condition builder state out of Step params. */
        fun fromParams(params: Map<String, Any?>): ConditionGroup {
            val combinator = when (Values.asString(params["combinator"]).lowercase()) {
                "any", "or" -> Combinator.Any
                else -> Combinator.All
            }
            val raw = params["conditions"]
            val conditions = Values.asList(raw).map { entry ->
                val map = Values.asMap(entry)
                Condition(
                    left = Values.asString(map["left"]),
                    operator = Operator.fromKey(Values.asString(map["operator"])),
                    right = map["right"],
                    right2 = map["right2"],
                    ignoreCase = map["ignoreCase"]?.let { Values.asBoolean(it) } ?: true,
                )
            }
            return ConditionGroup(combinator, conditions.toImmutableList())
        }
    }
}
