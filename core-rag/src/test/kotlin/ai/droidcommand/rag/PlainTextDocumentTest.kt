package ai.droidcommand.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlainTextDocumentTest {
    @Test
    fun `reads utf8 and strips a BOM`() {
        assertEquals("héllo # md", PlainTextDocument.read("﻿héllo # md".toByteArray()))
    }

    @Test
    fun `rejects oversized, binary and invalid utf8`() {
        assertFailsWith<DocumentReadException> { PlainTextDocument.read(ByteArray(11), maxBytes = 10) }
        assertFailsWith<DocumentReadException> { PlainTextDocument.read(byteArrayOf(65, 0, 66)) }
        assertFailsWith<DocumentReadException> { PlainTextDocument.read(byteArrayOf(0xC3.toByte(), 0x28)) }
    }
}
