package com.pixr.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

data class ExportResult(val uri: Uri, val exifWritten: Boolean)

object Exporter {
    private val copiedTags = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF
    )

    /**
     * Enregistre dans Pictures/PixR (JPEG 98 % ou PNG sans perte) et recopie les EXIF de la source.
     * Pour le PNG, l'écriture EXIF est tentée ; si le format ne la supporte pas, [ExportResult.exifWritten] vaut false.
     */
    fun save(
        context: Context,
        bitmap: Bitmap,
        sourceUri: Uri?,
        factor: Int,
        format: ExportFormat = ExportFormat.JPEG98,
        note: String = ExifPolicy.processingNote(factor),
        cameraExif: CameraExif? = null
    ): ExportResult? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PixR_${System.currentTimeMillis()}.${format.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val compress = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(compress, format.quality, it) } ?: false
            if (!ok) {
                resolver.delete(uri, null, null)
                return null
            }
            val exifWritten = runCatching { writeExif(context, uri, sourceUri, bitmap, note, cameraExif) }.isSuccess
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            // Pour le JPEG, l'échec EXIF reste une vraie erreur : on la remonte via exifWritten=false.
            return ExportResult(uri, exifWritten)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun writeExif(context: Context, dest: Uri, source: Uri?, bitmap: Bitmap, note: String, cam: CameraExif?) {
        val resolver = context.contentResolver
        val srcExif = source?.let { s -> runCatching { resolver.openInputStream(s)?.use { ExifInterface(it) } }.getOrNull() }
        resolver.openFileDescriptor(dest, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            if (srcExif != null) {
                for (tag in copiedTags) srcExif.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
            }
            if (cam != null) {
                exif.setAttribute(ExifInterface.TAG_MAKE, cam.make)
                exif.setAttribute(ExifInterface.TAG_MODEL, cam.model)
                exif.setAttribute(ExifInterface.TAG_DATETIME, cam.dateTime)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, cam.dateTime)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, cam.dateTime)
                cam.fNumber?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, CameraExifFormat.decimal(it.toDouble(), 2)) }
                cam.exposureTimeSec?.let { exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, CameraExifFormat.exposure(it)) }
                cam.iso?.let { exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
                cam.focalLengthMm?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, CameraExifFormat.decimal(it.toDouble(), 2)) }
            }
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PixR")
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, note)
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
            exif.saveAttributes()
        }
    }
}
