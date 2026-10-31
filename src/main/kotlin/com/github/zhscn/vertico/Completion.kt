package com.github.zhscn.vertico

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class Entry(val name: String, val isDirectory: Boolean) {
    val label: String get() = if (isDirectory) "$name/" else name
}

sealed interface Submit {
    data class Descend(val directory: Path) : Submit
    data class Open(val file: Path) : Submit
    data class Create(val path: Path, val isDirectory: Boolean) : Submit
    data object Nothing : Submit
}

/** Pure find-file logic. [home] expands `~`; [base] resolves input that is not absolute. */
class FindFileModel(private val home: Path, private val base: Path) {

    /** `~/a//etc` becomes `/etc` and `/a/~/b` becomes `~/b`, as `vertico-directory-tidy` does. */
    fun tidy(text: String): String {
        val root = text.lastIndexOf("//")
        val tilde = text.lastIndexOf("/~/")
        val cut = maxOf(root, tilde)
        return if (cut < 0) text else text.substring(cut + 1)
    }

    fun abbreviate(directory: Path): String {
        val normal = directory.normalize()
        val text = when {
            normal == home -> "~"
            normal.startsWith(home) -> "~/" + home.relativize(normal)
            else -> normal.toString()
        }
        return if (text.endsWith("/")) text else "$text/"
    }

    /** The directory being listed: everything up to the last `/`. */
    fun directoryOf(text: String): Path? {
        val slash = text.lastIndexOf('/')
        return expand(if (slash < 0) "" else text.substring(0, slash + 1))
    }

    fun patternOf(text: String): String = text.substring(text.lastIndexOf('/') + 1)

    private fun expand(text: String): Path? = try {
        when {
            text == "~" || text == "~/" -> home
            text.startsWith("~/") -> home.resolve(text.substring(2))
            else -> base.resolve(text)
        }.normalize()
    } catch (_: InvalidPathException) {
        null
    }

    /** RET: the selected candidate wins, otherwise the input is taken literally. */
    fun submit(text: String, selected: Entry?): Submit {
        if (selected == null) return submitInput(text)
        val target = directoryOf(text)?.resolve(selected.name)?.normalize() ?: return Submit.Nothing
        return if (selected.isDirectory) Submit.Descend(target) else Submit.Open(target)
    }

    /** M-RET (`vertico-exit-input`): ignore candidates, use the text as typed. */
    fun submitInput(text: String): Submit {
        val path = expand(text) ?: return Submit.Nothing
        return when {
            Files.isDirectory(path) -> Submit.Descend(path)
            Files.exists(path) -> Submit.Open(path)
            text.isEmpty() -> Submit.Nothing
            else -> Submit.Create(path, text.endsWith("/"))
        }
    }

    /** Text after DEL at a `/` boundary (`vertico-directory-up`). */
    fun parentOf(text: String): String {
        val trimmed = text.trimEnd('/')
        if (trimmed == "~") return abbreviate(home.parent ?: home)
        if (trimmed.isEmpty()) return "/"
        val slash = trimmed.lastIndexOf('/')
        return if (slash < 0) "" else trimmed.substring(0, slash + 1)
    }

    /** Text after M-DEL: drops the last path segment, whether or not it is complete. */
    fun deleteWord(text: String): String =
        if (text.endsWith("/")) parentOf(text) else text.substring(0, text.lastIndexOf('/') + 1)

    /** TAB: put the candidate into the input. */
    fun complete(text: String, selected: Entry): String =
        text.substring(0, text.lastIndexOf('/') + 1) + selected.label
}

object Matcher {
    /** Orderless: space-separated tokens match in any order; smart case. */
    fun filter(entries: List<Entry>, pattern: String): List<Entry> {
        val tokens = pattern.split(' ').filter { it.isNotEmpty() }
        val ignoreCase = pattern.none { it.isUpperCase() }
        val dotted = pattern.startsWith(".")
        val first = tokens.firstOrNull().orEmpty()
        return entries
            .filter { e -> tokens.all { e.name.contains(it, ignoreCase) } && (e.name != ".." || dotted) }
            .sortedWith(
                compareBy<Entry>(
                    { !it.name.equals(pattern, ignoreCase) },
                    { !it.name.startsWith(first, ignoreCase) },
                    { it.name.startsWith(".") && !dotted },
                    { !it.isDirectory },
                    { it.name.lowercase() },
                ),
            )
    }

    /** Sorted, disjoint spans of every token occurrence in [name], for highlighting. */
    fun highlights(name: String, pattern: String): List<IntRange> {
        val ignoreCase = pattern.none { it.isUpperCase() }
        val spans = pattern.split(' ').filter { it.isNotEmpty() }.flatMap { token ->
            generateSequence(name.indexOf(token, 0, ignoreCase)) { name.indexOf(token, it + 1, ignoreCase) }
                .takeWhile { it >= 0 }
                .map { it until it + token.length }
                .toList()
        }.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
            } else {
                merged.add(span)
            }
        }
        return merged
    }
}

fun listDirectory(directory: Path): List<Entry> {
    val entries = ArrayList<Entry>()
    try {
        Files.newDirectoryStream(directory).use { stream ->
            stream.forEach { entries.add(Entry(it.fileName.toString(), Files.isDirectory(it))) }
        }
    } catch (_: IOException) {
        return emptyList()
    }
    if (directory.parent != null) entries.add(Entry("..", true))
    return entries
}
