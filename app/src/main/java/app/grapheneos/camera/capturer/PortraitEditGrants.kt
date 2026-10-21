package app.grapheneos.camera.capturer

import java.security.SecureRandom

/** Bounded one-use authorization tied to a caller and one selected photo. */
internal class PortraitEditGrants(private val clock: () -> Long) {
    private data class Grant(val uid: Int,val uri: String,val expires: Long)
    private val grants=linkedMapOf<String,Grant>()
    @Synchronized fun issue(uid: Int,uri: String): String {
        require(uri.length in 1..4096)
        val now=clock();grants.entries.removeAll {it.value.expires<now}
        // One caller cannot crowd out the others: it keeps at most PER_CALLER open grants.
        grants.entries.filter {it.value.uid==uid}.dropLast(PER_CALLER-1).forEach {grants.remove(it.key)}
        while(grants.size>=64) grants.remove(grants.keys.first())
        val token=ByteArray(24).also {SecureRandom().nextBytes(it)}.joinToString("") {"%02x".format(it.toInt() and 255)}
        grants[token]=Grant(uid,uri,now+120_000);return token
    }
    companion object { const val PER_CALLER=8 }
    @Synchronized fun consume(token: String?,uid: Int,uri: String): Boolean {
        if(token==null || token.length!=48) return false
        val grant=grants.remove(token) ?: return false
        return grant.uid==uid && grant.uri==uri && grant.expires>=clock()
    }
}
