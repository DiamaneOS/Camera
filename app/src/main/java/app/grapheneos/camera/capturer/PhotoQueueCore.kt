package app.grapheneos.camera.capturer

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The durable photo queue: job states, the journal, the budget, recovery and publication
 * decisions. Files, providers, the processor and notifications sit behind [Io], so the whole
 * lifecycle also runs on the JVM with fakes.
 */
internal class PhotoQueueCore<D : PhotoQueueCore.Data>(
    private val io: Io<D>, private val worker: Executor, private val cleanup: Executor,
) {
    interface Data {
        val capturedAt: Long
        val dateString: String
        /** The folder chosen when the photo was taken. */
        val storage: String
        /** A completed journal that still carries data only processing needed. */
        val compactable: Boolean
    }

    /** What the journal stores. [destination] replaces the captured folder once the user moved the photo. */
    data class Journal<D>(
        val id: String, val data: D, val state: String, val bytes: Long, val publication: String?,
        val attempts: Int, val identity: PublicationRecovery.Identity?, val destination: String?,
        val portraitPrepared: Boolean, val destinationLost: Boolean,
    )

    class UnreadableJournal(cause: Throwable? = null) : Exception(cause)

    interface Io<D : Data> {
        val defaultStorage: String
        /** Job directories by name, at most 64. */
        fun jobIds(): List<String>
        fun hasJournal(id: String): Boolean
        /** Throws [UnreadableJournal] for content that cannot be decoded, anything else for I/O. */
        fun readJournal(id: String): Journal<D>
        fun writeJournal(journal: Journal<D>)
        /** Keeps an unreadable journal aside and removes the job's private inputs. */
        fun setAside(id: String)
        fun deleteJob(id: String)
        fun discardInputs(id: String)
        fun inputsValid(id: String, data: D): Boolean
        fun freeBytes(): Long

        /** Cancellation tombstones last rechecked before [before], oldest first, at most 64. */
        fun tombstoneIds(before: Long): List<String>
        fun readTombstone(id: String): Journal<D>
        fun writeTombstone(journal: Journal<D>)
        fun deleteTombstone(id: String)
        fun touchTombstone(id: String)

        fun publicationStatus(publication: Publication): PublicationRecovery.Status
        /** Deletes a photo this job created. True when the provider confirmed it. */
        fun deletePublication(uri: String): Boolean
        /** A row created just before a crash that holds exactly this photo; other pending rows are deleted. */
        fun adoptableOrphan(dateString: String, identity: PublicationRecovery.Identity): String?
        /** Whether the folder can take new photos: present, answering and still granted. */
        fun destinationAvailable(storage: String): Boolean
        /** The camera stops saving to a lost folder: its setting returns to the default location. */
        fun forgetDestination(storage: String)

        /** Runs the processor and writes the result. [prepared] persists Portrait editing data first. */
        fun process(id: String, data: D, stopped: () -> Boolean, prepared: () -> Unit): PublicationRecovery.Identity
        /** Publishes the result. [created] runs before any byte is written, [removed] for a deleted partial. */
        fun save(id: String, data: D, storage: String, stopped: () -> Boolean,
                 created: (String) -> Unit, removed: (String) -> Unit): String

        fun beginLifetime(interrupt: () -> Unit): String
        fun renewLifetime()
        fun endLifetime(token: String)

        fun portraitCommit(id: String, uri: String)
        fun portraitDiscard(id: String)
        fun portraitRemove(uri: String)
        fun purgePortraits()

        fun changed()
        fun failureNotification()
        fun clearFailureNotification()
        fun storageFull()
        fun destinationLost()
        fun log(message: String, error: Throwable)
    }

    class Publication(val uri: String, val storage: String, val expectedName: String,
                      val identity: PublicationRecovery.Identity?, val cancelled: Boolean, val inputsValid: () -> Boolean)

    data class Reservation(val id: String, val bytes: Long)

    sealed class Lookup {
        object Unknown : Lookup()
        object Cancelled : Lookup()
        data class Ready(val uri: String, val dateString: String) : Lookup()
        data class Pending(val id: String, val dateString: String, val failed: Boolean) : Lookup()
    }

    enum class RetryResult { STARTED, REFUSED, QUEUE_FULL }

    private class Job<D : Data>(val id: String, val data: D) {
        var state = QUEUED
        var bytes = 0L
        var ready: String? = null
        var publication: String? = null
        var identity: PublicationRecovery.Identity? = null
        val publicationLock = Any()
        var lifetime: String? = null
        var scheduled = false
        var running = false
        /** Automatic processing attempts since the capture or the user's last Retry. */
        var attempts = 0
        /** The lifetime ended before this job started: it stays queued for the next foreground. */
        var deferred = false
        var destination: String? = null
        var portraitPrepared = false
        /** The folder was missing or no longer granted: only the user's choice continues. */
        var destinationLost = false
        val cancelled = AtomicBoolean()
        val interrupted = AtomicBoolean()
        fun stopped() = cancelled.get() || interrupted.get()
        fun storage() = destination ?: data.storage
    }

    private val lock = Any()
    private val budget = PhotoQueueBudget()
    private val records = linkedMapOf<String, Job<D>>()
    private var resumeRequested = false // guarded by lock
    @Volatile var isRecovering = false
        private set

    /** Reads every journal. Jobs become visible once their journal matches their state. */
    fun recover() {
        synchronized(lock) { isRecovering = true }
        cleanup.execute {
            for (id in io.jobIds()) {
                try { recoverJob(id) } catch (error: Throwable) {
                    // A partial acquisition is never advertised as a completed image.
                    io.log("Cannot recover private photo job", error)
                    if (lowStorage()) io.storageFull()
                }
            }
            isRecovering = false
            // Only a visible Activity resumes work: a background start cannot run the
            // foreground service and would mark the jobs failed.
            changed()
            if (synchronized(lock) { resumeRequested.also { resumeRequested = false } }) resume()
        }
    }

    private fun recoverJob(id: String) {
        if (!io.hasJournal(id)) { io.deleteJob(id); return }
        val journal = try { io.readJournal(id) } catch (error: UnreadableJournal) {
            try { io.setAside(id) } finally { io.failureNotification() }
            throw error
        }
        require(journal.id == id)
        val job = Job(id, journal.data).apply {
            bytes = journal.bytes; attempts = journal.attempts.coerceAtLeast(0)
            publication = journal.publication; identity = journal.identity; destination = journal.destination
            portraitPrepared = journal.portraitPrepared; destinationLost = journal.destinationLost
        }
        val savedState = journal.state
        if (savedState == CANCELLED) {
            job.cancelled.set(true)
            cleanupCancelled(job)
            return
        }
        val disposition = if (savedState == COMPLETE && job.publication != null) PublicationRecovery.Decision.ADOPT
            else reconcilePublication(job)
        val published = job.publication?.takeIf { disposition == PublicationRecovery.Decision.ADOPT }
        if (published != null) {
            job.ready = published
            job.state = COMPLETE
        } else {
            job.state = if (disposition == PublicationRecovery.Decision.DEFER || savedState == FAILED ||
                !io.inputsValid(id, job.data)) FAILED else QUEUED
            require(job.bytes in 1..MAX_JOB_BYTES)
            // A failed job waits for the user without holding one of the queue's slots.
            check(if (job.state == FAILED) budget.reserveParked(id, job.bytes) || budget.reserve(id, job.bytes)
                else budget.reserve(id, job.bytes))
        }
        // A completed journal is already durable. Rewriting every completed
        // capture here performed dozens of fsyncs on Application.onCreate.
        if (savedState != job.state || savedState != COMPLETE) {
            try { persist(job) } catch (error: Throwable) { budget.release(id); throw error }
        }
        synchronized(lock) { records[id] = job }
        if (job.state == COMPLETE && job.data.compactable) cleanup.execute {
            val retained = synchronized(lock) { records[id] === job && job.state == COMPLETE }
            if (retained) try { persist(job) } catch (_: Exception) { }
        }
        if (published != null && job.portraitPrepared) cleanup.execute {
            try { io.portraitCommit(id, published) } catch (_: Exception) { }
        }
        if (job.state == COMPLETE) io.discardInputs(id)
    }

    fun reserve(bytes: Long): Reservation? = synchronized(lock) {
        if (isRecovering) return@synchronized null
        if (io.freeBytes() < bytes + MIN_FREE) return@synchronized null
        val id = UUID.randomUUID().toString()
        if (budget.reserve(id, bytes)) Reservation(id, bytes) else null
    }

    /** A reservation that already became a queued job keeps its budget with the job. */
    fun release(reservation: Reservation) {
        if (synchronized(lock) { records.containsKey(reservation.id) }) return
        budget.release(reservation.id)
    }

    /** Whether a reservation of this size fails for storage rather than a full queue. */
    fun lacksStorage(bytes: Long): Boolean = try { io.freeBytes() < bytes + MIN_FREE } catch (_: Exception) { true }

    /**
     * Takes over inputs already written to the reservation's directory. On failure the caller
     * deletes that directory and releases the reservation.
     */
    fun accept(reservation: Reservation, data: D, bytes: Long, cancelled: () -> Boolean) {
        require(bytes in 1..reservation.bytes)
        val job = Job(reservation.id, data).apply { this.bytes = bytes }
        persist(job)
        check(!cancelled())
        budget.commit(job.id, bytes)
        synchronized(lock) { records[job.id] = job }
        try { start(job) } catch (error: Throwable) { io.log("Cannot schedule captured photo", error) }
    }

    fun resume() {
        if (synchronized(lock) { if (isRecovering) resumeRequested = true; isRecovering }) return
        synchronized(lock) { records.values.filter { it.state == QUEUED && !it.scheduled }.toList() }.forEach(::start)
        cleanup.execute { io.purgePortraits() }
        cleanup.execute { cleanupCancelledTombstones() }
    }

    private fun start(job: Job<D>) {
        synchronized(lock) {
            if (job.scheduled || job.stopped() || job.state != QUEUED) return
            // A job that keeps killing the worker stops being retried by itself.
            if (!PhotoQueueAttempts.mayStart(job.attempts)) {
                fail(job)
                io.failureNotification(); changed(); return
            }
            job.scheduled = true
        }
        try {
            job.lifetime = io.beginLifetime { interrupt(job) }
            worker.execute {
                try { process(job) } catch (error: Throwable) { io.log("Photo worker stopped", error) }
            }
        } catch (error: Exception) {
            synchronized(lock) { job.scheduled = false; fail(job) }
            job.lifetime?.let(io::endLifetime); job.lifetime = null
            io.failureNotification(); changed()
        }
        changed()
    }

    /** Lock held. A failed job that is not running waits for the user outside the queue's slots. */
    private fun fail(job: Job<D>) {
        job.state = FAILED
        persistQuietly(job)
        if (!job.running) budget.park(job.id)
    }

    private fun process(job: Job<D>) {
        var published: String? = null
        try {
            val deferred = synchronized(lock) {
                if (job.deferred) { job.deferred = false; true } else {
                    check(!job.stopped())
                    job.running = true; job.state = PROCESSING; job.attempts++; persist(job); false
                }
            }
            if (deferred) return
            io.renewLifetime()
            when (reconcilePublication(job)) {
                PublicationRecovery.Decision.ADOPT -> {
                    check(!job.stopped())
                    published = checkNotNull(job.publication)
                    finishPublication(job, published)
                    return
                }
                PublicationRecovery.Decision.DEFER -> {
                    checkDestination(job)
                    error("Photo destination is temporarily unavailable")
                }
                PublicationRecovery.Decision.DISCARD -> error("Photo publication was cancelled")
                PublicationRecovery.Decision.RETRY -> Unit
            }
            // Before any processing: a folder that is gone or no longer granted stops here.
            checkDestination(job)
            check(io.inputsValid(job.id, job.data))
            check(!job.stopped())
            val identity = io.process(job.id, job.data, job::stopped) {
                synchronized(lock) { job.portraitPrepared = true; persist(job) }
            }
            check(!job.stopped())
            synchronized(lock) { job.identity = identity; persist(job) }
            published = io.save(job.id, job.data, job.storage(), job::stopped,
                { uri -> synchronized(lock) { job.publication = uri; persist(job) } },
                { uri -> clearPublication(job, uri) })
            finishPublication(job, published)
        } catch (error: Exception) {
            val failed = synchronized(lock) {
                if (job.cancelled.get()) false
                else if (published != null) { job.ready = published; job.state = COMPLETE; persistQuietly(job); false }
                else { job.state = FAILED; persistQuietly(job); true }
            }
            if (failed) {
                io.log("Unable to finish captured photo", error)
                if (job.destinationLost) io.destinationLost() else io.failureNotification()
            }
        } finally {
            synchronized(lock) {
                job.running = false; job.scheduled = false
                if (job.state == FAILED && !job.cancelled.get()) budget.park(job.id)
            }
            if (job.cancelled.get()) {
                cleanupCancelled(job)
            } else if (job.state == COMPLETE) {
                io.discardInputs(job.id); budget.release(job.id)
            }
            job.lifetime?.let(io::endLifetime); job.lifetime = null
            prune(); changed()
        }
    }

    /** Marks a missing or revoked folder durably, so no automatic attempt runs again. */
    private fun checkDestination(job: Job<D>) {
        val storage = job.storage()
        if (storage == io.defaultStorage || io.destinationAvailable(storage)) return
        synchronized(lock) { job.destinationLost = true; persistQuietly(job) }
        try { io.forgetDestination(storage) } catch (error: Exception) { io.log("Cannot reset the storage location", error) }
        error("Photo destination is unavailable")
    }

    private fun interrupt(job: Job<D>) {
        synchronized(lock) {
            if (job.state == COMPLETE || job.cancelled.get()) return
            if (job.interrupted.get()) return
            // The service timed out before this job ran: keep it queued, never fail it unseen.
            if (!job.running && job.state == QUEUED) { job.deferred = true; return }
            job.interrupted.set(true)
            job.state = FAILED
            persistQuietly(job)
        }
        changed()
    }

    /** Returns a completed photo when deletion raced with publication; otherwise cancels this job. */
    fun cancel(id: String): Lookup.Ready? = cancelJob(id, false)

    private fun cancelJob(id: String, includeCompleted: Boolean): Lookup.Ready? {
        val job = synchronized(lock) {
            val job = records[id] ?: return null
            if (!includeCompleted) job.ready?.let { return Lookup.Ready(it, job.data.dateString) }
            val previousReady = job.ready
            val previousState = job.state
            job.ready = null
            job.state = CANCELLED
            try { persist(job) } catch (error: Exception) {
                job.ready = previousReady; job.state = previousState
                throw error
            }
            job.cancelled.set(true)
            if (!job.running) records.remove(id)
            job
        }
        if (!job.running) cleanup.execute {
            cleanupCancelled(job)
            job.lifetime?.let(io::endLifetime); job.lifetime = null
        }
        changed()
        return null
    }

    fun abandon(id: String) { cancelJob(id, true) }

    fun forgetPublished(uri: String) {
        cleanup.execute { io.portraitRemove(uri) }
        val ids = synchronized(lock) { records.values.filter { it.ready == uri }.map { it.id } }
        ids.forEach(::forgetCompleted)
    }

    fun forgetCompleted(id: String) {
        val removed = synchronized(lock) { records[id]?.takeIf { it.ready != null }?.let { records.remove(id) } }
        removed?.let { cleanup.execute { io.deleteJob(it.id) } }
        changed()
    }

    fun retry(id: String): RetryResult = restart(id, moveToDefault = false)

    /** The user's choice for a photo whose folder is gone: publish it to the default location. */
    fun moveToDefault(id: String): RetryResult = restart(id, moveToDefault = true)

    private fun restart(id: String, moveToDefault: Boolean): RetryResult {
        val job = synchronized(lock) {
            val job = records[id] ?: return RetryResult.REFUSED
            if (job.state != FAILED || job.running || (!io.inputsValid(id, job.data) && job.publication == null)) return RetryResult.REFUSED
            // A journaled output elsewhere is never replaced by a second copy.
            if (moveToDefault && (!job.destinationLost || job.publication != null)) return RetryResult.REFUSED
            if (!budget.unpark(id)) return RetryResult.QUEUE_FULL
            val previous = job.destination
            val previousLost = job.destinationLost
            job.cancelled.set(false); job.interrupted.set(false); job.state = QUEUED; job.attempts = 0
            job.destinationLost = false
            if (moveToDefault) job.destination = io.defaultStorage
            // One journal write carries the new folder and the queued state together.
            if (!persistQuietly(job)) {
                job.state = FAILED; job.destination = previous; job.destinationLost = previousLost
                budget.park(id)
                return RetryResult.REFUSED
            }
            job
        }
        start(job)
        return RetryResult.STARTED
    }

    fun lookup(id: String): Lookup = synchronized(lock) {
        val job = records[id] ?: return Lookup.Unknown
        if (job.cancelled.get()) Lookup.Cancelled
        else job.ready?.let { Lookup.Ready(it, job.data.dateString) } ?: pending(job)
    }

    fun pendingItems(): List<Lookup.Pending> = synchronized(lock) {
        records.values.filter { it.ready == null && !it.cancelled.get() }.map(::pending)
    }

    fun has(id: String): Boolean = synchronized(lock) { records.containsKey(id) }

    /** File checks run outside the queue lock, which journal fsyncs can hold for a while. */
    fun canRetry(id: String): Boolean {
        val job = synchronized(lock) { records[id]?.takeIf { it.state == FAILED } } ?: return false
        return job.publication != null || io.inputsValid(id, job.data)
    }

    fun canMoveToDefault(id: String): Boolean {
        val job = synchronized(lock) { records[id]?.takeIf { it.state == FAILED && it.destinationLost && it.publication == null } }
            ?: return false
        return io.inputsValid(id, job.data)
    }

    private fun pending(job: Job<D>) = Lookup.Pending(job.id, job.data.dateString, job.state == FAILED)

    private fun changed() {
        io.changed()
        if (synchronized(lock) { records.values.none { it.state == FAILED && !it.cancelled.get() } }) io.clearFailureNotification()
    }

    private fun finishPublication(job: Job<D>, uri: String) {
        if (job.portraitPrepared) io.portraitCommit(job.id, uri)
        synchronized(lock) {
            check(!job.stopped()); job.ready = uri; job.state = COMPLETE
            try { persist(job) } catch (error: Exception) { io.log("Photo saved; journal update deferred", error) }
        }
    }

    private fun journal(job: Job<D>) = Journal(job.id, job.data, job.state, job.bytes, job.publication, job.attempts,
        job.identity, job.destination, job.portraitPrepared, job.destinationLost)

    private fun persist(job: Job<D>) = io.writeJournal(journal(job))

    /**
     * Journal writes on failure paths. A full disk must not turn a failed photo into a crash
     * loop: the in-memory state stays authoritative and the write is retried with the next change.
     */
    private fun persistQuietly(job: Job<D>): Boolean = JournalWrite.quietly({ persist(job) }) { error ->
        io.log("Cannot write photo journal", error)
        if (lowStorage()) io.storageFull()
    }

    private fun publication(job: Job<D>, uri: String) = Publication(uri, job.storage(),
        "IMG_" + job.data.dateString + ".jpg", job.identity, job.cancelled.get()) { io.inputsValid(job.id, job.data) }

    /**
     * The process can die after the provider created the row but before the journal recorded it.
     * Adopt such a row if it holds exactly the finished photo.
     */
    private fun adoptOrphan(job: Job<D>) {
        val expected = job.identity ?: return
        if (job.publication != null || job.storage() != io.defaultStorage) return
        val uri = try { io.adoptableOrphan(job.data.dateString, expected) } catch (_: Exception) { null } ?: return
        synchronized(lock) { job.publication = uri; persistQuietly(job) }
    }

    private fun reconcilePublication(job: Job<D>): PublicationRecovery.Decision = synchronized(job.publicationLock) {
        adoptOrphan(job)
        val uri = job.publication ?: return@synchronized if (job.cancelled.get()) PublicationRecovery.Decision.DISCARD
            else PublicationRecovery.Decision.RETRY
        val status = io.publicationStatus(publication(job, uri))
        val decision = PublicationRecovery.decide(status, job.cancelled::get) {
            try { io.deletePublication(uri) || io.publicationStatus(publication(job, uri)) == PublicationRecovery.Status.MISSING }
            catch (_: Exception) { false }
        }
        if (decision in listOf(PublicationRecovery.Decision.RETRY, PublicationRecovery.Decision.DISCARD)) clearPublication(job, uri)
        decision
    }

    private fun clearPublication(job: Job<D>, uri: String) = synchronized(lock) {
        if (job.publication != uri) return@synchronized
        val identity = job.identity
        job.publication = null; job.identity = null
        try { persist(job) } catch (error: Exception) { job.publication = uri; job.identity = identity; throw error }
    }

    private fun cleanupCancelled(job: Job<D>) = synchronized(job.publicationLock) {
        try {
            io.portraitDiscard(job.id)
            job.publication?.let(io::portraitRemove)
            val decision = reconcilePublication(job)
            if (decision != PublicationRecovery.Decision.DISCARD) {
                // Persist a small cancellation tombstone before deleting heavy
                // private inputs. It never re-enters the processing queue.
                io.writeTombstone(journal(job).copy(state = CANCELLED))
            }
            io.deleteJob(job.id)
            synchronized(lock) { records.remove(job.id) }; budget.release(job.id)
        } catch (error: Exception) {
            // Keep the original journal and URI if even tombstone persistence
            // fails. A later launch can retry without inventing another output.
            io.log("Cancelled publication cleanup deferred", error)
            synchronized(lock) { records.remove(job.id) }; budget.release(job.id)
        }
    }

    private fun cleanupCancelledTombstones(now: Long = System.currentTimeMillis()) {
        // Each tombstone is rechecked at most hourly, and given up after 30 days.
        io.tombstoneIds(now - 60 * 60 * 1000L).forEach { id ->
            try {
                val journal = io.readTombstone(id)
                require(journal.id == id && journal.state == CANCELLED)
                val job = Job(id, journal.data).apply {
                    state = CANCELLED; cancelled.set(true)
                    publication = journal.publication; identity = journal.identity; destination = journal.destination
                }
                if (reconcilePublication(job) == PublicationRecovery.Decision.DISCARD ||
                    now - job.data.capturedAt > 30L * 24 * 60 * 60 * 1000) io.deleteTombstone(id)
                else io.touchTombstone(id)
            } catch (_: Exception) { io.touchTombstone(id) }
        }
    }

    private fun prune() {
        val old = synchronized(lock) {
            records.values.filter { it.state == COMPLETE }.sortedByDescending { it.data.capturedAt }.drop(32)
                .also { values -> values.forEach { records.remove(it.id) } }
        }
        old.forEach { io.deleteJob(it.id) }
    }

    private fun lowStorage(): Boolean = try { io.freeBytes() < MIN_FREE } catch (_: Exception) { true }

    companion object {
        const val QUEUED = "queued"
        const val PROCESSING = "processing"
        const val COMPLETE = "complete"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        const val MIN_FREE = 256L * 1024 * 1024
        const val MAX_JOB_BYTES = 240L * 1024 * 1024 + 1024 * 1024 + RawShadingData.MAX_BYTES
    }
}
