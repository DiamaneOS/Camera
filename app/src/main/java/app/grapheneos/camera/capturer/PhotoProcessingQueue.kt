package app.grapheneos.camera.capturer

import android.app.PendingIntent
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.SharedMemory
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.grapheneos.camera.BuildConfig
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.ITEM_TYPE_IMAGE
import app.grapheneos.camera.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Captured inputs belong to durable gallery jobs, independently of the current camera operation. */
internal object PhotoProcessingQueue {
    const val URI_SCHEME = "camera-processing"
    private const val MAX_PLAN = 256 * 1024
    private const val MIN_FREE = 256L * 1024 * 1024
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    private val budget = PhotoQueueBudget()
    private val records = linkedMapOf<String, Job>()
    private val revision = MutableLiveData(0)
    val changes: LiveData<Int> = revision
    private var version = 0
    private lateinit var context: Context
    private lateinit var root: File

    internal data class Reservation(val id: String, val bytes: Long)
    private class Job(val id: String, val spec: ProcessedPhotoSpec, val plan: JSONObject, val directory: File) {
        var state = "queued"
        var bytes = 0L
        var ready: CapturedItem? = null
        var publication: Uri? = null
        var publicationIdentity: PublicationRecovery.Identity? = null
        val publicationLock = Any()
        var lifetime: String? = null
        var scheduled = false
        var running = false
        /** Automatic processing attempts since the capture or the user's last Retry. */
        var attempts = 0
        /** The lifetime ended before this job started: it stays queued for the next foreground. */
        var deferred = false
        val cancelled = AtomicBoolean()
        val interrupted = AtomicBoolean()
        fun stopped() = cancelled.get() || interrupted.get()
    }

    @Volatile var isRecovering=false
        private set

