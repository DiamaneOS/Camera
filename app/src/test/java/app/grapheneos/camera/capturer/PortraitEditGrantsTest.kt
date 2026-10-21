package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class PortraitEditGrantsTest {
    @Test fun grantsAreBoundToCallerAndSelectedPhoto() {
        val grants=PortraitEditGrants {0}
        assertFalse(grants.consume(grants.issue(10,"photo-a"),11,"photo-a"))
        assertFalse(grants.consume(grants.issue(10,"photo-a"),10,"photo-b"))
        assertFalse(grants.consume("invalid",10,"photo-a"))
    }
    @Test fun validGrantCanBeUsedOnlyOnce() {
        val grants=PortraitEditGrants {0};val token=grants.issue(10,"photo-a")
        assertTrue(grants.consume(token,10,"photo-a"));assertFalse(grants.consume(token,10,"photo-a"))
    }
    @Test fun expiredGrantsFailClosed() {
        var now=0L;val grants=PortraitEditGrants {now};val token=grants.issue(10,"photo-a")
        now=120_001;assertFalse(grants.consume(token,10,"photo-a"))
    }
    @Test fun memoryIsBoundedWhenCallersDoNotUseGrants() {
        val grants=PortraitEditGrants {0};val old=grants.issue(10,"old")
        repeat(64) {grants.issue(10,"photo-$it")}
        assertFalse(grants.consume(old,10,"old"))
    }
    @Test fun oneCallerKeepsAtMostItsOwnLimit() {
        val grants=PortraitEditGrants {0};val other=grants.issue(11,"other")
        val first=grants.issue(10,"first")
        repeat(PortraitEditGrants.PER_CALLER) {grants.issue(10,"photo-$it")}
        assertFalse(grants.consume(first,10,"first"))
        assertTrue(grants.consume(other,11,"other"))
    }
}
