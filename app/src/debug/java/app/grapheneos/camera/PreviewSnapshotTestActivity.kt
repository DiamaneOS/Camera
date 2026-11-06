package app.grapheneos.camera

import android.app.Activity
import android.os.Bundle
import app.grapheneos.camera.capturer.PortraitPreviewDiagnostics

/** No path/URI parameters; asks the already visible Portrait preview for one private diagnostic. */
class PreviewSnapshotTestActivity:Activity() {
    override fun onCreate(state:Bundle?) {super.onCreate(state);PortraitPreviewDiagnostics.requested=true;finish()}
}
