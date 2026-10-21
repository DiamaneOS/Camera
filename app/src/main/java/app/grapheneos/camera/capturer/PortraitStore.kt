package app.grapheneos.camera.capturer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** App-private originals survive queue pruning; deletion of a photo removes its editing data. */
internal object PortraitStore {
    @Volatile private var lastPurge=0L
    private fun root(context: Context) = File(context.filesDir, "portrait-originals")
    // Gallery scans the Files table; capture publication uses Images. Both refer to the same
    // MediaStore row within a volume, so editing data must not depend on the table alias.
    private fun canonical(context: Context,uri: Uri): Uri {
        val path=uri.pathSegments
        var volume=path.firstOrNull().orEmpty()
        if(uri.authority==android.provider.MediaStore.AUTHORITY && volume=="external") {
            try {
                context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.MediaColumns.VOLUME_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) {val column=it.getColumnIndex(android.provider.MediaStore.MediaColumns.VOLUME_NAME)
                        if(column>=0 && !it.isNull(column)) volume=it.getString(column)}
                }
            } catch (_: Exception) { }
        }
        return if(uri.authority==android.provider.MediaStore.AUTHORITY &&
            ((path.size==3 && path[1]=="file") || (path.size==4 && path[1]=="images" && path[2]=="media")) &&
            path.last().toLongOrNull()!=null) android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Files.getContentUri(volume),path.last().toLong()) else uri
    }
    private fun rawKey(uri: Uri) = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun key(context: Context,uri: Uri) = rawKey(canonical(context,uri))
    fun readGrantAliases(context: Context,uri: Uri): List<Uri> {
        val normalized=canonical(context,uri);val path=normalized.pathSegments
        if(normalized.authority!=android.provider.MediaStore.AUTHORITY || path.size!=3 || path[1]!="file") return listOf(uri)
        val aliases=MediaPhotoAliases.forRow(path[0],path[2]).map(Uri::parse).toMutableList()
        // The synthetic external volume is accepted only if its row resolves to
        // the same actual volume. Matching row numbers on different volumes is insufficient.
        for(candidate in MediaPhotoAliases.forRow("external",path[2]).map(Uri::parse))
            if(canonical(context,candidate)==normalized) aliases.add(candidate)
        aliases.add(uri);return aliases.distinct()
    }
    private fun directory(context: Context,uri: Uri): File {
        val target=File(root(context),key(context,uri))
        if(target.isDirectory) return target
        val normalized=canonical(context,uri);val path=normalized.pathSegments
        // Preserve editing data made by the development build before table aliases were normalized.
        if(normalized.authority==android.provider.MediaStore.AUTHORITY && path.size==3 && path[1]=="file") {
            val legacy=android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.getContentUri(path[0]),path[2].toLong())
            File(root(context),rawKey(legacy)).takeIf {it.isDirectory}?.let {return it}
        }
        return target
    }
    private fun staged(context: Context, id: String): File {
        require(id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        return File(root(context), ".pending-$id")
    }
    fun contains(context: Context, uri: Uri): Boolean = try {
        val directory=directory(context,uri);val manifest=File(directory,"photo.json")
        if(manifest.length() !in 1..16*1024 || !File(directory,"source.jpg").isFile || !File(directory,"portrait.bin").isFile) false
        else {
            val spec=JSONObject(manifest.readText()).getJSONObject("spec")
            context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                val name=it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst() && name>=0 && !it.isNull(name) && it.getString(name)=="IMG_"+spec.getString("date")+".jpg"
            } ?: false
        }
    } catch (_: Exception) {false}
    private fun publishedHash(context: Context,uri: Uri): String {
        val digest=MessageDigest.getInstance("SHA-256");var total=0L
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer=ByteArray(64*1024)
            while(true) {val length=input.read(buffer);if(length<0) break;total+=length;require(total<=20*1024*1024);digest.update(buffer,0,length)}
        }
        require(total>0);return digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
    }
    private fun write(file: File, bytes: ByteArray) { FileOutputStream(file).use { it.write(bytes);it.fd.sync() } }
    fun prepare(context: Context, id: String, source: File, data: File, spec: ProcessedPhotoSpec) {
        require(source.length() in 1..20*1024*1024 && data.length() in 40..PortraitData.MAX_BYTES)
        val root=root(context).apply { check(isDirectory || mkdirs()) }
        check(StatFs(root.path).availableBytes>source.length()+data.length()+256L*1024*1024)
        val directory=staged(context,id);directory.deleteRecursively();check(directory.mkdir())
        try {
            for ((input,name) in listOf(source to "source.jpg",data to "portrait.bin")) {
                input.inputStream().use { stream -> FileOutputStream(File(directory,name)).use { stream.copyTo(it);it.fd.sync() } }
            }
            write(File(directory,"photo.json"),JSONObject().put("schema",1).put("spec",spec.json()).toString().toByteArray())
        } catch (error: Exception) { directory.deleteRecursively();throw error }
    }
    fun commit(context: Context, id: String, uri: Uri) {
        val source=staged(context,id);if (!source.isDirectory) return
        val destination=File(root(context),key(context,uri))
        if (destination.exists()) { source.deleteRecursively();return }
        val manifest=File(source,"photo.json");val json=JSONObject(manifest.readText())
        json.put("uri",uri.toString()).put("publishedSha256",publishedHash(context,uri));write(manifest,json.toString().toByteArray())
        check(source.renameTo(destination))
    }
    fun discard(context: Context,id: String) { staged(context,id).deleteRecursively() }
    fun remove(context: Context,uri: Uri) { directory(context,uri).deleteRecursively() }
    /**
     * Unblurred originals go away with their photo. An empty MediaStore query, or for a document
     * an empty or missing result, establishes deletion. Permission failures keep the original.
     * Independently, at most [MAX_ORIGINALS] originals are kept, none older than [MAX_AGE_MS].
     */
    fun purgeDeleted(context: Context) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastPurge<60_000) return
        lastPurge=now
        val kept=arrayListOf<File>()
        for(directory in root(context).listFiles().orEmpty()) {
            if(!directory.name.matches(Regex("[0-9a-f]{64}"))) continue
            try {
                val file=File(directory,"photo.json");if(file.length() !in 1..16*1024) continue
                val uri=Uri.parse(JSONObject(file.readText()).getString("uri"))
                val deleted=if(uri.authority==android.provider.MediaStore.AUTHORITY)
                    context.contentResolver.query(uri,arrayOf("_id"),null,null,null)?.use {it.count==0} ?: false
                else documentMissing(context,uri)
                if(deleted) directory.deleteRecursively() else kept.add(directory)
            } catch (_: Exception) { }
        }
        expired(kept.map { it to it.lastModified() },System.currentTimeMillis()).forEach { it.deleteRecursively() }
    }
    /**
     * Missing only when the provider says so while its tree still answers. A removed SD card or a
     * stopped cloud provider, a null answer or no permission all count as unknown.
     */
    private fun documentMissing(context: Context,uri: Uri): Boolean {
        val gone = try {
            context.contentResolver.query(uri,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)
                ?.use {it.count==0} ?: return false
        } catch (_: java.io.FileNotFoundException) {true}
        catch (_: IllegalArgumentException) {true}
        catch (_: Exception) {return false}
        return gone && treeAnswers(context,uri)
    }
    private fun treeAnswers(context: Context,uri: Uri): Boolean = try {
        val tree=android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,android.provider.DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(tree,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)
            ?.use {it.moveToFirst()} ?: false
    } catch (_: Exception) {false}
    const val MAX_ORIGINALS=100
    const val MAX_AGE_MS=365L*24*60*60*1000
    /** The oldest entries beyond the count cap, plus every entry older than the age cap. */
    internal fun <T> expired(entries: List<Pair<T,Long>>,now: Long): List<T> {
        val sorted=entries.sortedByDescending {it.second}
        return sorted.filterIndexed {index,(_,time) -> index>=MAX_ORIGINALS || now-time>MAX_AGE_MS}.map {it.first}
    }
    data class Saved(val source: File,val dataFile: File,val spec: ProcessedPhotoSpec,val data: PortraitData) {
        fun bitmap(maximum: Int = Int.MAX_VALUE): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder,info,_ ->
            require(info.size.width==data.width && info.size.height==data.height)
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            if (maxOf(info.size.width,info.size.height)>maximum) {
                val scale=maximum.toFloat()/maxOf(info.size.width,info.size.height)
                decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(2),(info.size.height*scale).toInt().coerceAtLeast(2))
            }
        }
    }
    fun load(context: Context,uri: Uri): Saved {
        val directory=directory(context,uri);val manifest=File(directory,"photo.json")
        require(manifest.length() in 1..16*1024)
        val json=JSONObject(manifest.readText());require(json.getInt("schema")==1 && canonical(context,Uri.parse(json.getString("uri")))==canonical(context,uri))
        require(contains(context,uri))
        json.optString("publishedSha256").takeIf {it.isNotEmpty()}?.let {require(publishedHash(context,uri)==it)}
        val source=File(directory,"source.jpg");val data=File(directory,"portrait.bin")
        require(source.length() in 1..20*1024*1024 && data.length() in 40..PortraitData.MAX_BYTES)
        return Saved(source,data,ProcessedPhotoSpec.fromJson(json.getJSONObject("spec")),PortraitData.decode(ByteBuffer.wrap(data.readBytes())))
    }
}
