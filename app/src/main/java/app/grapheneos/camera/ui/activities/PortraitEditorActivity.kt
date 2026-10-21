package app.grapheneos.camera.ui.activities

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.R
import app.grapheneos.camera.capturer.*
import app.grapheneos.camera.databinding.PortraitEditorBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Reversible preview from the original; Save copy leaves the existing public photo unchanged. */
class PortraitEditorActivity : AppCompatActivity() {
    private lateinit var binding: PortraitEditorBinding
    private val worker=Executors.newSingleThreadExecutor()
    private val revision=AtomicInteger()
    @Volatile private var closed=false
    private var saving=false
    private var saved: PortraitStore.Saved?=null
    private var source: Bitmap?=null
    private var displayed: Bitmap?=null
    private var enabled=true
    private var strength=8
    private var gpuPreview=false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);enableEdgeToEdge()
        binding=PortraitEditorBinding.inflate(layoutInflater);setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        if (resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE) {
            binding.editorBody.orientation=LinearLayout.HORIZONTAL
            binding.editorImageFrame.layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f)
            binding.editorControls.layoutParams=LinearLayout.LayoutParams((300*resources.displayMetrics.density).toInt(),LinearLayout.LayoutParams.MATCH_PARENT)
        }
        binding.editorToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val uri=intent.data
        if (uri==null || !PortraitStore.contains(this,uri)) { finish();return }
        worker.execute {
            try {
                val original=PortraitStore.load(applicationContext,uri);val bitmap=original.bitmap(1024)
                saved=original;source=bitmap
                val previews=linkedMapOf(0 to bitmap)
                for(level in listOf(4,8,12,16,20)) {
                    if(closed) {previews.values.filter {it!==bitmap}.forEach(Bitmap::recycle);return@execute}
                    previews[level]=render(bitmap,level)
                }
                runOnUiThread {
                    if (closed) return@runOnUiThread
                    enabled=state?.getBoolean("enabled") ?: (original.data.blur>0)
                    strength=state?.getInt("strength") ?: original.data.blur.takeIf { it>0 } ?: 8
                    binding.editorBlurEnabled.isChecked=enabled;binding.editorBlurStrength.value=strength.toFloat()
                    gpuPreview=binding.portraitImage.installLevels(previews)
                    binding.editorBlurEnabled.isEnabled=true;binding.editorBlurStrength.isEnabled=enabled
                    binding.savePortrait.isEnabled=true
                    // The editor can be opened by another app: refuse taps through an overlay.
                    binding.savePortrait.filterTouchesWhenObscured=true
                    binding.editorBlurEnabled.setOnCheckedChangeListener { _,value ->
                        enabled=value;binding.editorBlurStrength.isEnabled=value;preview()
                    }
                    binding.editorBlurStrength.addOnChangeListener { _,value,fromUser -> if(fromUser) {strength=value.toInt();preview()} }
                    binding.savePortrait.setOnClickListener { saveCopy() };preview()
                }
            } catch (_: Exception) { runOnUiThread {
                if (!closed) { Snackbar.make(binding.root,R.string.portrait_edit_unavailable,Snackbar.LENGTH_LONG).show();binding.editorProgress.visibility=View.GONE }
            } }
        }
    }
    private fun render(bitmap: Bitmap,blur: Int): Bitmap {
        val data=checkNotNull(saved).data;val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val output=RawNativeCore.Task().use { it.renderPortrait(pixels,bitmap.width,bitmap.height,data.mask,data.maskWidth,
            data.maskHeight,blur,data.depth.takeIf { it.isNotEmpty() },data.depthWidth,data.depthHeight,data.x,data.y) }
        return Bitmap.createBitmap(output,bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
    }
    private fun preview() {
        val generation=revision.incrementAndGet();val blur=if(enabled) strength else 0
        if(gpuPreview) {binding.portraitImage.strength(blur);binding.editorProgress.visibility=View.GONE;return}
        worker.execute {
            if (closed || generation!=revision.get()) return@execute
            val start=android.os.SystemClock.elapsedRealtime()
            try {
                val original=source ?: return@execute
                val bitmap=if(blur==0) original else render(original,blur)
                runOnUiThread {
                    if (closed || generation!=revision.get()) { if(bitmap!==source) bitmap.recycle();return@runOnUiThread }
                    val previous=displayed;binding.portraitImage.setImageBitmap(bitmap);displayed=bitmap
                    if(previous!==source && previous!==bitmap) previous?.recycle()
                    binding.editorProgress.visibility=View.GONE
                    if (app.grapheneos.camera.BuildConfig.DEBUG) android.util.Log.i("PortraitEditor","previewMs="+(android.os.SystemClock.elapsedRealtime()-start))
                }
            } catch (_: Exception) { runOnUiThread { if (!closed) {
                binding.editorProgress.visibility=View.GONE;Snackbar.make(binding.root,R.string.portrait_edit_unavailable,Snackbar.LENGTH_LONG).show()
            } } }
        }
    }
    private fun saveCopy() {
        if(saving || saved==null) return
        saving=true;binding.savePortrait.isEnabled=false;binding.editorBlurEnabled.isEnabled=false
        binding.editorBlurStrength.isEnabled=false;binding.editorProgress.visibility=View.VISIBLE
        val blur=if(enabled) strength else 0;revision.incrementAndGet()
        worker.execute {
            val id=UUID.randomUUID().toString();val directory=Files.createTempDirectory(cacheDir.toPath(),"portrait-edit-").toFile()
            try {
                val original=checkNotNull(saved);val full=original.bitmap()
                val rendered=try { if(blur==0) full else render(full,blur) } catch (e:Exception) {full.recycle();throw e}
                val now=System.currentTimeMillis();val spec=original.spec.copy(capturedAt=now,dateString=ProcessedPhotoSpec.date(now),
                    rotation=0,mirrorHorizontal=false,mirrorVertical=false)
                val jpeg=File(directory,"result.jpg")
                try {
                    val bytes=java.io.ByteArrayOutputStream();check(rendered.compress(Bitmap.CompressFormat.JPEG,spec.quality,bytes))
                    FileOutputStream(jpeg).use {it.write(ProcessedJpeg.metadata(bytes.toByteArray(),spec));it.fd.sync()}
                } finally {if(rendered!==full) rendered.recycle();full.recycle()}
                val data=File(directory,"portrait.bin");val buffer=original.data.copy(blur=blur).encode()
                FileOutputStream(data).use {while(buffer.hasRemaining()) check(it.channel.write(buffer)>0);it.fd.sync()}
                PortraitStore.prepare(applicationContext,id,original.source,data,spec)
                val item=PhotoFileStore.save(applicationContext,listOf(PhotoFileStore.Photo(jpeg,".jpg","image/jpeg")),spec.storage,Date(now)).single()
                PortraitStore.commit(applicationContext,id,item.uri)
                runOnUiThread { if(!closed) {setResult(RESULT_OK,Intent().setData(item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("photo",item));finish()} }
            } catch (_: Exception) {
                PortraitStore.discard(applicationContext,id)
                runOnUiThread {if(!closed) {saving=false;binding.savePortrait.isEnabled=true;binding.editorBlurEnabled.isEnabled=true
                    binding.editorBlurStrength.isEnabled=enabled;binding.editorProgress.visibility=View.GONE
                    Snackbar.make(binding.root,R.string.portrait_save_failed,Snackbar.LENGTH_LONG).show()}}
            } finally {directory.deleteRecursively()}
        }
    }
    override fun onSaveInstanceState(out: Bundle) {out.putBoolean("enabled",enabled);out.putInt("strength",strength);super.onSaveInstanceState(out)}
    override fun onDestroy() {
        closed=true;revision.incrementAndGet();binding.portraitImage.clearPortrait();binding.portraitImage.setImageBitmap(null)
        worker.execute {if(displayed!==source) displayed?.recycle();source?.recycle();source=null;displayed=null};worker.shutdown()
        super.onDestroy()
    }
}
