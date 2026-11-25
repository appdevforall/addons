package com.itsaky.androidide.plugins.aiagentmcp.testing

import android.content.Context
import com.itsaky.androidide.plugins.aiagentmcp.R
import io.mockk.every
import io.mockk.mockk
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** A Context answering `getString` from this plugin's strings.xml, so the shipped words are checked. */
fun stringsXmlContext(): Context {
    val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/strings.xml"))
        .getElementsByTagName("string")
    val texts = (0 until nodes.length).map { nodes.item(it) as Element }
        .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }
    val text = { id: Int -> texts.getValue(names.getValue(id)) }
    return mockk(relaxed = true) {
        every { getString(any()) } answers { text(firstArg()) }
        every { getString(any(), *anyVararg()) } answers {
            String.format(text(firstArg()), *secondArg<Array<out Any?>>())
        }
    }
}
