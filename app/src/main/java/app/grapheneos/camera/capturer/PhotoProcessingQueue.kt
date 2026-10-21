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
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import app.grapheneos.camera.BuildConfig
import app.grapheneos.camera.CamConfig
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.ITEM_TYPE_IMAGE
import app.grapheneos.camera.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.concurrent.Executors

/** Captured inputs belong to durable gallery jobs, independently of the current camera operation. */
internal object PhotoProcessingQueue {
    const val URI_SCHEME = "camera-processing"
    private const val MAX_PLAN = 256 * 1024
    private const val QUEUE_PREFS = "photo_queue"
    private const val PENDING_STORAGE_RESET = "pending_storage_reset"
    private val resetExecutor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val revision = MutableLiveData(0)
    val changes: LiveData<Int> = revision
    private var version = 0
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var core: PhotoQueueCore<Job>

    internal data class Reservation(val id: String, val bytes: Long)

    /** A job's captured settings and its processing plan, as the journal stores them. */
    internal class Job(val spec: ProcessedPhotoSpec, val plan: JSONObject) : PhotoQueueCore.Data {
        override val capturedAt get() = spec.capturedAt
        override val dateString get() = spec.dateString
        override val storage get() = spec.storage
        override val compactable get() = plan.getJSONObject("metadata").has("colorReference")
    }

    val isRecovering: Boolean get() = ::core.isInitialized && core.isRecovering

    fun initialize(app: Context) {
        synchronized(lock) {
            if (::context.isInitialized) return
            context = app.applicationContext
            root = File(context.filesDir, "photo-processing").apply { check(isDirectory || mkdirs()) }
            core = PhotoQueueCore(AndroidIo, Executors.newSingleThreadExecutor(), Executors.newSingleThreadExecutor())
        }
        core.recover()
    }

    fun reserve(bytes: Long): Reservation? {
        check(::context.isInitialized)
        return core.reserve(bytes)?.let { Reservation(it.id, it.bytes) }
    }

    fun release(reservation: Reservation) = core.release(PhotoQueueCore.Reservation(reservation.id, reservation.bytes))

    fun lacksStorage(bytes: Long): Boolean = core.lacksStorage(bytes)

