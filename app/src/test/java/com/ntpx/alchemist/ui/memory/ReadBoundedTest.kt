package com.ntpx.alchemist.ui.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class ReadBoundedTest {

    @Test
    fun returnsContentAtOrUnderLimit() {
        assertEquals("abc", MemoryViewModel.readBounded(StringReader("abc"), 3))
        assertEquals("", MemoryViewModel.readBounded(StringReader(""), 0))
    }

    @Test
    fun stopsAsSoonAsLimitIsExceeded() {
        var served = 0L
        val endless = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                cbuf.fill('x', off, off + len)
                served += len
                return len
            }
            override fun close() {}
        }
        assertNull(MemoryViewModel.readBounded(endless, 100_000))
        assertEquals(true, served < 200_000)
    }
}
