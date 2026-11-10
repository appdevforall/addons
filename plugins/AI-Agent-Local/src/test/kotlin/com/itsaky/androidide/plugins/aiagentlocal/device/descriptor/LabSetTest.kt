package com.itsaky.androidide.plugins.aiagentlocal.device.descriptor

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The lab set: the five lab phones' inputs and expected descriptors, read from the directory named by the
 * system property `part1.labExamplesDir` (inputs under `inputs/<label>/`, expected `<label>-descriptor-v0.2.json`).
 * Skipped when the property is unset. Those files hold real device values and are never copied into the
 * repository: on a difference this test prints only the JSON paths that differ, never their values.
 */
class LabSetTest {

    @Test
    fun givenTheLabExamples_whenGenerated_thenEachDescriptorEqualsTheExpectedOne() {
        val dirName = System.getProperty("part1.labExamplesDir")
        assumeTrue("part1.labExamplesDir is not set; lab set skipped", !dirName.isNullOrEmpty())
        val dir = File(dirName!!)
        val labels = File(dir, "inputs").listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()
        assertTrue("no phone inputs found", labels.isNotEmpty())
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        for (label in labels) {
            val inDir = File(dir, "inputs/$label")
            val rowsFile = File(inDir, "rows.jsonl")
            val recFile = File(inDir, "dbrecord.json")
            val names = listOfNotNull("static.json", "rows.jsonl".takeIf { rowsFile.exists() }, "dbrecord.json".takeIf { recFile.exists() })
            val got = DescriptorGenerator.generate(
                JSONObject(File(inDir, "static.json").readText()),
                if (rowsFile.exists()) rowsFile.readLines() else emptyList(),
                if (recFile.exists()) JSONObject(recFile.readText()) else null,
                pluginVersion = null,
                inputNames = names,
            )
            val expected = Json.parse(File(dir, "$label-descriptor-v0.2.json").readText())
            val diff = Json.diff(expected, Json.fromOrg(got))
            results.add("$label: ${if (diff.isEmpty()) "PASS" else "FAIL at ${diff.size} path(s)"}")
            if (diff.isNotEmpty()) failures.add("$label differs at paths $diff") // paths only, never values
        }
        println("LAB SET RESULT: ${results.joinToString("; ")}")
        assertEquals(emptyList<String>(), failures)
    }
}
