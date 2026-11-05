package app.grapheneos.camera.capturer

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A photo while recording at a forced 60 fps. A photo stream would drop the camera to about 30 fps,
 * so the photo is a copy of the preview frame that already runs beside the video: one preview
 * sized frame with video tuning and an exposure of at most 1/60 s, softer and noisier than a photo.
 */
internal object PreviewSnapshot {
    enum class Route { PHOTO_STREAM, PREVIEW_COPY, UNAVAILABLE }

    /** Only a forced rate recording copies the preview. Everything else keeps its photo stream or has none. */
    fun route(hasPhotoStream: Boolean, recording: Boolean, forcedRate: Boolean): Route = when {
        hasPhotoStream -> Route.PHOTO_STREAM
        recording && forcedRate -> Route.PREVIEW_COPY
        else -> Route.UNAVAILABLE
    }

    /**
     * The width and height of the copy. The camera turns the preview buffers by the sensor
     * orientation for display, and the copy keeps that turn.
     */
    fun copySize(bufferWidth: Int, bufferHeight: Int, sensorOrientation: Int): Pair<Int, Int> {
        require(bufferWidth > 0 && bufferHeight > 0 && sensorOrientation % 90 == 0)
        return if (sensorOrientation % 180 == 90) bufferHeight to bufferWidth else bufferWidth to bufferHeight
    }

    /**
     * The clockwise turn that makes the copy upright, for a device rotation given as a
     * Surface.ROTATION_ value. The copy is upright when the device is in its natural orientation.
     */
    fun rotationDegrees(surfaceRotation: Int): Int {
        require(surfaceRotation in 0..3)
        return (360 - surfaceRotation * 90) % 360
    }

    /**
     * Whether to flip the upright copy left to right. The front preview is mirrored, and front
     * photos are mirrored only when the user saves them as previewed.
     */
    fun mirror(frontFacing: Boolean, saveAsPreviewed: Boolean): Boolean = frontFacing && !saveAsPreviewed

    /** At most one copy at a time. A press while a copy runs is ignored. */
    class Gate {
        private val busy = AtomicBoolean(false)

        fun tryStart(): Boolean = busy.compareAndSet(false, true)

        fun finish() {
            busy.set(false)
        }
    }
}
