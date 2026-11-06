package app.grapheneos.camera.util

import java.io.File
import java.io.RandomAccessFile

/** Edit only the inline orientation SHORT written by DngCreator, preserving sensor data. */
internal object DngOrientation {
    fun normalizeCopy(file: File): Int = replace(file, allowMissing = true) { 1 }

    fun mirrorHorizontally(file: File) {
        replace(file) { orientation ->
            // CameraX versions which already mirror RAW must not be mirrored twice.
            when (orientation) { 1 -> 2; 3 -> 4; 6 -> 5; 8 -> 7; else -> orientation }
        }
    }

    private fun replace(file: File, allowMissing: Boolean = false, transform: (Int) -> Int): Int = RandomAccessFile(file, "rw").use { data ->
        require(data.length() >= 8)
        val first = data.readUnsignedByte(); val second = data.readUnsignedByte()
        val little = first == 0x49 && second == 0x49
        require(little || (first == 0x4d && second == 0x4d))
        fun short(): Int {
            val a = data.readUnsignedByte(); val b = data.readUnsignedByte()
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun long(): Long {
            val a = short().toLong(); val b = short().toLong()
            return if (little) a or (b shl 16) else (a shl 16) or b
        }
        require(short() == 42)
        val offset = long()
        require(offset in 8..data.length() - 2)
        data.seek(offset)
        val count = short()
        require(count <= 4096 && offset + 2 + count * 12L <= data.length())
        repeat(count) { index ->
            data.seek(offset + 2 + index * 12L)
            val tag = short(); val type = short(); val length = long()
            if (tag == 274) {
                require(type == 3 && length == 1L)
                val valueOffset = data.filePointer
                val orientation = short()
                require(orientation in 1..8)
                val replacement = transform(orientation)
                require(replacement in 1..8)
                if (replacement != orientation) {
                    data.seek(valueOffset)
                    data.write(if (little) byteArrayOf(replacement.toByte(), 0) else byteArrayOf(0, replacement.toByte()))
                }
                return@use orientation
            }
        }
        require(allowMissing) { "DNG orientation missing" }
        1
    }
}
