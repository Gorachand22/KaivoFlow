package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.SchemaType

// Pure model of the filter conditions builder (spec P2 2.13). Stored value shape:
// { combinator: "and"|"or", conditions: [ { id, left, leftType, op, right, right2 } | { id, combinator, conditions } ], options }
// left, right and right2 are encoded with the nested codec, so each can be an expression.

enum class OperandType(val label: String) { Text("Text"), Number("Number"), Boolean("Boolean"), Date("Date"), List("List"), Object("Object"), Any("Any") }

enum class Combinator(val key: String, val label: String) { And("and", "AND"), Or("or", "OR") }

/** [key] is the stored operator name (the set `ParamValidator.knownOperators` accepts). [args] is 0, 1 or 2 right-hand values. */
data class OperatorDef(val key: String, val label: String, val args: Int)

object ConditionOps {
    private val text = listOf(
        OperatorDef("is", "is", 1), OperatorDef("isNot", "is not", 1), OperatorDef("contains", "contains", 1),
        OperatorDef("notContains", "does not contain", 1), OperatorDef("startsWith", "starts with", 1),
        OperatorDef("endsWith", "ends with", 1), OperatorDef("matches", "matches pattern", 1),
        OperatorDef("isEmpty", "is empty", 0), OperatorDef("isNotEmpty", "is not empty", 0),
    )
    private val number = listOf(
        OperatorDef("equals", "equals", 1), OperatorDef("notEquals", "not equals", 1), OperatorDef("gt", "greater than", 1),
        OperatorDef("gte", "greater or equal", 1), OperatorDef("lt", "less than", 1), OperatorDef("lte", "less or equal", 1),
        OperatorDef("between", "between", 2),
    )
    private val boolean = listOf(OperatorDef("isTrue", "is true", 0), OperatorDef("isFalse", "is false", 0))
    private val date = listOf(
        OperatorDef("is", "is", 1), OperatorDef("before", "before", 1), OperatorDef("after", "after", 1), OperatorDef("between", "between", 2),
    )
    private val list = listOf(
        OperatorDef("contains", "contains", 1), OperatorDef("lengthEquals", "length equals", 1),
        OperatorDef("lengthGt", "length greater than", 1), OperatorDef("isEmpty", "is empty", 0),
    )
    private val obj = listOf(OperatorDef("hasKey", "has key", 1), OperatorDef("isEmpty", "is empty", 0))
    private val any = listOf(OperatorDef("exists", "exists", 0), OperatorDef("notExists", "does not exist", 0))

    fun forType(t: OperandType): List<OperatorDef> = when (t) {
        OperandType.Text -> text
        OperandType.Number -> number
        OperandType.Boolean -> boolean
        OperandType.Date -> date
        OperandType.List -> list
        OperandType.Object -> obj
        OperandType.Any -> any
    }

    fun find(t: OperandType, key: String): OperatorDef? = forType(t).firstOrNull { it.key == key }

    /** Operand type that follows a data type from the schema (Drop on the left value). */
    fun typeOf(schema: SchemaType?): OperandType = when (schema) {
        SchemaType.String -> OperandType.Text
        SchemaType.Number -> OperandType.Number
        SchemaType.Bool -> OperandType.Boolean
        SchemaType.Date -> OperandType.Date
        SchemaType.Array -> OperandType.List
        SchemaType.Object -> OperandType.Object
        SchemaType.Binary, SchemaType.Mixed, SchemaType.Null, null -> OperandType.Any
    }

    /** Can a right-hand value typed for [from] stay when the left side becomes [to]? */
    fun rightCompatible(from: OperandType, to: OperandType): Boolean =
        from == to || from == OperandType.Any || to == OperandType.Any ||
            (from == OperandType.Text && to == OperandType.Date) || (from == OperandType.Date && to == OperandType.Text)
}

sealed interface ConditionNode {
    val id: String
}

data class Rule(
    override val id: String,
    val left: ParamValue? = null,
    val leftType: OperandType = OperandType.Text,
    val op: String = "is",
    val right: ParamValue? = null,
    val right2: ParamValue? = null,
) : ConditionNode {
    val def: OperatorDef get() = ConditionOps.find(leftType, op) ?: ConditionOps.forType(leftType).first()
}

data class ConditionGroup(
    override val id: String,
    val combinator: Combinator = Combinator.And,
    val children: List<ConditionNode> = emptyList(),
) : ConditionNode

data class ConditionTree(val root: ConditionGroup, val ignoreCase: Boolean = true, val lenientTypes: Boolean = false)

/** Result of editing the left type: [clearedRight] holds what was dropped, for the undo toast. */
data class TypeChange(val rule: Rule, val clearedRight: Boolean)

object ConditionsModel {
    const val MAX_DEPTH = 3

    fun empty(): ConditionTree = ConditionTree(ConditionGroup("g0"))

