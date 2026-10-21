package app.grapheneos.camera.capturer

import java.io.InputStream
import java.security.MessageDigest

/** A journaled output cannot be replaced until its disposition is confirmed. */
internal object PublicationRecovery {
    const val MAX_BYTES = 32L * 1024 * 1024
    data class Identity(val bytes: Long, val sha256: String) {
        init { require(bytes in 1..MAX_BYTES && sha256.matches(Regex("[0-9a-f]{64}"))) }
    }
    enum class Status { COMPLETE, INCOMPLETE, MISSING, UNAVAILABLE }
    enum class Decision { ADOPT, RETRY, DISCARD, DEFER }

    /**
     * Whether a document at a journaled location may be one this app started writing: the
     * expected name and no larger than the finished photo. Anything else is left alone.
     */
    fun mayOwnPartial(name: String?, size: Long?, expectedName: String, expected: Identity?): Boolean =
        name == expectedName && size != null && size >= 0 && size <= (expected?.bytes ?: MAX_BYTES)

    fun identity(input: InputStream): Identity {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        var bytes=0L
        while(true) {
            val size=input.read(buffer);if(size<0)break
            check(size>0);bytes+=size;require(bytes<=MAX_BYTES);digest.update(buffer,0,size)
        }
        return Identity(bytes,digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)})
    }

    fun matches(input: InputStream, expected: Identity): Boolean {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        var bytes=0L
        while(true) {
            val size=input.read(buffer,0,minOf(buffer.size.toLong(),expected.bytes+1-bytes).toInt())
            if(size<0)break
            check(size>0);bytes+=size;if(bytes>expected.bytes)return false
            digest.update(buffer,0,size)
        }
        return bytes==expected.bytes && digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}==expected.sha256
    }

    fun decide(status: Status, cancelled: () -> Boolean, deleteAndConfirm: () -> Boolean): Decision {
        if(status==Status.UNAVAILABLE)return Decision.DEFER
        if(status==Status.MISSING)return if(cancelled())Decision.DISCARD else Decision.RETRY
        if(status==Status.COMPLETE && !cancelled())return Decision.ADOPT
        if(!deleteAndConfirm())return Decision.DEFER
        return if(cancelled())Decision.DISCARD else Decision.RETRY
    }
}
