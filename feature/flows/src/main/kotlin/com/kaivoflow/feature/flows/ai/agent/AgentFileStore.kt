package com.kaivoflow.feature.flows.ai.agent

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Agent files on disk, one `<id>.json` each. Plain `java.io`, no Android, so it is tested on the JVM.
 *
 * - Writes go to `<id>.json.tmp`, are synced, then renamed over the target: a crash leaves the old file or the new one, never half.
 * - A file that cannot be read or decoded is moved to `quarantine/` (never deleted, never a crash) and reported, so the person
 *   can still recover the text and one bad file does not hide the other agents.
 * - Leftover `.tmp` files from a crash are removed on load.
 *
 * Not thread safe by itself: [FileAgentRepository] serialises every call behind one mutex.
 */
class AgentFileStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    data class LoadReport(val agents: List<CustomAgent>, val quarantined: List<String>, val hadAnyFile: Boolean)

    private val quarantineDir get() = File(dir, "quarantine")

    fun load(): LoadReport {
        dir.mkdirs()
        val files = dir.listFiles().orEmpty()
        files.filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
        val json = files.filter { it.isFile && it.name.endsWith(".json") }
        val agents = ArrayList<CustomAgent>()
        val bad = ArrayList<String>()
        for (f in json) {
            val decoded = try {
                AgentJsonCodec.decode(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                AgentJsonCodec.Decoded.Bad(e.javaClass.simpleName)
            }
            when {
                decoded is AgentJsonCodec.Decoded.Ok && "${decoded.agent.id}.json" == f.name -> agents += decoded.agent
                decoded is AgentJsonCodec.Decoded.Ok -> { quarantine(f); bad += "${f.name}: the id inside does not match the file name" }
                decoded is AgentJsonCodec.Decoded.Bad -> { quarantine(f); bad += "${f.name}: ${decoded.reason}" }
            }
        }
        return LoadReport(agents.sortedByDescending { it.updatedAt }, bad, json.isNotEmpty())
    }

    fun read(id: String): CustomAgent? {
        if (!AgentJsonCodec.ID_OK.matches(id)) return null
        val f = File(dir, "$id.json")
        if (!f.isFile) return null
        return try {
            (AgentJsonCodec.decode(f.readText(Charsets.UTF_8)) as? AgentJsonCodec.Decoded.Ok)?.agent
        } catch (e: Exception) {
            null
        }
    }

    fun write(agent: CustomAgent) {
        require(AgentJsonCodec.ID_OK.matches(agent.id)) { "unsafe agent id" }
        dir.mkdirs()
        val target = File(dir, "${agent.id}.json")
        val tmp = File(dir, "${agent.id}.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(AgentJsonCodec.encode(agent).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(id: String) {
        if (!AgentJsonCodec.ID_OK.matches(id)) return
        File(dir, "$id.json").delete()
        File(dir, "$id.json.tmp").delete()
    }

    /** Marker so the sample agent is created once, not again after the person deletes every agent. */
    fun seeded(): Boolean = File(dir, SEEDED).exists()
    fun markSeeded() { dir.mkdirs(); File(dir, SEEDED).writeText("1") }

    private fun quarantine(f: File) {
        quarantineDir.mkdirs()
        val dest = File(quarantineDir, "${f.name}.${clock()}")
        try {
            Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // Could not move it: leave it where it is. It is skipped on every load and reported again.
        }
    }

    private companion object { const val SEEDED = ".seeded" }
}
