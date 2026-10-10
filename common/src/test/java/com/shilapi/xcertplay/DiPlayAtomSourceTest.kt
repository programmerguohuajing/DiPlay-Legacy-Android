package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiPlayAtomSourceTest {
    @Test fun skipsPrereleaseAndFindsStableRelease() {
        val input = """<?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/123/v0.2.35-beta</id>
                <link rel="alternate" href="https://github.com/programmerguohuajing/DiPlay-Legacy-Android/releases/tag/v0.2.35-beta"/>
                <title>Preview</title>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/123/v0.2.34</id>
                <link rel="alternate" href="https://github.com/programmerguohuajing/DiPlay-Legacy-Android/releases/tag/v0.2.34"/>
                <title>Stable release</title>
                <content type="html">&lt;p&gt;Fixes legacy USB&lt;/p&gt;</content>
              </entry>
            </feed>"""
        val release = DiPlayAtomSource.newest(input, "0.2.33")
        assertEquals("v0.2.34", release?.tag)
        assertEquals("Fixes legacy USB", release?.notes)
        assertNull(DiPlayAtomSource.newest(input, "0.2.34"))
    }

    @Test fun ignoresEntriesWhoseUrlIsNotOurRelease() {
        val xml = """<feed><entry>
            <id>tag:github.com,2008:Repository/123/v0.2.40</id>
            <link rel="alternate" href="https://example.org/other/v0.2.40"/>
            <title>Untrusted</title>
            </entry></feed>"""
        assertNull(DiPlayAtomSource.newest(xml, "0.2.33"))
    }
}
