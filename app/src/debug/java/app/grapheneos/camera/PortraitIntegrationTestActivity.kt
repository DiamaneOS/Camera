package app.grapheneos.camera

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import app.grapheneos.camera.ui.activities.ExternalPortraitEditorActivity
import org.json.JSONObject
import java.io.File

/** Selected-photo external editor smoke test. Shell-only, absent from release. */
class PortraitIntegrationTestActivity : AppCompatActivity() {
    private val editor=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        File(File(filesDir,"processing-test").apply { mkdirs() },"portrait-integration-result.json").writeText(JSONObject().put("result",result.resultCode)
            .put("returnedPhoto",result.data?.data!=null).put("customPhotoExtra",result.data?.hasExtra("photo") ?: false).toString())
        finish()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(state!=null) return
        val uri=checkNotNull(intent.data)
        val reply=contentResolver.call(android.net.Uri.parse("content://$packageName.portrait"),"createPortraitEdit",uri.toString(),null)
        check(reply?.getBoolean("editable")==true)
        editor.launch(Intent(this,ExternalPortraitEditorActivity::class.java).setData(uri).putExtra("token",reply.getString("token")))
    }
}