    fun initialize(app: Context) {
        val existing=synchronized(lock) {
            if (::context.isInitialized) return
            context=app.applicationContext
            root=File(context.filesDir,"photo-processing").apply {check(isDirectory || mkdirs())}
            isRecovering=true
            root.listFiles()?.filter {it.isDirectory && validId(it.name)}?.sortedBy {it.name}?.take(64).orEmpty()
        }
        cleanup.execute {
        existing.forEach { directory ->
            try {
                require(directory.canonicalFile.parentFile == root.canonicalFile)
                val file = File(directory, "request.json")
                if (!file.exists() && !File(file.path + ".bak").exists()) { directory.deleteRecursively(); return@forEach }
                require(file.length() in 1..MAX_PLAN || File(file.path + ".bak").length() in 1..MAX_PLAN)
                val json = try {
                    JSONObject(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }).also {
                        require(it.getInt("schema") == 1 && it.getString("id") == directory.name)
                    }
                } catch (error: org.json.JSONException) { unrecoverable(directory, file); throw error }
                catch (error: IllegalArgumentException) { unrecoverable(directory, file); throw error }
                val job = Job(directory.name, ProcessedPhotoSpec.fromJson(json.getJSONObject("spec")), json.getJSONObject("plan"), directory)
                job.bytes = json.getLong("bytes")
                job.attempts = json.optInt("attempts").coerceAtLeast(0)
                job.publication = json.optString("publication").takeIf { it.isNotEmpty() }?.let(Uri::parse)
                job.publicationIdentity = readPublicationIdentity(json)
                val savedState = json.getString("state")
                if (savedState == "cancelled") {
                    job.cancelled.set(true)
                    cleanupCancelled(job)
                    return@forEach
                }
                val disposition = if(savedState=="complete" && job.publication!=null) PublicationRecovery.Decision.ADOPT
                    else reconcilePublication(job)
                val published = job.publication?.takeIf { disposition==PublicationRecovery.Decision.ADOPT }
                if (published != null) {
                    job.ready = CapturedItem(ITEM_TYPE_IMAGE, job.spec.dateString, published)
                    job.state = "complete"
                } else {
                    job.state = if (disposition==PublicationRecovery.Decision.DEFER || savedState == "failed" || !inputsValid(job)) "failed" else "queued"
                    require(job.bytes in 1..(240L * 1024 * 1024 + 1024 * 1024 + RawShadingData.MAX_BYTES))
                    check(budget.reserve(job.id, job.bytes))
                }
                // A completed journal is already durable. Rewriting every completed
                // capture here performed dozens of fsyncs on Application.onCreate.
                // A job only becomes visible once its journal matches its state.
                if (savedState != job.state || savedState != "complete") {
                    try { persist(job) }
                    catch (error: Throwable) { budget.release(job.id); throw error }
                }
                synchronized(lock) {records[job.id] = job}
                if (job.state == "complete" && job.plan.getJSONObject("metadata").has("colorReference")) cleanup.execute {
                    val retained = synchronized(lock) { records[job.id] === job && job.state == "complete" }
                    if (retained) try { persist(job) } catch (_: Exception) { }
                }
                if (published != null && job.plan.optBoolean("portraitPrepared")) cleanup.execute {
                    try { PortraitStore.commit(context, job.id, published) } catch (_: Exception) { }
                }
                if (job.state == "complete") discardInputs(job)
            } catch (error: Throwable) {
                // A partial acquisition is never advertised as a completed image.
                logFailure("Cannot recover private photo job", error)
                if (lowStorage()) storageFull()
            }
        }
        isRecovering=false
        // Only a visible Activity resumes work: a background start cannot run the
        // foreground service and would mark the jobs failed.
        changed();if (synchronized(lock) { resumeRequested.also { resumeRequested=false } }) resume()
        }
    }

    private var resumeRequested=false // guarded by lock

    /** A journal that cannot be read is kept aside, its private inputs removed, and the user told. */
    private fun unrecoverable(directory: File, journal: File) {
        try {
            journal.renameTo(File(directory, "request.json.unreadable"))
            File(journal.path + ".bak").delete()
            directory.listFiles()?.filter { it.name in setOf("source.jpg", "shading.bin", "result.jpg", "portrait.bin") ||
                it.name.matches(Regex("frame-[0-9]{2}\\.bin")) }?.forEach(File::delete)
        } finally { failureNotification() }
    }

    fun reserve(bytes: Long): Reservation? = synchronized(lock) {
        check(::context.isInitialized)
        if(isRecovering) return@synchronized null
        if (StatFs(root.path).availableBytes < bytes + MIN_FREE) return@synchronized null
        val id = UUID.randomUUID().toString()
        if (budget.reserve(id, bytes)) Reservation(id, bytes) else null
    }

    /** A reservation that already became a queued job keeps its budget with the job. */
    fun release(reservation: Reservation) {
        if (synchronized(lock) { records.containsKey(reservation.id) }) return
        budget.release(reservation.id)
    }

    /** Whether a reservation of this size fails for storage rather than a full queue. */
    fun lacksStorage(bytes: Long): Boolean = try { StatFs(root.path).availableBytes < bytes + MIN_FREE } catch (_: Exception) { true }

    @Suppress("DEPRECATION")
    fun enqueue(reservation: Reservation, spec: ProcessedPhotoSpec, payload: Bundle,
                jpeg: File?, preview: Bitmap?, profile: JSONObject?, cancelled: () -> Boolean): CapturedItem {
        val directory = File(root, reservation.id)
        val frames = payload.getParcelableArrayList<SharedMemory>("frames") ?: arrayListOf()
        var accepted = false
        try {
            check(!cancelled())
            if (payload.getInt("mode") == 4) {
                check(BuildConfig.DEBUG && BuildConfig.CAMERA_JPEG_BURST_EVALUATION)
                require(jpeg == null && preview != null && frames.size in 2..4 && !payload.containsKey("rawShading"))
            }
            check(directory.mkdir())
            val metadata = Bundle(payload).apply { remove("frames"); remove("jpeg"); remove("depthModel"); remove("rawShading") }
            val plan = JSONObject().put("metadata", encode(metadata)).put("files", JSONArray())
            val files = plan.getJSONArray("files")
            var bytes = 0L
            if (jpeg != null) {
                require(jpeg.length() in 1..20 * 1024 * 1024)
                val destination = File(directory, "source.jpg")
                check(jpeg.renameTo(destination))
                FileOutputStream(destination, true).use { it.fd.sync() }
                bytes += destination.length()
                files.put(JSONObject().put("name", "source.jpg").put("bytes", destination.length()))
            } else {
                require(frames.size in 2..8)
                frames.forEachIndexed { index, memory ->
                    check(!cancelled()); require(memory.size in 1..32 * 1024 * 1024)
                    val file = File(directory, "frame-%02d.bin".format(java.util.Locale.ROOT, index))
                    val mapping = memory.mapReadOnly()
                    try { FileOutputStream(file).use { output -> while (mapping.hasRemaining()) check(output.channel.write(mapping) > 0); output.fd.sync() } }
                    finally { SharedMemory.unmap(mapping) }
                    bytes += file.length()
                    require(bytes <= 240L * 1024 * 1024)
                    files.put(JSONObject().put("name", file.name).put("bytes", file.length()))
                }
            }
            payload.getParcelable<SharedMemory>("rawShading")?.let { memory ->
                require(jpeg == null && memory.size in 24..RawShadingData.MAX_BYTES)
                val file = File(directory, "shading.bin")
                val mapping = memory.mapReadOnly()
                try { FileOutputStream(file).use { output -> while (mapping.hasRemaining()) check(output.channel.write(mapping) > 0); output.fd.sync() } }
                finally { SharedMemory.unmap(mapping) }
                bytes += file.length()
                files.put(JSONObject().put("name", file.name).put("bytes", file.length()))
            }
            val thumbnail = preview ?: jpegThumbnail(File(directory, "source.jpg"))
            thumbnail?.let {
                try { FileOutputStream(File(directory, "preview.jpg")).use { output -> check(it.compress(Bitmap.CompressFormat.JPEG, 85, output)); output.fd.sync() } }
                finally { if (it !== preview) it.recycle() }
            }
            require(File(directory, "preview.jpg").length() <= 1024 * 1024)
            bytes += File(directory, "preview.jpg").length()
            require(bytes <= reservation.bytes)
            val job = Job(reservation.id, spec, plan, directory).apply { this.bytes = bytes }
            profile?.let { atomic(File(directory, "acquisition.json"), it) }
            persist(job)
            check(!cancelled())
            budget.commit(job.id, bytes)
            synchronized(lock) { records[job.id] = job }
            accepted = true
            try { start(job) } catch (error: Throwable) { logFailure("Cannot schedule captured photo", error) }
            return pending(job)
        } finally {
            if (!accepted) { directory.deleteRecursively(); budget.release(reservation.id) }
        }
    }

    fun resume() {
        if (synchronized(lock) { if (isRecovering) resumeRequested=true; isRecovering }) return
        synchronized(lock) { records.values.filter { it.state == "queued" && !it.scheduled }.toList() }.forEach(::start)
        cleanup.execute { PortraitStore.purgeDeleted(context) }
        cleanup.execute { cleanupCancelledTombstones() }
    }

    private fun start(job: Job) {
        synchronized(lock) {
            if (job.scheduled || job.stopped() || job.state != "queued") return
            // A job that keeps killing the worker stops being retried by itself.
            if (!PhotoQueueAttempts.mayStart(job.attempts)) {
                job.state = "failed"; persistQuietly(job)
                failureNotification(); changed(); return
            }
            job.scheduled = true
        }
        try {
            job.lifetime = PhotoProcessingLifetime.begin(context) { interrupt(job) }
            worker.execute {
                try { process(job) }
                catch (error: Throwable) { logFailure("Photo worker stopped", error) }
            }
        } catch (error: Exception) {
            synchronized(lock) { job.scheduled = false; job.state = "failed"; persistQuietly(job) }
            job.lifetime?.let { PhotoProcessingLifetime.end(context, it) }; job.lifetime = null
            failureNotification(); changed()
        }
        changed()
    }

    private fun process(job: Job) {
        val memories = arrayListOf<SharedMemory>()
        var published: CapturedItem? = null
        try {
            val deferred = synchronized(lock) {
                if (job.deferred) { job.deferred = false; true } else {
                    check(!job.stopped())
                    job.running = true; job.state = "processing"; job.attempts++; persist(job); false
                }
            }
            if (deferred) return
            PhotoProcessingLifetime.renew()
            when(reconcilePublication(job)) {
                PublicationRecovery.Decision.ADOPT -> {
                    check(!job.stopped())
                    published=CapturedItem(ITEM_TYPE_IMAGE,job.spec.dateString,checkNotNull(job.publication))
                    finishPublication(job,published)
                    return
                }
                PublicationRecovery.Decision.DEFER -> error("Photo destination is temporarily unavailable")
                PublicationRecovery.Decision.DISCARD -> error("Photo publication was cancelled")
                PublicationRecovery.Decision.RETRY -> Unit
            }
            check(inputsValid(job))
            val payload = decode(job.plan.getJSONObject("metadata"))
            val files = job.plan.getJSONArray("files")
            if (payload.getInt("mode") == 1 || payload.getInt("mode") == 4) {
                val frames = arrayListOf<SharedMemory>()
                for (index in 0 until files.length()) {
                    val name = files.getJSONObject(index).getString("name")
                    val memory = ProcessingClient.fileMemory("queued-raw", File(job.directory, name),
                        if (name == "shading.bin") RawShadingData.MAX_BYTES.toLong() else 32L * 1024 * 1024)
                    memories.add(memory)
                    if (name == "shading.bin") payload.putParcelable("rawShading", memory) else frames.add(memory)
                }
                payload.putParcelableArrayList("frames", frames)
            } else {
                val jpeg = ProcessingClient.fileMemory("queued-jpeg", File(job.directory, "source.jpg")); memories.add(jpeg)
                payload.putParcelable("jpeg", jpeg)
                payload.putBoolean("depthEnabled", job.spec.depthEnabled)
            }
            check(!job.stopped())
            val stats = Bundle()
            val editData = File(job.directory, "portrait.bin")
            val bytes = ProcessingClient.process(context, payload, job::stopped, stats) { memory ->
                val mapping = memory.mapReadOnly()
                try {
                    PortraitData.decode(mapping)
                    FileOutputStream(editData).use { output ->
                        while (mapping.hasRemaining()) check(output.channel.write(mapping) > 0)
                        output.fd.sync()
                    }
                } finally { SharedMemory.unmap(mapping) }
            }
            check(!job.stopped())
            if (payload.getInt("mode") == 2) {
                PortraitStore.prepare(context, job.id, File(job.directory, "source.jpg"), editData, job.spec)
                synchronized(lock) { job.plan.put("portraitPrepared", true); persist(job) }
            }
            val file = File(job.directory, "result.jpg")
            FileOutputStream(file).use { it.write(ProcessedJpeg.metadata(bytes, job.spec)); it.fd.sync() }
            val identity=file.inputStream().use(PublicationRecovery::identity)
            synchronized(lock) { job.publicationIdentity=identity;persist(job) }
            published = PhotoFileStore.save(context, listOf(PhotoFileStore.Photo(file, ".jpg", "image/jpeg")),
                job.spec.storage, Date(job.spec.capturedAt), job.spec.dateString, job::stopped,
                removedPhoto={uri->clearPublication(job,uri)}) { uri ->
                synchronized(lock) { job.publication = uri; persist(job) }
            }.single()
            finishPublication(job,published)
            if (BuildConfig.DEBUG) {
                val profile = JSONObject().put("mode", payload.getInt("mode")).put("depthEnabled", job.spec.depthEnabled)
                    .put("cameraPermission", stats.getInt("cameraPermission", -999))
                for (key in listOf("processMs", "nativeMs", "decodeMs", "modelMs", "inferenceMs", "depthInferenceMs", "neuralDenoiseMs", "encodeMs", "jpegStagePssKiB")) profile.put(key, stats.getLong(key))
                for (key in listOf("effectiveFrames", "colorErrorBefore", "colorErrorAfter", "colorInliers")) profile.put(key, stats.getDouble(key))
                profile.put("colorMatchApplied", stats.getBoolean("colorMatchApplied"))
                profile.put("edgeAwareDemosaic",stats.getBoolean("edgeAwareDemosaic"))
                profile.put("frequencyDenoise",stats.getBoolean("frequencyDenoise"))
                profile.put("steadyPhotoFinish",stats.getBoolean("steadyPhotoFinish"))
                profile.put("rawShadingApplied",stats.getBoolean("rawShadingApplied"))
                profile.put("jpegRejectedFrames",stats.getDouble("jpegRejectedFrames"))
                profile.put("jpegMaximumShift",stats.getDouble("jpegMaximumShift"))
                try { atomic(File(File(context.filesDir, "processing-test").apply { mkdirs() }, "photo-profile-" + job.spec.capturedAt + ".json"), profile) }
                catch (error: Exception) { Log.w("PhotoProcessingQueue", "Unable to write development profile", error) }
            }
        } catch (error: Exception) {
            val failed = synchronized(lock) {
                if (job.cancelled.get()) false
                else if (published != null) { job.ready = published; job.state = "complete"; persistQuietly(job); false }
                else { job.state = "failed"; persistQuietly(job); true }
            }
            if (failed) {
                logFailure("Unable to finish captured photo", error)
                failureNotification()
            }
        } finally {
            memories.forEach(SharedMemory::close)
            synchronized(lock) { job.running = false; job.scheduled = false }
            if (job.cancelled.get()) {
                cleanupCancelled(job)
            } else if (job.state == "complete") {
                discardInputs(job); budget.release(job.id)
            }
            job.lifetime?.let { PhotoProcessingLifetime.end(context, it) }; job.lifetime = null
            prune(); changed()
        }
    }

    private fun interrupt(job: Job) {
        synchronized(lock) {
            if (job.state == "complete" || job.cancelled.get()) return
            if (job.interrupted.get()) return
            // The service timed out before this job ran: keep it queued, never fail it unseen.
            if (!job.running && job.state == "queued") { job.deferred = true; return }
            job.interrupted.set(true)
            job.state = "failed"
            persistQuietly(job)
        }
        changed()
    }

    /** Returns a completed photo when deletion raced with publication; otherwise cancels this job. */
    fun cancel(id: String): CapturedItem? = cancelJob(id, false)

    private fun cancelJob(id: String, includeCompleted: Boolean): CapturedItem? {
        val job = synchronized(lock) {
            val job = records[id] ?: return null
            if (!includeCompleted) job.ready?.let { return it }
            val previousReady = job.ready
            val previousState = job.state
            job.ready = null
            job.state = "cancelled"
            try { persist(job) }
            catch (error: Exception) {
                job.ready = previousReady; job.state = previousState
                throw error
            }
            job.cancelled.set(true)
            if (!job.running) records.remove(id)
            job
        }
        if (!job.running) cleanup.execute {
            cleanupCancelled(job)
            job.lifetime?.let { PhotoProcessingLifetime.end(context, it) }; job.lifetime = null
        }
        changed()
        return null
    }

    fun forgetPhoto(uri: Uri) {
        cleanup.execute { PortraitStore.remove(context, uri) }
        val ids = synchronized(lock) { records.values.filter { it.ready?.uri == uri }.map { it.id } }
        ids.forEach(::forgetCompleted)
    }

    fun forgetCompleted(id: String) {
        val removed = synchronized(lock) { records[id]?.takeIf { it.ready != null }?.let { records.remove(id) } }
        removed?.let { cleanup.execute { it.directory.deleteRecursively() } }
        changed()
    }

    fun abandon(id: String) {
        try { cancelJob(id, true) }
        catch (error: Exception) {
            logFailure("Cannot persist capture abandonment", error)
            context.mainExecutor.execute {
                android.widget.Toast.makeText(context, R.string.deleting_unexpected_error,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun retry(id: String) {
        val job = synchronized(lock) {
            val job = records[id] ?: return
            if (job.state != "failed" || job.running || (!inputsValid(job) && job.publication==null)) return
            job.cancelled.set(false); job.interrupted.set(false); job.state = "queued"; job.attempts = 0
            if (!persistQuietly(job)) { job.state = "failed"; return }
            job
        }
        start(job)
    }

    fun resolve(item: CapturedItem): CapturedItem? {
        val id = item.processingId ?: return item
        return synchronized(lock) {
            val job=records[id]
            if(job==null) item.takeIf {isRecovering} else if(job.cancelled.get()) null else job.ready ?: pending(job)
        }
    }

    fun pendingItems(): List<CapturedItem> = synchronized(lock) {
        records.values.filter { it.ready == null && !it.cancelled.get() }.map(::pending)
    }

    fun previewFile(item: CapturedItem): File? = synchronized(lock) {
        records[item.processingId]?.let { File(it.directory, "preview.jpg").takeIf(File::isFile) }
    }

    /** File checks run outside the queue lock, which journal fsyncs can hold for a while. */
    fun canRetry(item: CapturedItem): Boolean {
        val job = synchronized(lock) { records[item.processingId]?.takeIf { it.state == "failed" } } ?: return false
        return job.publication != null || inputsValid(job)
    }

    private fun pending(job: Job) = CapturedItem(ITEM_TYPE_IMAGE, job.spec.dateString,
        Uri.Builder().scheme(URI_SCHEME).authority(job.id).build(), pendingFailed = job.state == "failed")

    private fun changed() {
        synchronized(lock) { version++; revision.postValue(version) }
        if (synchronized(lock) { records.values.none { it.state == "failed" && !it.cancelled.get() } }) {
            context.getSystemService(NotificationManager::class.java).cancel(13)
        }
    }
    private fun validId(id: String) = id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
    private fun discardInputs(job: Job) {
        // A completed journal has an empty file list. Also remove leftovers from
        // a process death between durable publication and ordinary cleanup.
        job.directory.listFiles()?.filter {
            it.name in setOf("source.jpg", "shading.bin", "result.jpg", "portrait.bin") ||
                it.name.matches(Regex("frame-[0-9]{2}\\.bin"))
        }?.forEach(File::delete)
    }

    private fun inputsValid(job: Job): Boolean = try {
        val files = job.plan.getJSONArray("files"); require(files.length() in 1..9)
        var total = 0L; var shadingBytes = 0L
        val names = hashSetOf<String>()
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index); val name = entry.getString("name")
            require(names.add(name))
            require(name == "source.jpg" || name == "shading.bin" || name.matches(Regex("frame-[0-9]{2}\\.bin")))
            val expected = entry.getLong("bytes")
            require(expected in 1..(if (name == "shading.bin") RawShadingData.MAX_BYTES.toLong() else 32L * 1024 * 1024))
            require(File(job.directory, name).length() == expected)
            if (name == "shading.bin") { require(expected >= 24); shadingBytes = expected } else total += expected
        }
        val mode = job.plan.getJSONObject("metadata").getJSONObject("mode").getInt("value")
        require(mode in 1..3 || (mode == 4 && BuildConfig.DEBUG && BuildConfig.CAMERA_JPEG_BURST_EVALUATION))
        if (mode == 1 || mode == 4) {
            val count = names.count { it.startsWith("frame-") }
            require(count in 2..8 && "source.jpg" !in names)
            require((0 until count).all { "frame-%02d.bin".format(java.util.Locale.ROOT, it) in names })
            if (mode == 4) {
                require(count in 2..4 && shadingBytes == 0L)
                val metadata = decode(job.plan.getJSONObject("metadata"))
                val width = metadata.getInt("width"); val height = metadata.getInt("height")
                require(width in 8..8192 && height in 8..8192 && width.toLong()*height <= 16_000_000)
                require(files.length() == count)
            }
        } else require(names == setOf("source.jpg") && shadingBytes == 0L)
        require(total <= 240L * 1024 * 1024)
        true
    } catch (_: Exception) { false }

    private fun persist(job: Job) = atomic(File(job.directory, "request.json"), JSONObject().apply {
        put("schema", 1); put("id", job.id); put("spec", job.spec.json())
        // Once publication is complete the RAW reference has no remaining consumer.
        // Keeping 96x96 RGB arrays in the last 32 journals made every cold launch
        // parse hundreds of thousands of numbers before drawing the first frame.
        val plan = if (job.state == "complete") JSONObject().apply {
            val metadata = job.plan.getJSONObject("metadata")
            put("metadata", JSONObject().apply { metadata.keys().forEach { key -> if (key != "colorReference") put(key, metadata.get(key)) } })
            put("files", JSONArray())
            put("portraitPrepared", job.plan.optBoolean("portraitPrepared"))
        } else job.plan
        put("plan", plan)
        put("state", job.state); put("bytes", job.bytes); put("publication", job.publication?.toString() ?: "")
        put("attempts", job.attempts)
        job.publicationIdentity?.let { put("publicationBytes",it.bytes);put("publicationSha256",it.sha256) }
    })

    private fun atomic(file: File, value: JSONObject) {
        val bytes = value.toString().toByteArray(); require(bytes.size <= MAX_PLAN)
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    private fun encode(bundle: Bundle): JSONObject = JSONObject().apply {
        for (key in bundle.keySet()) {
            val entry = JSONObject()
            @Suppress("DEPRECATION") val value = bundle.get(key)
            when (value) {
                is Int -> entry.put("type", "int").put("value", value)
                is Float -> entry.put("type", "float").put("value", value)
                is IntArray -> entry.put("type", "ints").put("value", JSONArray(value.toList()))
                is FloatArray -> entry.put("type", "floats").put("value", JSONArray(value.toList()))
                null -> continue
                else -> error("Unsupported photo metadata")
            }
            put(key, entry)
        }
    }

    private fun decode(json: JSONObject): Bundle = Bundle().apply {
        require(json.length() <= 32)
        for (key in json.keys()) {
            val entry = json.getJSONObject(key)
            when (entry.getString("type")) {
                "int" -> putInt(key, entry.getInt("value"))
                "float" -> putFloat(key, entry.getDouble("value").toFloat().also { require(it.isFinite()) })
                "ints" -> entry.getJSONArray("value").let { values -> require(values.length() <= 9216); putIntArray(key, IntArray(values.length()) { values.getInt(it) }) }
                "floats" -> entry.getJSONArray("value").let { values -> require(values.length() <= 9216); putFloatArray(key, FloatArray(values.length()) { values.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }) }
                else -> error("Unsupported saved photo metadata")
            }
        }
    }

    private fun jpegThumbnail(file: File): Bitmap? = if (!file.isFile) null else ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val scale = minOf(1.0, 720.0 / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    private fun finishPublication(job: Job, item: CapturedItem) {
        if(job.plan.optBoolean("portraitPrepared"))PortraitStore.commit(context,job.id,item.uri)
        synchronized(lock) {
            check(!job.stopped());job.ready=item;job.state="complete"
            try {persist(job)} catch(error:Exception) {logFailure("Photo saved; journal update deferred",error)}
        }
    }

    private fun readPublicationIdentity(json: JSONObject): PublicationRecovery.Identity? =
        if(json.has("publicationBytes") && json.has("publicationSha256"))
            PublicationRecovery.Identity(json.getLong("publicationBytes"),json.getString("publicationSha256")) else null

    private fun publicationStatus(job: Job): PublicationRecovery.Status {
        val uri=job.publication ?: return PublicationRecovery.Status.MISSING
        val resolver=context.contentResolver
        return try {
            if(uri.scheme!="content")return PublicationRecovery.Status.UNAVAILABLE
            if(uri.authority==MediaStore.AUTHORITY) {
                val row=bounded {resolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.IS_PENDING),null,it)}?.use {
                    if(!it.moveToFirst())null else (if(it.isNull(0))null else it.getString(0)) to (it.getInt(1)!=0)
                } ?: return PublicationRecovery.Status.MISSING
                val (name,pending)=row
                val expected=job.publicationIdentity
                // The written bytes, not the name, prove the row is ours: a provider may rename it.
                val matches=expected!=null && (openBounded(uri)?.use {PublicationRecovery.matches(it,expected)} ?: false)
                when {
                    matches && !pending -> PublicationRecovery.Status.COMPLETE
                    // A cancelled photo is never published by recovery: it stays pending for deletion.
                    matches && job.cancelled.get() -> PublicationRecovery.Status.INCOMPLETE
                    matches -> {
                        // Fully written but still pending: publish it rather than redo the work.
                        val cleared=resolver.update(uri,android.content.ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)>0
                        if(cleared)PublicationRecovery.Status.COMPLETE else PublicationRecovery.Status.UNAVAILABLE
                    }
                    name!="IMG_"+job.spec.dateString+".jpg" -> PublicationRecovery.Status.UNAVAILABLE
                    // Our name and published: complete, even if edited in place since.
                    !pending -> PublicationRecovery.Status.COMPLETE
                    else -> PublicationRecovery.Status.INCOMPLETE
                }
            } else {
                if(uri.authority!=Uri.parse(job.spec.storage).authority || !DocumentsContract.isDocumentUri(context,uri))
                    return PublicationRecovery.Status.UNAVAILABLE
                val row=bounded {resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE),
                    null,it)}?.use {if(!it.moveToFirst())null else
                        (if(it.isNull(0))null else it.getString(0)) to (if(it.isNull(1))null else it.getLong(1))}
                    // No answer is only "missing" while the tree itself still answers (SD card, cloud).
                    ?: return if(treeAnswers(uri))PublicationRecovery.Status.MISSING else PublicationRecovery.Status.UNAVAILABLE
                val expected=job.publicationIdentity
                if(!PublicationRecovery.mayOwnPartial(row.first,row.second,"IMG_"+job.spec.dateString+".jpg",expected))
                    return PublicationRecovery.Status.UNAVAILABLE
                if(expected==null) {
                    // Legacy SAF journals cannot prove completion. Delete only
                    // when cancelled or recoverable from retained inputs.
                    if(job.cancelled.get() || inputsValid(job))PublicationRecovery.Status.INCOMPLETE
                    else PublicationRecovery.Status.UNAVAILABLE
                } else openBounded(uri)?.use {
                    if(PublicationRecovery.matches(it,expected))PublicationRecovery.Status.COMPLETE else PublicationRecovery.Status.INCOMPLETE
                } ?: PublicationRecovery.Status.UNAVAILABLE
            }
        } catch(_:java.io.FileNotFoundException) {
            // Document providers report a deleted document this way, MediaStore never does.
            if(uri.authority!=MediaStore.AUTHORITY && treeAnswers(uri))PublicationRecovery.Status.MISSING else PublicationRecovery.Status.UNAVAILABLE
        } catch(_:IllegalArgumentException) {
            if(uri.authority!=MediaStore.AUTHORITY && treeAnswers(uri))PublicationRecovery.Status.MISSING else PublicationRecovery.Status.UNAVAILABLE
        } catch(_:Exception) { PublicationRecovery.Status.UNAVAILABLE }
    }

    /** Provider I/O is serialized per job and never runs under the queue lock. */
    /**
     * The process can die after MediaStore created the row but before the journal recorded it.
     * Find such a row by its name and folder: adopt it if it holds exactly the finished photo,
     * otherwise delete it (pending rows are only visible to their owner).
     */
    private fun adoptOrphan(job: Job) {
        val expected=job.publicationIdentity ?: return
        // Pending rows can only be matched by name from Android 11.
        if(android.os.Build.VERSION.SDK_INT<30 || job.publication!=null ||
            job.spec.storage!=app.grapheneos.camera.CamConfig.SettingValues.Default.STORAGE_LOCATION) return
        val resolver=context.contentResolver
        val args=android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("IMG_"+job.spec.dateString+".jpg",DEFAULT_MEDIA_STORE_CAPTURE_PATH+"/"))
            @android.annotation.SuppressLint("InlinedApi") putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE)
        }
        val rows=try {
            bounded {resolver.query(app.grapheneos.camera.CamConfig.imageCollectionUri,arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.IS_PENDING),
                args,it)}?.use {
                val list=arrayListOf<Pair<Uri,Boolean>>()
                while(it.moveToNext())list.add(android.content.ContentUris.withAppendedId(app.grapheneos.camera.CamConfig.imageCollectionUri,it.getLong(0)) to (it.getInt(1)!=0))
                list
            }
        } catch(_:Exception) {null} ?: return
        for((uri,pending) in rows) {
            val matches=try {openBounded(uri)?.use {PublicationRecovery.matches(it,expected)} ?: false} catch(_:Exception) {false}
            if(matches) { synchronized(lock) {job.publication=uri;persistQuietly(job)};return }
            if(pending) try {resolver.delete(uri,null,null)} catch(_:Exception) { }
        }
    }

    private fun reconcilePublication(job: Job): PublicationRecovery.Decision = synchronized(job.publicationLock) {
        adoptOrphan(job)
        val uri=job.publication ?: return@synchronized if(job.cancelled.get())PublicationRecovery.Decision.DISCARD else PublicationRecovery.Decision.RETRY
        val status=publicationStatus(job)
        val decision=PublicationRecovery.decide(status,job.cancelled::get) {
            try {
                val acknowledged=if(uri.authority==MediaStore.AUTHORITY)context.contentResolver.delete(uri,null,null)>0
                    else DocumentsContract.deleteDocument(context.contentResolver,uri)
                acknowledged || publicationStatus(job)==PublicationRecovery.Status.MISSING
            } catch(_:Exception) {false}
        }
        if(decision in listOf(PublicationRecovery.Decision.RETRY,PublicationRecovery.Decision.DISCARD))clearPublication(job,uri)
        decision
    }

    private fun clearPublication(job: Job, uri: Uri) = synchronized(lock) {
        if(job.publication!=uri)return@synchronized
        val identity=job.publicationIdentity
        job.publication=null;job.publicationIdentity=null
        try {persist(job)} catch(error:Exception) {job.publication=uri;job.publicationIdentity=identity;throw error}
    }

    private fun cleanupCancelled(job: Job) = synchronized(job.publicationLock) {
        try {
            PortraitStore.discard(context,job.id)
            job.publication?.let {PortraitStore.remove(context,it)}
            val decision=reconcilePublication(job)
            if(decision!=PublicationRecovery.Decision.DISCARD) {
                // Persist a small cancellation tombstone before deleting heavy
                // private inputs. It never re-enters the processing queue.
                val directory=File(root,"cancelled-"+job.id).apply {check(isDirectory || mkdir())}
                val plan=JSONObject().put("metadata",JSONObject()).put("files",JSONArray())
                val tombstone=Job(job.id,job.spec,plan,directory).apply {
                    state="cancelled";cancelled.set(true);publication=job.publication;publicationIdentity=job.publicationIdentity
                }
                persist(tombstone)
            }
            job.directory.deleteRecursively()
            synchronized(lock) {records.remove(job.id)};budget.release(job.id)
        } catch(error:Exception) {
            // Keep the original journal and URI if even tombstone persistence
            // fails. A later launch can retry without inventing another output.
            logFailure("Cancelled publication cleanup deferred",error)
            // The journal stays on disk for the next launch. Drop the in-memory job and its budget now.
            synchronized(lock) {records.remove(job.id)};budget.release(job.id)
        }
    }

    private fun cleanupCancelledTombstones() {
        val now=System.currentTimeMillis()
        // Each tombstone is rechecked at most hourly, and given up after 30 days.
        root.listFiles()?.filter {it.isDirectory && it.name.startsWith("cancelled-") && validId(it.name.removePrefix("cancelled-")) &&
            now-it.lastModified()>=60*60*1000L}
            ?.sortedBy {it.lastModified()}?.take(64).orEmpty().forEach {directory ->
                try {
                    val file=File(directory,"request.json");require(file.length() in 1..MAX_PLAN)
                    val json=JSONObject(AtomicFile(file).openRead().use {it.readBytes().toString(Charsets.UTF_8)})
                    require(json.getInt("schema")==1 && "cancelled-"+json.getString("id")==directory.name && json.getString("state")=="cancelled")
                    val job=Job(json.getString("id"),ProcessedPhotoSpec.fromJson(json.getJSONObject("spec")),json.getJSONObject("plan"),directory).apply {
                        state="cancelled";cancelled.set(true)
                        publication=json.optString("publication").takeIf {it.isNotEmpty()}?.let(Uri::parse)
                        publicationIdentity=readPublicationIdentity(json)
                    }
                    if(reconcilePublication(job)==PublicationRecovery.Decision.DISCARD ||
                        now-job.spec.capturedAt>30L*24*60*60*1000)directory.deleteRecursively()
                    else directory.setLastModified(System.currentTimeMillis())
                } catch(_:Exception) {directory.setLastModified(System.currentTimeMillis())}
            }
    }

    private fun prune() {
        val old = synchronized(lock) { records.values.filter { it.state == "complete" }.sortedByDescending { it.spec.capturedAt }.drop(32).also { values -> values.forEach { records.remove(it.id) } } }
        old.forEach { it.directory.deleteRecursively() }
    }

    /**
     * Journal writes on failure paths. A full disk must not turn a failed photo into a crash
     * loop: the in-memory state stays authoritative and the write is retried with the next change.
     */
    private fun persistQuietly(job: Job): Boolean = JournalWrite.quietly({ persist(job) }) { error ->
        logFailure("Cannot write photo journal", error)
        if (lowStorage()) storageFull()
    }

    private val providerTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    /** A provider that does not answer within 10 s is treated as unavailable, never as an answer. */
    /** Runs a provider call that is cancelled after 10 s; the timer is withdrawn when it returns. */
    private inline fun <T> bounded(call: (android.os.CancellationSignal) -> T): T {
        val signal = android.os.CancellationSignal()
        val timer = providerTimer.schedule({ signal.cancel() }, 10, java.util.concurrent.TimeUnit.SECONDS)
        try { return call(signal) } finally { timer.cancel(false) }
    }

    /** A bounded open for reading: a provider that hangs is cancelled like a query. */
    private fun openBounded(uri: Uri): java.io.InputStream? = bounded { signal ->
        context.contentResolver.openFileDescriptor(uri, "r", signal)
    }?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }

    /** Whether the SAF tree holding a document still answers: only then is a missing document gone. */
    private fun treeAnswers(uri: Uri): Boolean = try {
        val tree = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        bounded { signal -> context.contentResolver.query(tree, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, signal) }
            ?.use { it.moveToFirst() } ?: false
    } catch (_: Exception) { false }

    private fun lowStorage(): Boolean = try { StatFs(root.path).availableBytes < MIN_FREE } catch (_: Exception) { true }

    private fun storageFull() {
        context.mainExecutor.execute {
            android.widget.Toast.makeText(context, R.string.photo_storage_full, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Release builds log only the exception class: messages can carry paths and URIs. */
    private fun logFailure(message: String, error: Throwable) {
        if (BuildConfig.DEBUG) Log.w("PhotoProcessingQueue", message, error)
        else Log.w("PhotoProcessingQueue", message + ": " + error.javaClass.name)
    }

    private fun failureNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("image_saver_error", context.getString(R.string.unable_to_save_image), NotificationManager.IMPORTANCE_HIGH))
        manager.notify(13, Notification.Builder(context, "image_saver_error").setSmallIcon(R.drawable.info)
            .setContentTitle(context.getString(R.string.photo_processing_failed))
            .setContentIntent(PendingIntent.getActivity(context, 1,
                Intent(context, app.grapheneos.camera.ui.activities.InAppGallery::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).build())
    }
}