    /** Next unused id for a node, `c1`, `c2`, ... */
    fun nextId(tree: ConditionTree, prefix: String = "c"): String {
        val used = HashSet<String>()
        fun walk(n: ConditionNode) { used += n.id; if (n is ConditionGroup) n.children.forEach(::walk) }
        walk(tree.root)
        var i = 1
        while ("$prefix$i" in used) i++
        return "$prefix$i"
    }

    // ---- edits; every function returns a new tree, and a path that misses leaves the tree unchanged ----

    /** [groupId] is the id of the group to add to. Refuses nothing: rules never deepen the tree. */
    fun addRule(tree: ConditionTree, groupId: String = tree.root.id, rule: Rule? = null): ConditionTree {
        val r = rule?.let { if (it.id.isBlank() || exists(tree, it.id)) it.copy(id = nextId(tree)) else it } ?: Rule(nextId(tree))
        return mapGroup(tree, groupId) { it.copy(children = it.children + r) }
    }

    /** Adds a nested group when allowed and below [MAX_DEPTH]. */
    fun addGroup(tree: ConditionTree, groupId: String = tree.root.id, allowNested: Boolean = true): ConditionTree {
        if (!allowNested || depthOf(tree, groupId) >= MAX_DEPTH) return tree
        val g = ConditionGroup(nextId(tree, "g"), Combinator.And, listOf(Rule(nextId(tree))))
        return mapGroup(tree, groupId) { it.copy(children = it.children + g) }
    }

    fun remove(tree: ConditionTree, id: String): ConditionTree {
        if (id == tree.root.id) return tree
        fun strip(g: ConditionGroup): ConditionGroup =
            g.copy(children = g.children.filter { it.id != id }.map { if (it is ConditionGroup) strip(it) else it })
        return tree.copy(root = strip(tree.root))
    }

    fun toggleCombinator(tree: ConditionTree, groupId: String): ConditionTree = mapGroup(tree, groupId) {
        it.copy(combinator = if (it.combinator == Combinator.And) Combinator.Or else Combinator.And)
    }

    fun updateRule(tree: ConditionTree, id: String, f: (Rule) -> Rule): ConditionTree {
        fun walk(g: ConditionGroup): ConditionGroup = g.copy(children = g.children.map {
            when {
                it is Rule && it.id == id -> f(it)
                it is ConditionGroup -> walk(it)
                else -> it
            }
        })
        return tree.copy(root = walk(tree.root))
    }

    fun find(tree: ConditionTree, id: String): ConditionNode? {
        fun walk(n: ConditionNode): ConditionNode? =
            if (n.id == id) n else (n as? ConditionGroup)?.children?.firstNotNullOfOrNull { walk(it) }
        return walk(tree.root)
    }

    fun exists(tree: ConditionTree, id: String) = find(tree, id) != null

    /** Depth of a group: the root is 1. Returns 0 for an unknown id. */
    fun depthOf(tree: ConditionTree, groupId: String): Int {
        fun walk(g: ConditionGroup, d: Int): Int =
            if (g.id == groupId) d else g.children.filterIsInstance<ConditionGroup>().maxOfOrNull { walk(it, d + 1) } ?: 0
        return walk(tree.root, 1)
    }

    fun ruleCount(tree: ConditionTree): Int {
        fun walk(g: ConditionGroup): Int = g.children.sumOf { if (it is ConditionGroup) walk(it) else 1 }
        return walk(tree.root)
    }

    /**
     * Changing the left type: the operator stays when the new type has it, else the first operator
     * of the new type; the right values stay when compatible, else they are cleared.
     */
    fun changeType(rule: Rule, to: OperandType): TypeChange {
        if (rule.leftType == to) return TypeChange(rule, false)
        val op = ConditionOps.find(to, rule.op) ?: ConditionOps.forType(to).first()
        val compatible = ConditionOps.rightCompatible(rule.leftType, to)
        val hadRight = rule.right != null || rule.right2 != null
        val keepRight = compatible && op.args > 0
        val next = rule.copy(
            leftType = to,
            op = op.key,
            right = if (keepRight) rule.right else null,
            right2 = if (keepRight && op.args == 2) rule.right2 else null,
        )
        return TypeChange(next, clearedRight = hadRight && !compatible)
    }

    fun changeOperator(rule: Rule, op: String): Rule {
        val def = ConditionOps.find(rule.leftType, op) ?: return rule
        return rule.copy(op = def.key, right = if (def.args == 0) null else rule.right, right2 = if (def.args < 2) null else rule.right2)
    }

    /** A drop on the left value of [id]: fills the left value; the operator group follows the schema type. */
    fun dropOnLeft(tree: ConditionTree, id: String, reference: String, type: SchemaType?): ConditionTree =
        updateRule(tree, id) { r ->
            val t = if (type == null) r.leftType else ConditionOps.typeOf(type)
            val base = if (t != r.leftType) changeType(r, t).rule else r
            base.copy(left = ParamValue.Expr(reference))
        }

