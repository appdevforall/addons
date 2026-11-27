package org.appdevforall.shellhooks.engine

import org.appdevforall.shellhooks.engine.ScriptHints.Kind
import org.appdevforall.shellhooks.engine.ScriptHints.Kind.COMMAND
import org.appdevforall.shellhooks.engine.ScriptHints.Kind.CONTINUATION
import org.appdevforall.shellhooks.engine.ScriptHints.Kind.HERE_DOCUMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptHintsTest {
    private fun lines(script: String): List<String> {
        // Every script here is plain, as only a plain script reaches the dialog.
        assertNull(script, ScriptReview.checkBytes(script.toByteArray()))
        return ScriptReview.lines(script.toByteArray())
    }

    private fun marks(script: String): ScriptHints.Marks = ScriptHints.find(lines(script))

    /** The kinds each line of [script] is marked for, keyed by line number; unmarked lines left out. */
    private fun byLine(script: String): Map<Int, Set<Kind>> {
        val found = marks(script)
        return Kind.entries
            .flatMap { kind -> found.of(kind).map { it to kind } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    /**
     * Scripts the fourth and fifth reviews wrote. Each is plain and runs its PWNED command (or the
     * command after a comment) under dash, bash or busybox, though it reads as inert. With each, the
     * line the trick needs and the kinds that line must be marked for.
     */
    private val tricks: Map<String, Pair<String, Map<Int, Set<Kind>>>> =
        mapOf(
            "continuation into a #" to ("#!/bin/sh\necho building\\\n#;echo PWNED-hash-line\n" to mapOf(2 to setOf(CONTINUATION))),
            "here-document ending at EOF" to
                (
                    "#!/bin/sh\ncat >/dev/null <<E\"O\"F\nNotes. The block below is quoted text only:\nEOF\n" +
                        "echo PWNED-heredoc\nE\"O\"F\n" to mapOf(2 to setOf(HERE_DOCUMENT))
                ),
            "backticks in here-document text" to
                (
                    "#!/bin/sh\ncat >/dev/null <<EOF\nUsage: see `echo PWNED-backtick >&2` for details\nEOF\n" to
                        mapOf(2 to setOf(HERE_DOCUMENT), 3 to setOf(COMMAND))
                ),
            "short wrap trick" to
                (
                    ": <<'NOTES'" + " ".repeat(60) + "; echo PWNED-wrap\nThese notes are documentation only.\nNOTES\n" to
                        mapOf(1 to setOf(HERE_DOCUMENT))
                ),
            "ANSI-C quoted delimiter" to ("cat <<\$'EOF'\nnotes\nEOF\necho PWNED-dq1\n\$EOF\n" to mapOf(1 to setOf(HERE_DOCUMENT))),
            "locale quoted delimiter" to ("cat <<\$\"EOF\"\nnotes\nEOF\necho PWNED-dq2\n\$EOF\n" to mapOf(1 to setOf(HERE_DOCUMENT))),
            "(( << )) after x=1" to
                ("x=1\n(( y = x << 1 ))\necho PWNED-looks-like-command\n1\necho after\n" to mapOf(2 to setOf(HERE_DOCUMENT))),
            "(( << )) before exit 0" to
                ("#!/bin/sh\n(( n = 1 << 1 ))\nexit 0\n1\necho PWNED-dead-code\n" to mapOf(2 to setOf(HERE_DOCUMENT))),
            "backslash after )#" to ("echo build \$(echo x)#\\\n#; echo PWNED-P1\n" to mapOf(1 to setOf(CONTINUATION))),
            "quote in \$( before a continuation" to
                ("q=\"\$(printf '\"')\"\necho ok\\\n#;echo PWNED-P2\n# that's all\n" to mapOf(2 to setOf(CONTINUATION))),
            "backslash in a double-quoted delimiter" to
                ("cat <<\"E\\F\"\nnotes\nE\\F\necho PWNED-P3\nEF\n" to mapOf(1 to setOf(HERE_DOCUMENT))),
            "delimiter split by a continuation" to
                (
                    "cat <<EO\\\nF\ntext \$(echo PWNED-P4-expanded)\nEOF\necho PWNED-P4b\nEO\n" to
                        mapOf(1 to setOf(HERE_DOCUMENT, CONTINUATION), 3 to setOf(COMMAND))
                ),
            "<< then a continuation" to
                ("cat <<\\\nEOF\ntext\nEOF\necho PWNED-P5\n\nmore\n" to mapOf(1 to setOf(HERE_DOCUMENT, CONTINUATION))),
            "<< in a parameter expansion" to ("v=\${PWD%<<*}\necho PWNED-P6\n" to mapOf(1 to setOf(HERE_DOCUMENT))),
            "backslash ending a comment" to ("# cleanup old files \\\necho PWNED-P7\n" to mapOf(1 to setOf(CONTINUATION))),
            "backslash ending a comment, then rm" to ("# cleanup old files \\\nrm -rf build\n" to mapOf(1 to setOf(CONTINUATION))),
            "continuation in here-document text" to
                ("cat <<EOF\na\\\nEOF\necho PWNED-P8\nEOF\n" to mapOf(1 to setOf(HERE_DOCUMENT), 2 to setOf(CONTINUATION))),
            "ANSI-C quote before a continuation" to
                ("x=\$'\\''\necho ok\\\n#;echo PWNED-P9\n# it's fine\n" to mapOf(2 to setOf(CONTINUATION))),
            "array subscript in \$((x))" to
                ("x='a[\$(echo PWNED-P10 >&2)]'\necho \$((x))\n" to mapOf(1 to setOf(COMMAND), 2 to setOf(COMMAND))),
            "let with <<" to ("let x=1<<2\necho after-let\n2\necho PWNED-P11\n" to mapOf(1 to setOf(HERE_DOCUMENT))),
            "(( with a quoted paren" to ("((echo '(' ) )\necho ok\\\n#;echo PWNED-P12\n" to mapOf(2 to setOf(CONTINUATION))),
            "quotes in \$( before a here-document" to
                (
                    "echo \"Branch: \$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo \"it's detached\")\"\n" +
                        "cat <<EOF\nBuilt \$(echo PWNED-P13)\nEOF\n# that's it\n" to
                        mapOf(2 to setOf(HERE_DOCUMENT), 3 to setOf(COMMAND))
                ),
        )

    @Test
    fun everyTrickFromTheReviewsIsMarkedOnTheLineItNeeds() {
        for ((name, trick) in tricks) {
            val (script, needed) = trick
            val found = byLine(script)
            for ((line, kinds) in needed) {
                assertTrue("$name: line $line needs $kinds, has ${found[line]}", found[line].orEmpty().containsAll(kinds))
            }
        }
    }

    /**
     * Tricks the reviews found outside the three marked kinds. They are listed here so the dialog's
     * and docs' "a line without a mark can still do anything" stays a known fact, not a guess: each
     * trick's line gets no mark.
     */
    @Test
    fun tricksOutsideTheMarkedKindsGetNoMark() {
        val unmarked =
            mapOf(
                "alias" to ("alias cd='echo PWNED-alias;cd'\ncd /tmp\n" to listOf(1, 2)),
                "function named [" to ("[() { echo PWNED-func; }\nif [ -d /tmp ]; then echo dir; fi\n" to listOf(1, 2)),
                "\${x@P}" to ("x='\$(echo PWNED-atP >&2)'\necho \"\${x@P}\"\n" to listOf(2)),
                "IFS" to ("IFS=_\nc=echo_PWNED-ifs\n\$c\n" to listOf(1, 2, 3)),
                "esac#" to ("x=1\ncase \$x in\n1) echo one ;;\nesac#;echo PWNED-esac\n" to listOf(1, 2, 3, 4)),
            )
        for ((name, case) in unmarked) {
            val (script, trickLines) = case
            val found = byLine(script)
            for (line in trickLines) assertEquals("$name: line $line", null, found[line])
        }
    }

    // A line is marked exactly when it has <<, a final backslash, a backtick or $(: checked against
    // every line of up to four characters drawn from the characters that matter and a few that don't.
    @Test
    fun aLineIsMarkedExactlyForTheSyntaxItHas() {
        val alphabet = listOf('<', '\\', '`', '$', '(', '#', '\'', '"', 'a', ' ')
        var lines = listOf("")
        repeat(4) {
            lines = lines.flatMap { prefix -> alphabet.map { prefix + it } }
            for (line in lines) {
                val expected =
                    buildSet {
                        if ("<<" in line) add(HERE_DOCUMENT)
                        if (line.endsWith("\\")) add(CONTINUATION)
                        if ('`' in line || "$(" in line) add(COMMAND)
                    }
                assertEquals("'$line'", expected, ScriptHints.kindsOf(line))
            }
        }
    }

    // What the fifth review used to blind the old scanner: a line that leaves a quote, a
    // here-document, a comment continuation or (( open. None of them changes how a later line is marked.
    @Test
    fun noLineChangesHowTheLinesAfterItAreMarked() {
        val openers = listOf("echo \"it's", "cat <<'EOF'", ": '", "(( x", "# \\", "echo \$(", "echo `", "x=\$'\\''")
        val probes = listOf("echo plain", "cat <<EOF", "echo ok\\", "echo `date`", "echo \$(date)", "#;echo hidden")
        for (opener in openers) {
            for (probe in probes) {
                val found = ScriptHints.find(listOf(opener, probe, opener))
                for (kind in Kind.entries) {
                    assertEquals("'$opener' then '$probe': $kind", kind in ScriptHints.kindsOf(probe), 2 in found.of(kind))
                }
            }
        }
    }

    @Test
    fun anOrdinaryScriptGetsNoMarks() {
        val script =
            "#!/bin/sh\n# Log each saved file.\nset -eu\nfile=\"\$1\"\ncase \"\$file\" in\n" +
                "  *.kt) echo \"Kotlin: \$file\" >> \"\$HOME/hooks.log\" ;;\n" +
                "  *) echo \"Saved: \$file\" >> \"\$HOME/hooks.log\" ;;\nesac\n"

        assertTrue(marks(script).isEmpty)
        assertEquals(emptySet<Int>(), marks(script).lines)
    }

    @Test
    fun marksAreGroupedByKindInLineOrder() {
        val script = "cat <<EOF\nx `a`\nEOF\necho a\\\nb \$(c) <<X\nX\n"
        val found = marks(script)

        assertEquals(listOf(1, 5), found.hereDocuments)
        assertEquals(listOf(4), found.continuations)
        assertEquals(listOf(2, 5), found.commands)
        assertEquals(setOf(1, 2, 4, 5), found.lines)
    }

    @Test
    fun aLongListShowsTheFirstHundredAndCountsTheRest() {
        assertEquals(emptyList<Int>() to 0, ScriptHints.listed(emptyList()))
        assertEquals((1..100).toList() to 0, ScriptHints.listed((1..100).toList()))
        assertEquals((1..100).toList() to 1, ScriptHints.listed((1..101).toList()))
        assertEquals((1..100).toList() to 150, ScriptHints.listed((1..250).toList()))
    }
}