    @Suppress("DEPRECATION")
    fun enqueue(reservation: Reservation, spec: ProcessedPhotoSpec, payload: Bundle,
                jpeg: File?, preview: Bitmap?, profile: JSONObject?, cancelled: () -> Boolean): CapturedItem {
        val directory = File(root, reservation.id)
        val frames = payload.getParcelableArrayList<SharedMemory>("frames") ?: arrayListOf()
        var accepted = false
        try {
            check(!cancelled())
            if (payload.getInt("mode") == 4) {
                check(IspJpegNight.enabled(context))
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
            profile?.let { atomic(File(directory, "acquisition.json"), it) }
            core.accept(PhotoQueueCore.Reservation(reservation.id, reservation.bytes), Job(spec, plan), bytes, cancelled)
            accepted = true
            return CapturedItem(ITEM_TYPE_IMAGE, spec.dateString, pendingUri(reservation.id))
        } finally {
            if (!accepted) { directory.deleteRecursively(); release(reservation) }
        }
    }

    fun resume() = core.resume()

    /** An unlocked camera session applies a folder reset that a locked session only recorded,
     *  unless the folder answers again by then. */
    fun applyPendingStorageReset() {
        val queuePrefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val storage = queuePrefs.getString(PENDING_STORAGE_RESET, null) ?: return
        if (context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) return
        resetExecutor.execute {
            try {
                if (AndroidIo.destinationState(storage) == PhotoQueueCore.Destination.LOST) resetStorageLocation(storage)
            } catch (error: Exception) { logFailure("Cannot reset the storage location", error) }
            finally { queuePrefs.edit { remove(PENDING_STORAGE_RESET) } }
        }
    }

    private fun resetStorageLocation(storage: String) {
        val prefs = context.getSharedPreferences(CamConfig.COMMON_SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            if (prefs.getString(CamConfig.SettingValues.Key.STORAGE_LOCATION, AndroidIo.defaultStorage) != storage) return
            app.grapheneos.camera.CapturedItems.savePreviousSafTree(Uri.parse(storage), prefs)
            prefs.edit().putString(CamConfig.SettingValues.Key.STORAGE_LOCATION, AndroidIo.defaultStorage).apply()
            app.grapheneos.camera.CapturedItems.releaseUntrackedSafTrees(context, prefs)
        }
    }

    /** Returns a completed photo when deletion raced with publication; otherwise cancels this job. */
    fun cancel(id: String): CapturedItem? = core.cancel(id)?.let { CapturedItem(ITEM_TYPE_IMAGE, it.dateString, Uri.parse(it.uri)) }

    fun forgetPhoto(uri: Uri) = core.forgetPublished(uri.toString())

    fun forgetCompleted(id: String) = core.forgetCompleted(id)

    fun abandon(id: String) {
        try { core.abandon(id) }
        catch (error: Exception) {
            logFailure("Cannot persist capture abandonment", error)
            context.mainExecutor.execute {
                android.widget.Toast.makeText(context, R.string.deleting_unexpected_error,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun retry(id: String) = report(core.retry(id))

    /** Publishes a photo whose folder is gone to the default location instead. */
    fun moveToDefault(id: String) = report(core.moveToDefault(id))

    private fun report(result: PhotoQueueCore.RetryResult) {
        if (result == PhotoQueueCore.RetryResult.QUEUE_FULL) context.mainExecutor.execute {
            android.widget.Toast.makeText(context, R.string.photo_retry_queue_full, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun resolve(item: CapturedItem): CapturedItem? {
        val id = item.processingId ?: return item
        return when (val found = core.lookup(id)) {
            PhotoQueueCore.Lookup.Unknown -> item.takeIf { isRecovering }
            PhotoQueueCore.Lookup.Cancelled -> null
            is PhotoQueueCore.Lookup.Ready -> CapturedItem(ITEM_TYPE_IMAGE, found.dateString, Uri.parse(found.uri))
            is PhotoQueueCore.Lookup.Pending -> pending(found)
        }
    }

    fun pendingItems(): List<CapturedItem> = core.pendingItems().map(::pending)

    fun previewFile(item: CapturedItem): File? = item.processingId?.takeIf { core.has(it) }
        ?.let { File(File(root, it), "preview.jpg").takeIf(File::isFile) }

    fun canRetry(item: CapturedItem): Boolean = item.processingId?.let(core::canRetry) == true
    fun canMoveToDefault(item: CapturedItem): Boolean = item.processingId?.let(core::canMoveToDefault) == true

    private fun pendingUri(id: String) = Uri.Builder().scheme(URI_SCHEME).authority(id).build()
    private fun pending(job: PhotoQueueCore.Lookup.Pending) =
        CapturedItem(ITEM_TYPE_IMAGE, job.dateString, pendingUri(job.id), pendingFailed = job.failed)

    private fun validId(id: String) = id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
    private fun directory(id: String): File {
        require(validId(id))
        return File(root, id)
    }

    private fun inputsValid(directory: File, plan: JSONObject): Boolean = try {
        val files = plan.getJSONArray("files"); require(files.length() in 1..9)
        var total = 0L; var shadingBytes = 0L
        val names = hashSetOf<String>()
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index); val name = entry.getString("name")
            require(names.add(name))
            require(name == "source.jpg" || name == "shading.bin" || name.matches(Regex("frame-[0-9]{2}\\.bin")))
            val expected = entry.getLong("bytes")
            require(expected in 1..(if (name == "shading.bin") RawShadingData.MAX_BYTES.toLong() else 32L * 1024 * 1024))
            require(File(directory, name).length() == expected)
            if (name == "shading.bin") { require(expected >= 24); shadingBytes = expected } else total += expected
        }
        val mode = plan.getJSONObject("metadata").getJSONObject("mode").getInt("value")
        require(mode in 1..3 || (mode == 4 && IspJpegNight.enabled(context)))
        if (mode == 1 || mode == 4) {
            val count = names.count { it.startsWith("frame-") }
            require(count in 2..8 && "source.jpg" !in names)
            require((0 until count).all { "frame-%02d.bin".format(java.util.Locale.ROOT, it) in names })
            if (mode == 4) {
                require(count in 2..4 && shadingBytes == 0L)
                val metadata = decode(plan.getJSONObject("metadata"))
                val width = metadata.getInt("width"); val height = metadata.getInt("height")
                require(width in 8..8192 && height in 8..8192 && width.toLong()*height <= 16_000_000)
                require(files.length() == count)
            }
        } else require(names == setOf("source.jpg") && shadingBytes == 0L)
        require(total <= 240L * 1024 * 1024)
        true
    } catch (_: Exception) { false }

    private fun journalJson(journal: PhotoQueueCore.Journal<Job>, plan: JSONObject) = JSONObject().apply {
        put("schema", 1); put("id", journal.id); put("spec", journal.data.spec.json())
        put("plan", plan)
        put("state", journal.state); put("bytes", journal.bytes); put("publication", journal.publication ?: "")
        put("attempts", journal.attempts)
        journal.identity?.let { put("publicationBytes", it.bytes); put("publicationSha256", it.sha256) }
        journal.destination?.let { put("destination", it) }
        if (journal.destinationLost) put("failure", "destination")
    }

    /** A journal decodes completely or the job is set aside: no partial job is ever advertised. */
    private fun readJournalFile(file: File, id: String): PhotoQueueCore.Journal<Job> {
        require(file.length() in 1..MAX_PLAN || File(file.path + ".bak").length() in 1..MAX_PLAN)
        val text = AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        return try {
            val json = JSONObject(text)
            require(json.getInt("schema") == 1 && json.getString("id") == id)
            val plan = json.getJSONObject("plan")
            val destination = json.optString("destination").takeIf { it.isNotEmpty() }
            require(destination == null || destination == CamConfig.SettingValues.Default.STORAGE_LOCATION)
            PhotoQueueCore.Journal(id, Job(ProcessedPhotoSpec.fromJson(json.getJSONObject("spec")), plan),
                json.getString("state"), json.getLong("bytes"), json.optString("publication").takeIf { it.isNotEmpty() },
                json.optInt("attempts"), readPublicationIdentity(json), destination,
                plan.optBoolean("portraitPrepared"), json.optString("failure") == "destination")
        } catch (error: org.json.JSONException) { throw PhotoQueueCore.UnreadableJournal(error) }
        catch (error: IllegalArgumentException) { throw PhotoQueueCore.UnreadableJournal(error) }
    }

    private fun readPublicationIdentity(json: JSONObject): PublicationRecovery.Identity? =
        if (json.has("publicationBytes") && json.has("publicationSha256"))
            PublicationRecovery.Identity(json.getLong("publicationBytes"), json.getString("publicationSha256")) else null

    private object AndroidIo : PhotoQueueCore.Io<Job> {
        override val defaultStorage = CamConfig.SettingValues.Default.STORAGE_LOCATION

        override fun jobIds() = root.listFiles()?.filter { it.isDirectory && validId(it.name) }?.map { it.name }
            ?.sorted()?.take(64).orEmpty()

        override fun hasJournal(id: String): Boolean {
            val file = File(directory(id), "request.json")
            return file.exists() || File(file.path + ".bak").exists()
        }

        override fun readJournal(id: String) = readJournalFile(File(directory(id), "request.json"), id)

        override fun writeJournal(journal: PhotoQueueCore.Journal<Job>) {
            // Once publication is complete the RAW reference has no remaining consumer.
            // Keeping 96x96 RGB arrays in the last 32 journals made every cold launch
            // parse hundreds of thousands of numbers before drawing the first frame.
            val plan = journal.data.plan
            val stored = if (journal.state == PhotoQueueCore.COMPLETE) JSONObject().apply {
                val metadata = plan.getJSONObject("metadata")
                put("metadata", JSONObject().apply { metadata.keys().forEach { key -> if (key != "colorReference") put(key, metadata.get(key)) } })
                put("files", JSONArray())
                put("portraitPrepared", journal.portraitPrepared)
            } else plan.apply { if (journal.portraitPrepared) put("portraitPrepared", true) }
            atomic(File(directory(journal.id), "request.json"), journalJson(journal, stored))
        }

        override fun setAside(id: String) {
            val directory = directory(id)
            val journal = File(directory, "request.json")
            journal.renameTo(File(directory, "request.json.unreadable"))
            File(journal.path + ".bak").delete()
            discardInputs(id)
        }

        override fun deleteJob(id: String) { directory(id).deleteRecursively() }

        override fun discardInputs(id: String) {
            // A completed journal has an empty file list. Also remove leftovers from
            // a process death between durable publication and ordinary cleanup.
            directory(id).listFiles()?.filter {
                it.name in setOf("source.jpg", "shading.bin", "result.jpg", "portrait.bin") ||
                    it.name.matches(Regex("frame-[0-9]{2}\\.bin"))
            }?.forEach(File::delete)
        }

        override fun inputsValid(id: String, data: Job) = inputsValid(directory(id), data.plan)

        override fun freeBytes() = StatFs(root.path).availableBytes

        override fun tombstoneIds(before: Long) = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("cancelled-") &&
            validId(it.name.removePrefix("cancelled-")) && it.lastModified() <= before }
            ?.sortedBy { it.lastModified() }?.take(64)?.map { it.name.removePrefix("cancelled-") }.orEmpty()

        private fun tombstone(id: String): File { require(validId(id)); return File(root, "cancelled-$id") }
        override fun readTombstone(id: String) = readJournalFile(File(tombstone(id), "request.json"), id)
        override fun writeTombstone(journal: PhotoQueueCore.Journal<Job>) {
            val directory = tombstone(journal.id).apply { check(isDirectory || mkdir()) }
            atomic(File(directory, "request.json"), journalJson(journal,
                JSONObject().put("metadata", JSONObject()).put("files", JSONArray())))
        }
        override fun deleteTombstone(id: String) { tombstone(id).deleteRecursively() }
        override fun touchTombstone(id: String) { tombstone(id).setLastModified(System.currentTimeMillis()) }

        override fun publicationStatus(publication: PhotoQueueCore.Publication) = publicationStatus(
            Uri.parse(publication.uri), publication)

        override fun deletePublication(uri: String): Boolean {
            val parsed = Uri.parse(uri)
            return if (parsed.authority == MediaStore.AUTHORITY) context.contentResolver.delete(parsed, null, null) > 0
                else DocumentsContract.deleteDocument(context.contentResolver, parsed)
        }

        override fun adoptableOrphan(dateString: String, identity: PublicationRecovery.Identity): String? {
            // Pending rows can only be matched by name from Android 11.
            if (android.os.Build.VERSION.SDK_INT < 30) return null
            val resolver = context.contentResolver
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf("IMG_$dateString.jpg", "$DEFAULT_MEDIA_STORE_CAPTURE_PATH/"))
                @android.annotation.SuppressLint("InlinedApi") putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            val rows = try {
                bounded { resolver.query(CamConfig.imageCollectionUri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                    args, it) }?.use {
                    val list = arrayListOf<Pair<Uri, Boolean>>()
                    while (it.moveToNext()) list.add(android.content.ContentUris.withAppendedId(CamConfig.imageCollectionUri, it.getLong(0)) to (it.getInt(1) != 0))
                    list
                }
            } catch (_: Exception) { null } ?: return null
            for ((uri, pending) in rows) {
                val matches = try { openBounded(uri)?.use { PublicationRecovery.matches(it, identity) } ?: false } catch (_: Exception) { false }
                if (matches) return uri.toString()
                if (pending) try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            return null
        }

        override fun destinationState(storage: String): PhotoQueueCore.Destination {
            val tree = try { Uri.parse(storage) } catch (_: Exception) { return PhotoQueueCore.Destination.LOST }
            // A revoked grant leaves the folder in place but no longer ours to write.
            if (context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission })
                return PhotoQueueCore.Destination.LOST
            return try {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val found = bounded { signal -> context.contentResolver.query(root,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, signal) }?.use { it.moveToFirst() } ?: false
                if (found) PhotoQueueCore.Destination.AVAILABLE else PhotoQueueCore.Destination.LOST
            } catch (_: java.io.FileNotFoundException) { PhotoQueueCore.Destination.LOST }
            catch (_: IllegalArgumentException) { PhotoQueueCore.Destination.LOST }
            catch (_: Exception) { PhotoQueueCore.Destination.UNKNOWN }
        }

        /** As the camera does when a photo cannot be created in the folder (GrapheneOS behaviour).
         *  A locked device only records it: the next unlocked camera session applies it. */
        override fun forgetDestination(storage: String) {
            if (context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE).edit { putString(PENDING_STORAGE_RESET, storage) }
                return
            }
            resetStorageLocation(storage)
        }

        @Suppress("DEPRECATION")
        override fun process(id: String, data: Job, stopped: () -> Boolean, prepared: () -> Unit): PublicationRecovery.Identity {
            val directory = directory(id)
            val memories = arrayListOf<SharedMemory>()
            try {
                val payload = decode(data.plan.getJSONObject("metadata"))
                val files = data.plan.getJSONArray("files")
                if (payload.getInt("mode") == 1 || payload.getInt("mode") == 4) {
                    val frames = arrayListOf<SharedMemory>()
                    for (index in 0 until files.length()) {
                        val name = files.getJSONObject(index).getString("name")
                        val memory = ProcessingClient.fileMemory("queued-raw", File(directory, name),
                            if (name == "shading.bin") RawShadingData.MAX_BYTES.toLong() else 32L * 1024 * 1024)
                        memories.add(memory)
                        if (name == "shading.bin") payload.putParcelable("rawShading", memory) else frames.add(memory)
                    }
                    payload.putParcelableArrayList("frames", frames)
                } else {
                    val jpeg = ProcessingClient.fileMemory("queued-jpeg", File(directory, "source.jpg")); memories.add(jpeg)
                    payload.putParcelable("jpeg", jpeg)
                    payload.putBoolean("depthEnabled", data.spec.depthEnabled)
                }
                check(!stopped())
                val stats = Bundle()
                val editData = File(directory, "portrait.bin")
                val bytes = ProcessingClient.process(context, payload, stopped, stats) { memory ->
                    val mapping = memory.mapReadOnly()
                    try {
                        PortraitData.decode(mapping)
                        FileOutputStream(editData).use { output ->
                            while (mapping.hasRemaining()) check(output.channel.write(mapping) > 0)
                            output.fd.sync()
                        }
                    } finally { SharedMemory.unmap(mapping) }
                }
                check(!stopped())
                if (payload.getInt("mode") == 2) {
                    PortraitStore.prepare(context, id, File(directory, "source.jpg"), editData, data.spec)
                    prepared()
                }
                val file = File(directory, "result.jpg")
                FileOutputStream(file).use { it.write(ProcessedJpeg.metadata(bytes, data.spec)); it.fd.sync() }
                if (BuildConfig.DEBUG) developmentProfile(payload, data, stats)
                return file.inputStream().use(PublicationRecovery::identity)
            } finally { memories.forEach(SharedMemory::close) }
        }

        override fun save(id: String, data: Job, storage: String, stopped: () -> Boolean,
                          created: (String) -> Unit, removed: (String) -> Unit): String =
            PhotoFileStore.save(context, listOf(PhotoFileStore.Photo(File(directory(id), "result.jpg"), ".jpg", "image/jpeg")),
                storage, Date(data.spec.capturedAt), data.spec.dateString, stopped,
                removedPhoto = { removed(it.toString()) }) { created(it.toString()) }.single().uri.toString()

        override fun beginLifetime(interrupt: () -> Unit) = PhotoProcessingLifetime.begin(context, interrupt)
        override fun renewLifetime() = PhotoProcessingLifetime.renew()
        override fun endLifetime(token: String) = PhotoProcessingLifetime.end(context, token)

        override fun portraitCommit(id: String, uri: String) = PortraitStore.commit(context, id, Uri.parse(uri))
        override fun portraitDiscard(id: String) = PortraitStore.discard(context, id)
        override fun portraitRemove(uri: String) = PortraitStore.remove(context, Uri.parse(uri))
        override fun purgePortraits() = PortraitStore.purgeDeleted(context)

        override fun changed() { synchronized(lock) { version++; revision.postValue(version) } }

        override fun failureNotification() = notifyFailure(R.string.photo_processing_failed)
        override fun destinationLost() = notifyFailure(R.string.folder_not_found)

        override fun clearFailureNotification() {
            context.getSystemService(NotificationManager::class.java).cancel(13)
        }

        override fun storageFull() {
            context.mainExecutor.execute {
                android.widget.Toast.makeText(context, R.string.photo_storage_full, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        override fun log(message: String, error: Throwable) = logFailure(message, error)
    }

    private fun developmentProfile(payload: Bundle, data: Job, stats: Bundle) {
        val profile = JSONObject().put("mode", payload.getInt("mode")).put("depthEnabled", data.spec.depthEnabled)
            .put("cameraPermission", stats.getInt("cameraPermission", -999))
        for (key in listOf("processMs", "nativeMs", "decodeMs", "modelMs", "inferenceMs", "depthInferenceMs", "neuralDenoiseMs", "encodeMs", "jpegStagePssKiB")) profile.put(key, stats.getLong(key))
        for (key in listOf("effectiveFrames", "colorErrorBefore", "colorErrorAfter", "colorInliers")) profile.put(key, stats.getDouble(key))
        profile.put("colorMatchApplied", stats.getBoolean("colorMatchApplied"))
        profile.put("edgeAwareDemosaic", stats.getBoolean("edgeAwareDemosaic"))
        profile.put("frequencyDenoise", stats.getBoolean("frequencyDenoise"))
        profile.put("steadyPhotoFinish", stats.getBoolean("steadyPhotoFinish"))
        profile.put("rawShadingApplied", stats.getBoolean("rawShadingApplied"))
        profile.put("jpegRejectedFrames", stats.getDouble("jpegRejectedFrames"))
        profile.put("jpegMaximumShift", stats.getDouble("jpegMaximumShift"))
        profile.put("jpegGain", stats.getDouble("jpegGain"))
        stats.getDoubleArray("jpegBlackPedestal")?.let { profile.put("jpegBlackPedestal", JSONArray(it.toList())) }
        try { atomic(File(File(context.filesDir, "processing-test").apply { mkdirs() }, "photo-profile-" + data.spec.capturedAt + ".json"), profile) }
        catch (error: Exception) { Log.w("PhotoProcessingQueue", "Unable to write development profile", error) }
    }

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

    private fun publicationStatus(uri: Uri, publication: PhotoQueueCore.Publication): PublicationRecovery.Status {
        val resolver = context.contentResolver
        return try {
            if (uri.scheme != "content") return PublicationRecovery.Status.UNAVAILABLE
            if (uri.authority == MediaStore.AUTHORITY) {
                val row = bounded { resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING), null, it) }?.use {
                    if (!it.moveToFirst()) null else (if (it.isNull(0)) null else it.getString(0)) to (it.getInt(1) != 0)
                } ?: return PublicationRecovery.Status.MISSING
                val (name, pending) = row
                val expected = publication.identity
                // The written bytes, not the name, prove the row is ours: a provider may rename it.
                val matches = expected != null && (openBounded(uri)?.use { PublicationRecovery.matches(it, expected) } ?: false)
                when {
                    matches && !pending -> PublicationRecovery.Status.COMPLETE
                    // A cancelled photo is never published by recovery: it stays pending for deletion.
                    matches && publication.cancelled -> PublicationRecovery.Status.INCOMPLETE
                    matches -> {
                        // Fully written but still pending: publish it rather than redo the work.
                        val cleared = resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0
                        if (cleared) PublicationRecovery.Status.COMPLETE else PublicationRecovery.Status.UNAVAILABLE
                    }
                    name != publication.expectedName -> PublicationRecovery.Status.UNAVAILABLE
                    // Our name and published: complete, even if edited in place since.
                    !pending -> PublicationRecovery.Status.COMPLETE
                    else -> PublicationRecovery.Status.INCOMPLETE
                }
            } else {
                if (uri.authority != Uri.parse(publication.storage).authority || !DocumentsContract.isDocumentUri(context, uri))
                    return PublicationRecovery.Status.UNAVAILABLE
                val row = bounded { resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE),
                    null, it) }?.use { if (!it.moveToFirst()) null else
                        (if (it.isNull(0)) null else it.getString(0)) to (if (it.isNull(1)) null else it.getLong(1)) }
                    // No answer is only "missing" while the tree itself still answers (SD card, cloud).
                    ?: return if (treeAnswers(uri)) PublicationRecovery.Status.MISSING else PublicationRecovery.Status.UNAVAILABLE
                val expected = publication.identity
                if (!PublicationRecovery.mayOwnPartial(row.first, row.second, publication.expectedName, expected))
                    return PublicationRecovery.Status.UNAVAILABLE
                if (expected == null) {
                    // Legacy SAF journals cannot prove completion. Delete only
                    // when cancelled or recoverable from retained inputs.
                    if (publication.cancelled || publication.inputsValid()) PublicationRecovery.Status.INCOMPLETE
                    else PublicationRecovery.Status.UNAVAILABLE
                } else openBounded(uri)?.use {
                    if (PublicationRecovery.matches(it, expected)) PublicationRecovery.Status.COMPLETE else PublicationRecovery.Status.INCOMPLETE
                } ?: PublicationRecovery.Status.UNAVAILABLE
            }
        } catch (_: java.io.FileNotFoundException) {
            // Document providers report a deleted document this way, MediaStore never does.
            if (uri.authority != MediaStore.AUTHORITY && treeAnswers(uri)) PublicationRecovery.Status.MISSING else PublicationRecovery.Status.UNAVAILABLE
        } catch (_: IllegalArgumentException) {
            if (uri.authority != MediaStore.AUTHORITY && treeAnswers(uri)) PublicationRecovery.Status.MISSING else PublicationRecovery.Status.UNAVAILABLE
        } catch (_: Exception) { PublicationRecovery.Status.UNAVAILABLE }
    }

    private val providerTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    /** Runs a provider call that is cancelled after 10 s, treated as unavailable rather than as an answer. */
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

    /** Release builds log only the exception class: messages can carry paths and URIs. */
    private fun logFailure(message: String, error: Throwable) {
        if (BuildConfig.DEBUG) Log.w("PhotoProcessingQueue", message, error)
        else Log.w("PhotoProcessingQueue", message + ": " + error.javaClass.name)
    }

    private fun notifyFailure(text: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("image_saver_error", context.getString(R.string.unable_to_save_image), NotificationManager.IMPORTANCE_HIGH))
        manager.notify(13, Notification.Builder(context, "image_saver_error").setSmallIcon(R.drawable.info)
            .setContentTitle(context.getString(text))
            .setContentIntent(PendingIntent.getActivity(context, 1,
                Intent(context, app.grapheneos.camera.ui.activities.InAppGallery::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true).build())
    }
}
