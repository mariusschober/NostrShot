package com.nostrpost.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayMessageParserTest {

    @Test
    fun parsesAcceptedOk() {
        val ok = RelayMessageParser.parseOk("""["OK","abc",true,""]""")
        assertEquals("abc", ok!!.id)
        assertTrue(ok.accepted)
        assertEquals("", ok.reason)
    }

    @Test
    fun parsesRejectionWithEscapedReason() {
        val ok = RelayMessageParser.parseOk(
            """["OK","abc",false,"rate-limited: wait\n5s \u0022please\u0022"]"""
        )
        assertFalse(ok!!.accepted)
        assertEquals("rate-limited: wait\n5s \"please\"", ok.reason)
    }

    @Test
    fun ignoresUnrelatedFrames() {
        assertNull(RelayMessageParser.parseOk("""["NOTICE","hello"]"""))
        assertNull(RelayMessageParser.parseOk("""["EOSE","sub"]"""))
        assertNull(RelayMessageParser.parseOk("""["EVENT","sub",{}]"""))
    }

    @Test
    fun malformedFramesReturnNullInsteadOfThrowing() {
        assertNull(RelayMessageParser.parseOk(""))
        assertNull(RelayMessageParser.parseOk("["))
        assertNull(RelayMessageParser.parseOk("""["OK","abc"]"""))
        assertNull(RelayMessageParser.parseOk("""["OK","abc","yes","reason"]"""))
        assertNull(RelayMessageParser.parseOk("""["OK","abc",true,"unterminated]"""))
        assertNull(RelayMessageParser.parseOk("not json"))
    }
}