    /** A drop on the card background of the list: a new rule with the left value filled. */
    fun dropAsNewRule(tree: ConditionTree, groupId: String, reference: String, type: SchemaType?): ConditionTree {
        val t = ConditionOps.typeOf(type).let { if (it == OperandType.Any) OperandType.Text else it }
        val rule = Rule(nextId(tree), left = ParamValue.Expr(reference), leftType = t, op = ConditionOps.forType(t).first().key)
        return addRule(tree, groupId, rule)
    }

    private fun mapGroup(tree: ConditionTree, groupId: String, f: (ConditionGroup) -> ConditionGroup): ConditionTree {
        fun walk(g: ConditionGroup): ConditionGroup =
            if (g.id == groupId) f(g) else g.copy(children = g.children.map { if (it is ConditionGroup) walk(it) else it })
        return tree.copy(root = walk(tree.root))
    }

    // ---- summary for the canvas card and the accessibility label ----

    fun summary(tree: ConditionTree): String {
        fun text(v: ParamValue?): String = when (v) {
            null -> "..."
            is ParamValue.Expr -> v.text.trim()
            is ParamValue.Fixed -> v.value.toString()
        }
        fun rule(r: Rule): String {
            val d = r.def
            return when (d.args) {
                0 -> "${text(r.left)} ${d.label}"
                2 -> "${text(r.left)} ${d.label} ${text(r.right)} and ${text(r.right2)}"
                else -> "${text(r.left)} ${d.label} ${text(r.right)}"
            }
        }
        fun group(g: ConditionGroup, nested: Boolean): String {
            val parts = g.children.map { if (it is ConditionGroup) group(it, true) else rule(it as Rule) }
            val joined = parts.joinToString(" ${g.combinator.label} ")
            return if (nested && parts.size > 1) "($joined)" else joined
        }
        return group(tree.root, false).ifEmpty { "No conditions" }
    }

    // ---- stored form ----

    fun decode(v: ParamValue?): ConditionTree {
        val m = ParamTree.asMap(v)
        if (m.isEmpty()) return empty()
        val used = HashSet<String>()
        var counter = 0
        fun id(raw: Any?, prefix: String): String {
            val s = raw?.toString().orEmpty()
            if (s.isNotBlank() && used.add(s)) return s
            var n: String
            do { counter++; n = "$prefix$counter" } while (!used.add(n))
            return n
        }
        fun group(map: Map<*, *>, gid: String, depth: Int): ConditionGroup {
            val comb = if (map["combinator"]?.toString()?.lowercase() == "or") Combinator.Or else Combinator.And
            val kids = (map["conditions"] as? List<*>).orEmpty().mapNotNull { raw ->
                val c = raw as? Map<*, *> ?: return@mapNotNull null
                if (c.containsKey("conditions") && depth < MAX_DEPTH) group(c, id(c["id"], "g"), depth + 1)
                else if (c.containsKey("conditions")) null
                else {
                    val type = OperandType.entries.firstOrNull { it.name.equals(c["leftType"]?.toString(), true) } ?: OperandType.Text
                    Rule(
                        id = id(c["id"], "c"),
                        left = ParamValueCodec.decodeLenient(c["left"]),
                        leftType = type,
                        op = c["op"]?.toString() ?: ConditionOps.forType(type).first().key,
                        right = ParamValueCodec.decodeLenient(c["right"]),
                        right2 = ParamValueCodec.decodeLenient(c["right2"]),
                    )
                }
            }
            return ConditionGroup(gid, comb, kids)
        }
        val opts = m["options"] as? Map<*, *>
        val rootId = id(m["id"], "g")
        return ConditionTree(
            root = group(m, rootId, 1),
            ignoreCase = opts?.get("ignoreCase") as? Boolean ?: true,
            lenientTypes = opts?.get("lenientTypes") as? Boolean ?: false,
        )
    }

    fun encode(tree: ConditionTree): ParamValue? {
        if (tree.root.children.isEmpty()) return null
        fun group(g: ConditionGroup, isRoot: Boolean): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            if (!isRoot) out["id"] = g.id
            out["combinator"] = g.combinator.key
            out["conditions"] = g.children.map { n ->
                when (n) {
                    is ConditionGroup -> group(n, false)
                    is Rule -> linkedMapOf<String, Any?>().also { m ->
                        m["id"] = n.id
                        m["left"] = ParamValueCodec.encode(n.left)
                        m["leftType"] = n.leftType.name.lowercase()
                        m["op"] = n.op
                        if (n.def.args >= 1) m["right"] = ParamValueCodec.encode(n.right)
                        if (n.def.args == 2) m["right2"] = ParamValueCodec.encode(n.right2)
                    }
                }
            }
            return out
        }
        val root = group(tree.root, true).toMutableMap()
        root["options"] = mapOf("ignoreCase" to tree.ignoreCase, "lenientTypes" to tree.lenientTypes)
        return ParamValue.Fixed(root)
    }
}
