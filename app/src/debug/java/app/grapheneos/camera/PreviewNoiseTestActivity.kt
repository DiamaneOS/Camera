package app.grapheneos.camera

import android.app.Activity
import android.os.Bundle
import app.grapheneos.camera.capturer.PortraitPreviewDiagnostics

/** DUMP-only, bounded, in-memory test override. -1 restores the original options. */
class PreviewNoiseTestActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(intent.hasExtra("spatial")) {
            val value=intent.getIntExtra("spatial",0);require(value in 0..100)
            PortraitPreviewDiagnostics.spatialLumaRequest=value
        } else if(intent.hasExtra("temporal"))PortraitPreviewDiagnostics.temporalPreviewRequest=intent.getBooleanExtra("temporal",true)
        else {
            val mode=intent.getIntExtra("mode",-1)
            require(mode in -1..4)
            PortraitPreviewDiagnostics.noiseReductionRequest=mode
        }
        finish()
    }
}
