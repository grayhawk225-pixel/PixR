package com.pixr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rafale RAW prête à être fusionnée : trames 16 bits d'une région + tout ce qu'il faut pour le rendu. */
class RawJob(
    val frames: List<ShortArray>,
    val w: Int,
    val h: Int,
    val cfa: Int,
    val blacks: IntArray,
    val white: Int,
    val gains: FloatArray,
    val ccm: FloatArray,
    val factor: Int,
    val relCrop: IntArray,
    val textMode: Boolean,
    val tone: FloatArray,
    val rotationDeg: Int,
    val exif: CameraExif
)

/** Développement d'une rafale RAW : fusion native, rotation, export unique dans Pictures/PixR. */
object RawDeveloper {
    fun develop(context: Context, job: RawJob): CameraOutcome {
        return try {
            check(NativeEngine.available) { "Moteur natif indisponible" }
            val pixels = NativeEngine.fuseRawBurst(
                job.frames.toTypedArray(), job.w, job.h, job.cfa, job.blacks, job.white,
                job.gains, job.ccm, job.factor, job.relCrop, job.textMode, job.tone
            ) ?: error("Fusion RAW refusée par le moteur")
            val ow = (job.relCrop[2] - job.relCrop[0]) * job.factor
            val oh = (job.relCrop[3] - job.relCrop[1]) * job.factor
            var bmp = Bitmap.createBitmap(pixels, ow, oh, Bitmap.Config.ARGB_8888)
            if (job.rotationDeg != 0) {
                val m = Matrix().apply { postRotate(job.rotationDeg.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val note = CameraExifFormat.note(true, job.frames.size)
            val saved = Exporter.save(context, bmp, null, job.factor, ExportFormat.JPEG98, note, job.exif)
                ?: return CameraOutcome(false, "Enregistrement impossible", null, null)
            val scale = 480f / maxOf(bmp.width, bmp.height)
            val thumb = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            CameraOutcome(true, "Enregistré dans Pictures/PixR (${job.frames.size} trames, ${bmp.width}×${bmp.height})", thumb, saved.uri)
        } catch (t: Throwable) {
            CameraOutcome(false, "Échec : ${t.message}", null, null)
        }
    }
}

/** File d'attente disque des rafales à développer plus tard (fichiers binaires dans filesDir/pending). */
object RawJobStore {
    private const val MAGIC = 0x50495852 // "PIXR"

    private fun dir(context: Context): File = File(context.filesDir, "pending").apply { mkdirs() }

    fun pending(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".pxj") }?.sortedBy { it.name } ?: emptyList()

    fun write(context: Context, job: RawJob): File {
        val target = File(dir(context), "${System.currentTimeMillis()}.pxj")
        val tmp = File(dir(context), target.name + ".part")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(job.w); o.writeInt(job.h); o.writeInt(job.cfa); o.writeInt(job.white)
            o.writeInt(job.factor); o.writeBoolean(job.textMode); o.writeInt(job.rotationDeg)
            job.blacks.forEach { o.writeInt(it) }
            job.gains.forEach { o.writeFloat(it) }
            job.ccm.forEach { o.writeFloat(it) }
            job.relCrop.forEach { o.writeInt(it) }
            o.writeInt(job.tone.size); job.tone.forEach { o.writeFloat(it) }
            val e = job.exif
            o.writeUTF(e.make); o.writeUTF(e.model); o.writeUTF(e.dateTime)
            o.writeBoolean(e.fNumber != null); o.writeFloat(e.fNumber ?: 0f)
            o.writeBoolean(e.exposureTimeSec != null); o.writeDouble(e.exposureTimeSec ?: 0.0)
            o.writeBoolean(e.iso != null); o.writeInt(e.iso ?: 0)
            o.writeBoolean(e.focalLengthMm != null); o.writeFloat(e.focalLengthMm ?: 0f)
            o.writeInt(job.frames.size)
            val buf = ByteBuffer.allocate(job.w * job.h * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (f in job.frames) {
                buf.clear(); buf.asShortBuffer().put(f)
                o.write(buf.array(), 0, job.w * job.h * 2)
            }
        }
        check(tmp.renameTo(target)) { "Écriture de la rafale impossible" }
        return target
    }

    fun read(file: File): RawJob = DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { i ->
        check(i.readInt() == MAGIC) { "Fichier de rafale invalide" }
        val w = i.readInt(); val h = i.readInt(); val cfa = i.readInt(); val white = i.readInt()
        val factor = i.readInt(); val text = i.readBoolean(); val rot = i.readInt()
        val blacks = IntArray(4) { i.readInt() }
        val gains = FloatArray(4) { i.readFloat() }
        val ccm = FloatArray(9) { i.readFloat() }
        val crop = IntArray(4) { i.readInt() }
        val tone = FloatArray(i.readInt()) { i.readFloat() }
        val make = i.readUTF(); val model = i.readUTF(); val date = i.readUTF()
        val f = i.readBoolean().let { has -> i.readFloat().takeIf { has } }
        val ex = i.readBoolean().let { has -> i.readDouble().takeIf { has } }
        val iso = i.readBoolean().let { has -> i.readInt().takeIf { has } }
        val fl = i.readBoolean().let { has -> i.readFloat().takeIf { has } }
        val n = i.readInt()
        val bytes = ByteArray(w * h * 2)
        val frames = ArrayList<ShortArray>(n)
        repeat(n) {
            i.readFully(bytes)
            val s = ShortArray(w * h)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
            frames += s
        }
        RawJob(frames, w, h, cfa, blacks, white, gains, ccm, factor, crop, text, tone, rot,
            CameraExif(make, model, date, f, ex, iso, fl))
    }

    /** Programme le développement en tâche de fond (batterie non faible), à la suite des précédents. */
    fun schedule(context: Context) {
        val req = OneTimeWorkRequestBuilder<PixRDevelopWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("pixr_develop", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

/** Développe les rafales en attente, une par une, en respectant les mêmes règles batterie/chauffe que le lot. */
class PixRDevelopWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        var done = 0
        var failed = 0
        while (true) {
            val next = RawJobStore.pending(applicationContext).firstOrNull() ?: break
            val b = DeviceState.battery(applicationContext)
            val pause = BatchRules.pauseReason(b.percent, b.charging, DeviceState.thermalStatus(applicationContext))
            if (pause != null) return@withContext Result.retry()
            val ok = try {
                RawDeveloper.develop(applicationContext, RawJobStore.read(next)).success
            } catch (_: Throwable) {
                false
            }
            // Une rafale qui échoue (fichier corrompu, mémoire) est retirée : pas de boucle infinie.
            next.delete()
            if (ok) done++ else failed++
        }
        Result.success(workDataOf("done" to done, "failed" to failed))
    }
}
