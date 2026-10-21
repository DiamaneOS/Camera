package app.grapheneos.camera.capturer
import org.junit.Assert.*
import org.junit.Test
class MediaPhotoAliasesTest {
    @Test fun aliasesKeepExactRowAndVolume() {
        assertEquals(listOf("content://media/external_primary/images/media/42","content://media/external_primary/file/42"),MediaPhotoAliases.forRow("external_primary","42"))
        assertTrue(MediaPhotoAliases.forRow("external_primary","42").intersect(MediaPhotoAliases.forRow("ABCD-1234","42").toSet()).isEmpty())
        assertTrue(MediaPhotoAliases.forRow("external_primary","42").intersect(MediaPhotoAliases.forRow("external_primary","43").toSet()).isEmpty())
    }
    @Test fun malformedIdentitiesNeverCreateGrantCandidates() {
        for(volume in listOf("","../external","external/images","external?x")) assertTrue(MediaPhotoAliases.forRow(volume,"42").isEmpty())
        for(row in listOf("-1","42/43","42?x","99999999999999999999999")) assertTrue(MediaPhotoAliases.forRow("external_primary",row).isEmpty())
    }
}
