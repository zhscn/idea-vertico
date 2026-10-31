package com.github.zhscn.vertico

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CompletionTest {
    private val home = Path.of("/home/u")
    private val model = FindFileModel(home, Path.of("/home/u/p"))

    @Test fun tidyResetsOnDoubleSlashAndTilde() {
        assertEquals("/etc/", model.tidy("~/code//etc/"))
        assertEquals("~/x", model.tidy("/usr/lib/~/x"))
        assertEquals("~/a/b", model.tidy("~/a/b"))
    }

    @Test fun abbreviatesHome() {
        assertEquals("~/", model.abbreviate(home))
        assertEquals("~/a/b/", model.abbreviate(Path.of("/home/u/a/b")))
        assertEquals("/etc/", model.abbreviate(Path.of("/etc")))
        assertEquals("/", model.abbreviate(Path.of("/")))
    }

    @Test fun splitsDirectoryAndPattern() {
        assertEquals(Path.of("/home/u/a"), model.directoryOf("~/a/fo"))
        assertEquals("fo", model.patternOf("~/a/fo"))
        assertEquals("", model.patternOf("~/a/"))
        assertEquals(Path.of("/home/u/p"), model.directoryOf("rel"))
    }

    @Test fun deleteAtBoundaryGoesUp() {
        assertEquals("~/a/", model.parentOf("~/a/b/"))
        assertEquals("/home/", model.parentOf("~/"))
        assertEquals("/", model.parentOf("/usr/"))
        assertEquals("/", model.parentOf("/"))
    }

    @Test fun deleteWordDropsSegment() {
        assertEquals("~/a/", model.deleteWord("~/a/foo"))
        assertEquals("~/a/", model.deleteWord("~/a/b/"))
    }

    @Test fun completeKeepsDirectorySlash() {
        assertEquals("~/a/src/", model.complete("~/a/s", Entry("src", true)))
        assertEquals("~/a/x.kt", model.complete("~/a/x", Entry("x.kt", false)))
    }

    @Test fun highlightsEveryTokenAndMergesOverlaps() {
        assertEquals(listOf(0..5), Matcher.highlights("FooBar.kt", "bar foo"))
        assertEquals(listOf(0..1, 3..4), Matcher.highlights("abxab", "ab"))
        assertEquals(listOf(0..3), Matcher.highlights("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), Matcher.highlights("foo", "Foo"))
        assertEquals(listOf(0..2), Matcher.highlights("Foo", "Foo"))
        assertEquals(emptyList<IntRange>(), Matcher.highlights("foo", ""))
    }

    @Test fun orderlessSmartCase() {
        val entries = listOf(Entry("FooBar.kt", false), Entry("barfoo.kt", false), Entry("baz", false))
        assertEquals(listOf("FooBar.kt", "barfoo.kt"), Matcher.filter(entries, "foo bar").map { it.name })
        assertEquals(listOf("FooBar.kt"), Matcher.filter(entries, "Foo").map { it.name })
    }

    @Test fun exactThenPrefixThenDirectories() {
        val entries = listOf(Entry("abc", false), Entry("ab", false), Entry("abd", true), Entry("xab", true))
        assertEquals(listOf("ab", "abd", "abc", "xab"), Matcher.filter(entries, "ab").map { it.name })
    }

    @Test fun hiddenSortsLastAndParentOnlyOnDot() {
        val entries = listOf(Entry(".git", true), Entry("src", true), Entry("..", true))
        assertEquals(listOf("src", ".git"), Matcher.filter(entries, "").map { it.name })
        assertEquals(listOf("..", ".git"), Matcher.filter(entries, ".").map { it.name })
    }

    @Test fun submitDispatches() {
        val root = Files.createTempDirectory("vertico").toRealPath()
        Files.createDirectory(root.resolve("dir"))
        Files.createFile(root.resolve("file"))
        val m = FindFileModel(home, root)
        val r = "$root/"
        assertEquals(Submit.Descend(root.resolve("dir")), m.submit(r, Entry("dir", true)))
        assertEquals(Submit.Open(root.resolve("file")), m.submit(r, Entry("file", false)))
        assertEquals(root.parent, (m.submit(r, Entry("..", true)) as Submit.Descend).directory)
        assertEquals(Submit.Create(root.resolve("a/b.txt"), false), m.submit("${r}a/b.txt", null))
        assertEquals(Submit.Create(root.resolve("new"), true), m.submitInput("${r}new/"))
        assertEquals(Submit.Open(root.resolve("file")), m.submitInput("${r}file"))
    }

    @Test fun listsWithParentEntry() {
        val root = Files.createTempDirectory("vertico")
        Files.createFile(root.resolve("f"))
        assertEquals(setOf(Entry("f", false), Entry("..", true)), listDirectory(root).toSet())
    }
}
