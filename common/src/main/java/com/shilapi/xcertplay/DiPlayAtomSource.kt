package com.shilapi.xcertplay

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Strictly parse published release entries from GitHub's official Atom feed. No API token needed.
 * Non-semver / preview tags are ignored.
 */
internal object DiPlayAtomSource {
    private const val REPO = "https://github.com/programmerguohuajing/DiPlay-Legacy-Android"
    data class Entry(val tag: String, val notes: String)

    fun newest(xml: String, installedVersion: String): Entry? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var entry = false
        var id = ""
        var href = ""
        var title = ""
        var notes = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        entry = true
                        id = ""
                        href = ""
                        title = ""
                        notes = ""
                    }
                    "id" -> if (entry) id = parser.nextText()
                    "title" -> if (entry) title = parser.nextText()
                    "content" -> if (entry) {
                        notes = parser.nextText()
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ").trim().take(6000)
                    }
                    "link" -> if (entry && parser.getAttributeValue(null, "rel") == "alternate") {
                        href = parser.getAttributeValue(null, "href") ?: ""
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry") {
                    entry = false
                    val tag = id.substringAfterLast('/')
                    if (id.startsWith("tag:github.com,2008:Repository/") &&
                        href == "$REPO/releases/tag/$tag" &&
                        DiPlayUpdateClient.newerVersion(tag, installedVersion)
                    ) return Entry(tag, notes.ifBlank { title }.take(6000))
                }
            }
            parser.next()
        }
        return null
    }
}
