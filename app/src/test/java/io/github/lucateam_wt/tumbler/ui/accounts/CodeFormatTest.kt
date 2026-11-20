package io.github.lucateam_wt.tumbler.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeFormatTest {

    @Test
    fun `six digits are split three and three`() {
        assertEquals("123 456", formatCode("123456"))
    }

    @Test
    fun `eight digits are split four and four`() {
        assertEquals("1234 5678", formatCode("12345678"))
    }

    @Test
    fun `seven digits put the shorter group first`() {
        assertEquals("123 4567", formatCode("1234567"))
    }

    @Test
    fun `leading zeros are kept`() {
        assertEquals("007 081", formatCode("007081"))
    }

    @Test
    fun `very short codes are left alone`() {
        assertEquals("123", formatCode("123"))
    }
}
