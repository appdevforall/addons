package org.appdevforall.maps.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * aapt2 drops an unescaped `"` from a string resource, `&quot;` included, so the device shows the
 * text without its quotes. Every literal double quote in a plugin string must be written `\"`.
 */
class StringResourceQuotesTest {

  @Test
  fun `every double quote in a string resource is backslash-escaped`() {
    val files =
      File("src/main/res").listFiles { f -> f.name.startsWith("values") }!!.mapNotNull {
        File(it, "strings.xml").takeIf(File::isFile)
      }
    assertTrue("no strings.xml found under src/main/res", files.isNotEmpty())
    var checked = 0
    val bad = mutableListOf<String>()
    for (file in files) {
      val strings =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
      for (i in 0 until strings.length) {
        val element = strings.item(i) as Element
        checked++
        // After XML decoding, a quote aapt2 keeps is one preceded by a backslash.
        if (Regex("""(?<!\\)"""").containsMatchIn(element.textContent)) {
          bad += "${file.parentFile.name}/${element.getAttribute("name")}"
        }
      }
    }
    assertTrue("checked no strings", checked > 0)
    assertEquals("strings with a quote aapt2 will drop (of $checked checked)", emptyList<String>(), bad)
  }
}
