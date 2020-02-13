package jdroidcoder.ua.camera2.helper

import android.media.Image
import androidx.exifinterface.media.ExifInterface

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception

internal class ImageSaver(
    private val image: Image,
    private val file: File,
    private val isFrontCamera: Boolean = false
) : Runnable {

    override fun run() {
        val buffer = image.planes[0].buffer

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(file).apply {
                write(bytes)
            }
            if (isFrontCamera) {
                try {
                    val exif = ExifInterface(file.absolutePath)
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, "" + 2)
                    exif.saveAttributes()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            image.close()
            output?.let {
                try {
                    it.close()
                } catch (e: IOException) {
                }
            }
        }
    }
}