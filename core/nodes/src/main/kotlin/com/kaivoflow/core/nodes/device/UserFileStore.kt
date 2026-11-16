package com.kaivoflow.core.nodes

import java.io.File

/**
 * Sandboxed storage behind the File Step. Everything lives under [root] (on Android
 * `files/user-files/`), never in the Flow mirror directory `files/flows/`.
 *
 * Names are relative paths. Absolute paths, `..` segments, NUL bytes and anything whose real
 * location (after symlinks are resolved) leaves [root] are rejected with [IllegalArgumentException].
 */
class UserFileStore(root: File) {
    private val root: File = root.absoluteFile

    fun resolve(name: String): File {
        val unified = checkName(name)
        root.mkdirs()
        val base = root.canonicalFile
        val target = File(base, unified).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "the file must stay inside the Kaivo files folder" }
        return target
    }

    fun write(name: String, content: String): String {
        val file = resolve(name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file.path
    }

    fun read(name: String): String? = resolve(name).takeIf { it.isFile }?.readText()

    companion object {
        /** Pure syntax check (no disk access); returns the name with `/` separators. */
        fun checkName(name: String): String {
            require(name.isNotBlank()) { "the file name is empty" }
            require('\u0000' !in name) { "the file name contains an invalid character" }
            val unified = name.replace('\\', '/')
            require(!unified.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(unified)) {
                "use a relative file name, not an absolute path"
            }
            require(unified.split('/').none { it == ".." }) { "file names cannot contain \"..\"" }
            return unified
        }
    }
}
