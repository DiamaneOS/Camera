package app.grapheneos.camera.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import app.grapheneos.camera.capturer.PortraitCapabilitiesProvider

/** Entry for a gallery with a one-use capability for its selected, readable photo. */
class ExternalPortraitEditorActivity : AppCompatActivity() {
    private val editor=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val returned=result.data?.data?.let {uri -> Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        setResult(result.resultCode,returned);finish()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(state?.getBoolean("delegated")==true) return
        val uri=intent.data
        val uid=try {callingPackage?.let {packageManager.getApplicationInfo(it,0).uid}} catch (_: Exception) {null}
        if(uri==null || uid==null || !PortraitCapabilitiesProvider.consume(intent.getStringExtra("token"),uid,uri)) {finish();return}
        editor.launch(Intent(this,PortraitEditorActivity::class.java).setData(uri))
    }
    override fun onSaveInstanceState(out: Bundle) {out.putBoolean("delegated",true);super.onSaveInstanceState(out)}
}
