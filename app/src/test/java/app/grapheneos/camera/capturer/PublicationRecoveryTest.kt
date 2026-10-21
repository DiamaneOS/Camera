package app.grapheneos.camera.capturer

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PublicationRecoveryTest {
    private val bytes=ByteArray(4096) { (it*31).toByte() }
    private val expected=PublicationRecovery.identity(ByteArrayInputStream(bytes))

    @Test fun identityChecksTheEntireWrittenArtifactIncludingTrailingBytes() {
        assertTrue(PublicationRecovery.matches(ByteArrayInputStream(bytes),expected))
        assertFalse(PublicationRecovery.matches(ByteArrayInputStream(bytes.copyOf(2048)),expected))
        assertFalse(PublicationRecovery.matches(ByteArrayInputStream(bytes+byteArrayOf(1)),expected))
        val changed=bytes.copyOf();changed[2048]=(changed[2048].toInt() xor 1).toByte()
        assertFalse(PublicationRecovery.matches(ByteArrayInputStream(changed),expected))
    }
    @Test fun interruptionAfterJournalingNeverCreatesAnotherUnresolvedOutput() {
        var hasOwnedUri=true;var unavailable=true;var deletionWorks=false;var outputs=1;var cancelled=false
        fun recover(status: PublicationRecovery.Status): PublicationRecovery.Decision {
            val decision=PublicationRecovery.decide(status,{cancelled}) {
                if(unavailable || !deletionWorks)false else {outputs--;true}
            }
            if(decision in listOf(PublicationRecovery.Decision.RETRY,PublicationRecovery.Decision.DISCARD))hasOwnedUri=false
            return decision
        }
        repeat(3) { assertEquals(PublicationRecovery.Decision.DEFER,recover(PublicationRecovery.Status.UNAVAILABLE)) }
        assertTrue(hasOwnedUri);assertEquals(1,outputs)
        unavailable=false
        assertEquals(PublicationRecovery.Decision.DEFER,recover(PublicationRecovery.Status.INCOMPLETE))
        assertTrue(hasOwnedUri);assertEquals(1,outputs)
        deletionWorks=true
        assertEquals(PublicationRecovery.Decision.RETRY,recover(PublicationRecovery.Status.INCOMPLETE))
        assertFalse(hasOwnedUri);assertEquals(0,outputs)
        outputs++ // Only now may the caller publish its replacement.
        assertEquals(PublicationRecovery.Decision.ADOPT,recover(PublicationRecovery.Status.COMPLETE))
        assertEquals(1,outputs)
        cancelled=true
        assertEquals(PublicationRecovery.Decision.DISCARD,recover(PublicationRecovery.Status.COMPLETE))
        assertEquals(0,outputs)
    }
    @Test fun cancellationAndPermissionFailuresDoNotInventCompletionOrAbsence() {
        var cancelled=false;var removed=false
        val afterHash=PublicationRecovery.Status.COMPLETE;cancelled=true
        assertEquals(PublicationRecovery.Decision.DISCARD,PublicationRecovery.decide(afterHash,{cancelled}) {removed=true;true})
        assertTrue(removed)
        assertEquals(PublicationRecovery.Decision.DEFER,PublicationRecovery.decide(PublicationRecovery.Status.UNAVAILABLE,{true}) {error("Must not delete unknown output")})
        assertEquals(PublicationRecovery.Decision.DISCARD,PublicationRecovery.decide(PublicationRecovery.Status.MISSING,{true}) {error("Already absent")})
    }
    @Test(expected=IOException::class) fun transientReadFailureIsNotReportedAsMismatch() {
        PublicationRecovery.matches(object:java.io.InputStream() {override fun read():Int=throw IOException("provider unavailable")},expected)
    }

    @Test fun recoveryOnlyTouchesItsOwnPartialDocument() {
        val expected = PublicationRecovery.Identity(1000, "0".repeat(64))
        assertEquals(true, PublicationRecovery.mayOwnPartial("IMG_1.jpg", 400, "IMG_1.jpg", expected))
        assertEquals(true, PublicationRecovery.mayOwnPartial("IMG_1.jpg", 1000, "IMG_1.jpg", expected))
        assertEquals(false, PublicationRecovery.mayOwnPartial("IMG_1.jpg", 1001, "IMG_1.jpg", expected))
        assertEquals(false, PublicationRecovery.mayOwnPartial("holiday.jpg", 400, "IMG_1.jpg", expected))
        assertEquals(false, PublicationRecovery.mayOwnPartial(null, 400, "IMG_1.jpg", expected))
        assertEquals(false, PublicationRecovery.mayOwnPartial("IMG_1.jpg", null, "IMG_1.jpg", expected))
    }
}
