package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/** End to end runs of the photo queue over a fake disk, provider and processor. */
class PhotoQueueCoreTest {
    private data class Photo(override val capturedAt: Long, override val storage: String) : PhotoQueueCore.Data {
        override val dateString = "20261021_0600%02d_000".format(capturedAt % 60)
        override val compactable = false
    }

    private class Row(val name: String, var content: String, var pending: Boolean, val folder: String)

    /** Process death: an Error that the queue's own failure handling does not catch. */
    private class Death : Error()

    private class FakeIo : PhotoQueueCore.Io<Photo> {
        override val defaultStorage = ""
        val journals = linkedMapOf<String, PhotoQueueCore.Journal<Photo>>()
        val history = arrayListOf<PhotoQueueCore.Journal<Photo>>()
        val unreadable = hashSetOf<String>()
        val setAside = hashSetOf<String>()
        val inputs = hashSetOf<String>()
        val tombstones = linkedMapOf<String, PhotoQueueCore.Journal<Photo>>()
        var free = 8L shl 30
        var diskFull = false
        val rows = linkedMapOf<String, Row>()
        private var nextRow = 1
        val granted = hashSetOf<String>()
        var processed = 0
        var processFails = false
        /** Where save dies: "before-journal" after creating the row, "after-bytes" once it is written. */
        var crash: String? = null
        var failureNotifications = 0
        var lostNotifications = 0
        var storageFullReports = 0

        override fun jobIds() = (journals.keys + inputs + unreadable).distinct().sorted()
        override fun hasJournal(id: String) = id in journals || id in unreadable
        override fun readJournal(id: String): PhotoQueueCore.Journal<Photo> {
            if (id in unreadable) throw PhotoQueueCore.UnreadableJournal()
            return journals.getValue(id)
        }
        override fun writeJournal(journal: PhotoQueueCore.Journal<Photo>) {
            if (diskFull) throw IOException("No space left on device")
            journals[journal.id] = journal; history.add(journal)
        }
        override fun setAside(id: String) { setAside.add(id); unreadable.remove(id); inputs.remove(id) }
        override fun deleteJob(id: String) { journals.remove(id); inputs.remove(id) }
        override fun discardInputs(id: String) { inputs.remove(id) }
        override fun inputsValid(id: String, data: Photo) = id in inputs
        override fun freeBytes() = free
        override fun tombstoneIds(before: Long) = tombstones.keys.toList()
        override fun readTombstone(id: String) = tombstones.getValue(id)
        override fun writeTombstone(journal: PhotoQueueCore.Journal<Photo>) { tombstones[journal.id] = journal }
        override fun deleteTombstone(id: String) { tombstones.remove(id) }
        override fun touchTombstone(id: String) { }

        fun content(id: String) = "photo $id"
        fun identity(content: String) = PublicationRecovery.identity(content.byteInputStream())

        override fun publicationStatus(publication: PhotoQueueCore.Publication): PublicationRecovery.Status {
            val row = rows[publication.uri] ?: return PublicationRecovery.Status.MISSING
            if (row.folder != defaultStorage && row.folder !in granted) return PublicationRecovery.Status.UNAVAILABLE
            val matches = publication.identity?.let { PublicationRecovery.matches(row.content.byteInputStream(), it) } == true
            return when {
                matches && !row.pending -> PublicationRecovery.Status.COMPLETE
                matches && publication.cancelled -> PublicationRecovery.Status.INCOMPLETE
                matches -> { row.pending = false; PublicationRecovery.Status.COMPLETE }
                row.name != publication.expectedName -> PublicationRecovery.Status.UNAVAILABLE
                !row.pending -> PublicationRecovery.Status.COMPLETE
                else -> PublicationRecovery.Status.INCOMPLETE
            }
        }
        override fun deletePublication(uri: String) = rows.remove(uri) != null
        override fun adoptableOrphan(dateString: String, identity: PublicationRecovery.Identity): String? {
            for ((uri, row) in rows.entries.toList()) {
                if (row.name != "IMG_$dateString.jpg" || row.folder != defaultStorage) continue
                if (PublicationRecovery.matches(row.content.byteInputStream(), identity)) return uri
                if (row.pending) rows.remove(uri)
            }
            return null
        }
        override fun destinationAvailable(storage: String) = storage in granted

        override fun process(id: String, data: Photo, stopped: () -> Boolean, prepared: () -> Unit): PublicationRecovery.Identity {
            processed++
            if (processFails) throw IOException("Cannot write result")
            return identity(content(id))
        }

        override fun save(id: String, data: Photo, storage: String, stopped: () -> Boolean,
                          created: (String) -> Unit, removed: (String) -> Unit): String {
            check(storage == defaultStorage || storage in granted)
            val uri = "content://row/" + nextRow++
            rows[uri] = Row("IMG_${data.dateString}.jpg", "", true, storage)
            if (crash == "before-journal") throw Death()
            created(uri)
            rows.getValue(uri).content = content(id)
            if (crash == "after-bytes") throw Death()
            rows.getValue(uri).pending = false
            return uri
        }

