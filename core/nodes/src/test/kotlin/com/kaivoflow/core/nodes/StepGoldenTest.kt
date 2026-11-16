package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.NodeSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Characterisation test (taxonomy spec 5.6). Records, for every registered executor, its id,
 * legacy category, new category path, kind, output ports and param keys. A move or recategorisation
 * must keep this green, or edit the golden file in the same change on purpose.
 *
 * To regenerate: run with environment variable KAIVO_UPDATE_GOLDEN set to the output file path.
 */
class StepGoldenTest {

    @Test
    fun `registry matches the golden snapshot`() {
        val actual = snapshot()
        System.getenv("KAIVO_UPDATE_GOLDEN")?.takeIf { it.isNotBlank() }?.let {
            File(it).writeText(actual)
        }
        val resource = StepGoldenTest::class.java.getResourceAsStream("/step-golden.txt")
        assertNotNull("missing test resource step-golden.txt", resource)
        val expected = resource!!.bufferedReader().use { it.readText() }
        assertEquals(expected, actual)
    }

    companion object {
        fun line(spec: NodeSpec): String = listOf(
            spec.id,
            "legacy=${spec.category.name}",
            "path=${spec.categoryPath}",
            "alsoIn=${spec.alsoIn.joinToString(",")}",
            "lifecycle=${spec.lifecycle.name}",
            "role=${spec.role.name}",
            "kind=${spec.kind.name}",
            "outputs=${spec.outputs}[${spec.outputLabels.joinToString(",")}]",
            "params=${spec.params.joinToString(",") { it.key }}",
        ).joinToString(" | ")

        fun snapshot(): String =
            NodeCatalog.executors().map { it.spec }.sortedBy { it.id }.joinToString("\n", postfix = "\n") { line(it) }
    }
}
