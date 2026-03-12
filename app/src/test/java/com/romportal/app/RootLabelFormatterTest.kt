package com.romportal.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RootLabelFormatterTest {
    @Test
    fun emptyUri_showsNoFolderSelected() {
        assertEquals("No folder selected", formatRootLabel(null))
    }

    @Test
    fun invalidUri_fallsBackToGenericLabel() {
        assertEquals("Selected folder", formatRootLabel("not-a-tree-uri"))
    }

    @Test
    fun validTreeUri_showsFolderSegment() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AROMs"
        assertEquals("Selected folder: ROMs", formatRootLabel(uri))
    }
}
