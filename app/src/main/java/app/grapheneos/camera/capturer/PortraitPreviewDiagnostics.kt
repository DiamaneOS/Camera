package app.grapheneos.camera.capturer

/** Triggered only by a DUMP-protected debug activity; release call sites are removed. */
internal object PortraitPreviewDiagnostics {
    @Volatile var requested=false
    @Volatile var capturedAtMs=0L
    @Volatile var stillCapturedAtMs=0L
    @Volatile var stillNoiseReductionMode=-1
    @Volatile var stillExposureNs=0L
    @Volatile var stillIso=0
    @Volatile var stillBoost=0
    @Volatile var stillAeMode=-1
    @Volatile var noiseReductionMode=-1
    @Volatile var edgeMode=-1
    @Volatile var iso=0
    @Volatile var boost=0
    @Volatile var aeMode=-1
    @Volatile var fpsLower=0
    @Volatile var fpsUpper=0
    @Volatile var exposureNs=0L
    @Volatile var frameDurationNs=0L
    @Volatile var noiseReductionRequest:Int?=null
    @Volatile var temporalPreviewRequest:Boolean?=null
    @Volatile var spatialLumaRequest:Int?=null
    @Volatile var spatialLumaApplied=0
}