        override fun beginLifetime(interrupt: () -> Unit) = "lifetime"
        override fun renewLifetime() { }
        override fun endLifetime(token: String) { }
        override fun portraitCommit(id: String, uri: String) { }
        override fun portraitDiscard(id: String) { }
        override fun portraitRemove(uri: String) { }
        override fun purgePortraits() { }
        override fun changed() { }
        override fun failureNotification() { failureNotifications++ }
        override fun clearFailureNotification() { }
        override fun storageFull() { storageFullReports++ }
        override fun destinationLost() { lostNotifications++ }
        override fun log(message: String, error: Throwable) { }
    }

    private val direct = Executor { it.run() }
    /** A worker whose deaths are part of the scenario, as a killed process would be. */
    private val dying = Executor { try { it.run() } catch (_: Death) { } }

    private fun queue(io: FakeIo, worker: Executor = direct) = PhotoQueueCore(io, worker, direct).apply { recover() }

    private fun capture(queue: PhotoQueueCore<Photo>, io: FakeIo, at: Long, storage: String = ""): String {
        val reservation = assertNotNullAndGet(queue.reserve(1000))
        io.inputs.add(reservation.id)
        queue.accept(reservation, Photo(at, storage), 900) { false }
        return reservation.id
    }

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }

    private fun published(queue: PhotoQueueCore<Photo>, id: String) = queue.lookup(id) as? PhotoQueueCore.Lookup.Ready

    @Test fun aPhotoIsPublishedOnceAndItsInputsAndSlotAreFreed() {
        val io = FakeIo(); val queue = queue(io)
        val id = capture(queue, io, 1)
        val ready = assertNotNullAndGet(published(queue, id))
        assertEquals(1, io.rows.size)
        assertFalse(io.rows.getValue(ready.uri).pending)
        assertEquals(PhotoQueueCore.COMPLETE, io.journals.getValue(id).state)
        assertFalse(id in io.inputs)
        repeat(8) { assertNotNull(queue.reserve(1000)) }
    }

    @Test fun aFullDiskRefusesNewPhotosBeforeAcquisition() {
        val io = FakeIo(); val queue = queue(io)
        io.free = PhotoQueueCore.MIN_FREE + 500
        assertNull(queue.reserve(1000))
        assertTrue(queue.lacksStorage(1000))
    }

    @Test fun aFullDiskDuringProcessingFailsThePhotoWithoutACrashLoop() {
        val io = FakeIo(); val queue = queue(io)
        io.processFails = true
        val reservation = assertNotNullAndGet(queue.reserve(1000))
        io.inputs.add(reservation.id)
        // The journal of the accepted job is written, then the disk fills up.
        val accepted = { io.diskFull = true; io.free = 0; false }
        queue.accept(reservation, Photo(2, ""), 900, accepted)
        val pending = queue.lookup(reservation.id) as PhotoQueueCore.Lookup.Pending
        assertTrue(pending.failed)
        assertTrue(io.storageFullReports > 0)
        assertEquals(1, io.failureNotifications)
        // The last durable journal is the queued one: the next launch simply runs it.
        assertEquals(PhotoQueueCore.QUEUED, io.journals.getValue(reservation.id).state)
        io.diskFull = false; io.free = 8L shl 30; io.processFails = false
        val relaunched = queue(io)
        relaunched.resume()
        assertNotNull(published(relaunched, reservation.id))
        assertEquals(1, io.rows.size)
    }

    @Test fun aRowCreatedJustBeforeACrashIsAdoptedNotDuplicated() {
        val io = FakeIo(); val queue = queue(io, dying)
        io.crash = "after-bytes"
        val id = capture(queue, io, 3)
        // Died with the row written but still pending and before the result was journaled as complete.
        assertEquals(1, io.rows.size)
        io.crash = null
        val relaunched = queue(io)
        relaunched.resume()
        val ready = assertNotNullAndGet(published(relaunched, id))
        assertEquals(1, io.rows.size)
        assertFalse(io.rows.getValue(ready.uri).pending)
        assertEquals(1, io.processed)
    }

    @Test fun aRowCreatedButNeverJournaledIsReplacedOnce() {
        val io = FakeIo(); val queue = queue(io, dying)
        io.crash = "before-journal"
        val id = capture(queue, io, 4)
        assertNull(io.journals.getValue(id).publication)
        assertEquals(1, io.rows.size)
        io.crash = null
        val relaunched = queue(io)
        relaunched.resume()
        val ready = assertNotNullAndGet(published(relaunched, id))
        // The empty pending row from before the crash is gone; only the finished photo remains.
        assertEquals(listOf(ready.uri), io.rows.keys.toList())
        assertEquals(2, io.processed)
    }

    @Test fun aCorruptJournalIsSetAsideAndDoesNotBlockOtherPhotos() {
        val io = FakeIo()
        val corrupt = "00000000-0000-0000-0000-00000000000a"
        val id = "00000000-0000-0000-0000-00000000000b"
        io.unreadable.add(corrupt); io.inputs.add(corrupt)
        io.journals[id] = PhotoQueueCore.Journal(id, Photo(5, ""), PhotoQueueCore.QUEUED, 900, null, 0, null, null, false, false)
        io.inputs.add(id)
        val relaunched = queue(io)
        assertEquals(setOf(corrupt), io.setAside)
        assertFalse(corrupt in io.inputs)
        assertEquals(1, io.failureNotifications)
        assertTrue(relaunched.lookup(corrupt) is PhotoQueueCore.Lookup.Unknown)
        relaunched.resume()
        assertNotNull(published(relaunched, id))
        // The unreadable job holds none of the queue's slots.
        repeat(8) { assertNotNull(relaunched.reserve(1000)) }
    }

    @Test fun aRevokedFolderStopsBeforeProcessingAndOffersTheDefaultLocation() {
        val io = FakeIo(); val queue = queue(io)
        val folder = "content://tree/revoked"
        val id = capture(queue, io, 6, folder)
        assertEquals(0, io.processed)
        assertTrue((queue.lookup(id) as PhotoQueueCore.Lookup.Pending).failed)
        assertEquals(1, io.lostNotifications)
        assertEquals(0, io.failureNotifications)
        assertTrue(io.journals.getValue(id).destinationLost)
        // No automatic attempt: neither a resume nor a relaunch touches it.
        queue.resume()
        val relaunched = queue(io)
        relaunched.resume()
        assertEquals(0, io.processed)
        assertTrue(relaunched.canMoveToDefault(id))
        assertEquals(PhotoQueueCore.RetryResult.STARTED, relaunched.moveToDefault(id))
        // The new folder was durable before anything was processed or published.
        val moved = io.history.first { it.id == id && it.destination == "" }
        assertEquals(PhotoQueueCore.QUEUED, moved.state)
        assertFalse(moved.destinationLost)
        val ready = assertNotNullAndGet(published(relaunched, id))
        assertEquals("", io.rows.getValue(ready.uri).folder)
        assertEquals("", io.journals.getValue(id).destination)
    }

    @Test fun aGrantedFolderIsUsedAsBefore() {
        val io = FakeIo(); val queue = queue(io)
        io.granted.add("content://tree/kept")
        val id = capture(queue, io, 7, "content://tree/kept")
        val ready = assertNotNullAndGet(published(queue, id))
        assertEquals("content://tree/kept", io.rows.getValue(ready.uri).folder)
    }

    @Test fun failedPhotosDoNotTakeTheQueueSlots() {
        val io = FakeIo(); val queue = queue(io)
        val failed = (10L until 18L).map { capture(queue, io, it, "content://tree/gone") }
        assertTrue(failed.all { (queue.lookup(it) as PhotoQueueCore.Lookup.Pending).failed })
        val reservations = (0 until 8).map { assertNotNullAndGet(queue.reserve(1000)) }
        assertNull(queue.reserve(1000))
        // With the queue full a retry waits, and the photo stays failed and movable.
        assertEquals(PhotoQueueCore.RetryResult.QUEUE_FULL, queue.moveToDefault(failed[0]))
        assertTrue(queue.canMoveToDefault(failed[0]))
        reservations.forEach(queue::release)
        assertEquals(PhotoQueueCore.RetryResult.STARTED, queue.moveToDefault(failed[0]))
        assertNotNull(published(queue, failed[0]))
    }

    @Test fun failedPhotosFoundAtLaunchAreAllRecovered() {
        val io = FakeIo(); val queue = queue(io)
        val failed = (20L until 30L).map { capture(queue, io, it, "content://tree/gone") }
        val relaunched = queue(io)
        assertTrue(failed.all { (relaunched.lookup(it) as PhotoQueueCore.Lookup.Pending).failed })
        assertNotNull(relaunched.reserve(1000))
    }

    @Test fun aMoveIsRefusedWhileAnOutputInTheOldFolderIsUnresolved() {
        val io = FakeIo()
        io.granted.add("content://tree/later-revoked")
        val queue = queue(io, dying)
        io.crash = "after-bytes"
        val id = capture(queue, io, 8, "content://tree/later-revoked")
        io.crash = null
        // A journaled photo is left in the folder, then the grant is revoked.
        assertNotNull(io.journals.getValue(id).publication)
        io.granted.clear()
        val relaunched = queue(io)
        assertTrue((relaunched.lookup(id) as PhotoQueueCore.Lookup.Pending).failed)
        assertFalse(relaunched.canMoveToDefault(id))
        assertEquals(PhotoQueueCore.RetryResult.REFUSED, relaunched.moveToDefault(id))
    }

    @Test fun aCancelledPhotoIsNeverPublishedByRecovery() {
        val io = FakeIo(); val queue = queue(io, dying)
        io.crash = "after-bytes"
        val id = capture(queue, io, 9)
        io.crash = null
        io.journals[id] = io.journals.getValue(id).copy(state = PhotoQueueCore.CANCELLED,
            publication = io.rows.keys.single())
        queue(io)
        assertTrue(io.rows.isEmpty())
        assertFalse(id in io.journals)
    }
}
