package com.kaivoflow.core.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file replacement: write a temp file in the same folder, flush it to disk, then rename it over
 * the target. A kill at any point leaves either the old file or the new one, never a half-written file.
 */
object AtomicFiles {
    fun write(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))

    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: error("no parent folder for ${target.name}")
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
