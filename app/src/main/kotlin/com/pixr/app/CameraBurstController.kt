package com.pixr.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Display
import android.view.Surface
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** Capacités réelles détectées sur la caméra arrière principale. */
class CameraInfo(
    val id: String,
    val strategy: CaptureStrategy,
    val hasRawCapability: Boolean,
    val hasBurstCapability: Boolean,
    val captureSize: Size,
    val previewSize: Size,
    val cfa: Int,
    val blackLevels: IntArray,
    val whiteLevel: Int,
    val maxZoom: Float,
    val sensorOrientation: Int,
    val forwardMatrix: FloatArray?,
    val make: String,
    val model: String,
    val exposureRange: LongRange?,
    val isoRange: IntRange?,
    val aeCompMin: Int,
    val aeCompMax: Int,
    val aeCompStep: Float
) {
    val summary: String
        get() = (if (strategy == CaptureStrategy.RAW) "RAW_SENSOR" else "Repli YUV_420_888") +
            " ${captureSize.width}×${captureSize.height}" +
            (if (strategy == CaptureStrategy.RAW) " · ${BayerMath.cfaName(cfa)} · blanc $whiteLevel" else "") +
            (if (hasBurstCapability) " · BURST_CAPTURE" else "")
}

/** Résultat d'une capture : image enregistrée (ou message d'erreur). */
class CameraOutcome(val success: Boolean, val message: String, val thumbnail: Bitmap?, val uri: Uri?)

class CameraBurstController(context: Context, private val info: CameraInfo) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val chars: CameraCharacteristics = manager.getCameraCharacteristics(info.id)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var closed = false
    private var zoom = 1f
    @Volatile private var ev = 0f
    @Volatile var look: RenderLook = RenderLook.NATURAL
    @Volatile var deferred = false
    @Volatile var sharpness: Sharpness = Sharpness.NATURAL
    @Volatile private var lastPreview: TotalCaptureResult? = null
    private var pending: Pending? = null

    private class Pending(
        val n: Int,
        val region: IntArray,
        val relCrop: IntArray,
        val factor: Int,
        val textMode: Boolean,
        val rotation: Int,
        val tone: FloatArray,
        val deferred: Boolean,
        val onDone: (CameraOutcome) -> Unit
    ) {
        val raw = ArrayList<ShortArray>()
        val yuv = ArrayList<IntArray>()
        val results = ArrayList<TotalCaptureResult>()
        var finished = false
    }

    // ------------------------------------------------------------------ ouverture / fermeture

    @SuppressLint("MissingPermission")
    fun start(texture: SurfaceTexture, report: (String) -> Unit) {
        synchronized(lock) {
            if (closed || camera != null) return
            val t = HandlerThread("pixr-camera").also { it.start() }
            thread = t
            val h = Handler(t.looper)
            handler = h
            texture.setDefaultBufferSize(info.previewSize.width, info.previewSize.height)
            val surface = Surface(texture)
            previewSurface = surface
            val format = if (info.strategy == CaptureStrategy.RAW) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
            val r = ImageReader.newInstance(info.captureSize.width, info.captureSize.height, format, BurstRules.MAX_FRAMES + 2)
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, h)
            reader = r
            try {
                manager.openCamera(info.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        synchronized(lock) {
                            if (closed) { device.close(); return }
                            camera = device
                        }
                        createSession(device, report)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        main.post { report("Caméra déconnectée") }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        main.post { report("Erreur caméra $error") }
                    }
                }, h)
            } catch (e: CameraAccessException) {
                main.post { report("Accès caméra impossible : ${e.message}") }
            } catch (e: SecurityException) {
                main.post { report("Permission caméra refusée") }
            }
        }
    }

    private fun createSession(device: CameraDevice, report: (String) -> Unit) {
        val h = handler ?: return
        val outputs = synchronized(lock) {
            val p = previewSurface
            val r = reader
            if (closed || p == null || r == null) return
            listOf(OutputConfiguration(p), OutputConfiguration(r.surface))
        }
        val executor = Executor { h.post(it) }
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                synchronized(lock) {
                    if (closed) { s.close(); return }
                    session = s
                }
                startPreview(device, s, report)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                main.post { report("Configuration de la session caméra impossible") }
            }
        }
        try {
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, cb))
        } catch (e: CameraAccessException) {
            main.post { report("Session caméra impossible : ${e.message}") }
        }
    }

    private fun startPreview(device: CameraDevice, s: CameraCaptureSession, report: (String) -> Unit) {
        val surface = synchronized(lock) { previewSurface } ?: return
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surface)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyZoom(b, zoom)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeIndex())
            previewBuilder = b
            s.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    lastPreview = result
                }
            }, handler)
        } catch (e: CameraAccessException) {
            main.post { report("Aperçu impossible : ${e.message}") }
        } catch (e: IllegalStateException) {
            main.post { report("Aperçu impossible : ${e.message}") }
        }
    }

    /** Ferme strictement session, caméra, lecteur d'images et thread (idempotent). */
    fun close() {
        val p: Pending?
        synchronized(lock) {
            if (closed) return
            closed = true
            p = pending
            pending = null
        }
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        camera = null
        reader = null
        previewSurface = null
        previewBuilder = null
        lastPreview = null
        thread?.quitSafely()
        thread = null
        handler = null
        if (p != null && !p.finished) {
            main.post { p.onDone(CameraOutcome(false, "Capture interrompue", null, null)) }
        }
    }

    // ------------------------------------------------------------------ exposition

    private fun aeIndex(): Int = ExposureMath.compensationIndex(ev, info.aeCompStep, info.aeCompMin, info.aeCompMax)

    /** Compensation d'exposition en EV : physique (AE, par pas) puis reste appliqué numériquement à la fusion. */
    fun setExposureBias(value: Float) {
        ev = value.coerceIn(-ExposureMath.MAX_EV, ExposureMath.MAX_EV)
        val b = previewBuilder ?: return
        val s = session ?: return
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeIndex())
        try {
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: CameraAccessException) {
        } catch (_: IllegalStateException) {
        }
    }

    // ------------------------------------------------------------------ zoom

    fun setZoom(z: Float) {
        zoom = z
        val b = previewBuilder ?: return
        val s = session ?: return
        applyZoom(b, z)
        try {
            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: CameraAccessException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, z: Float) {
        if (Build.VERSION.SDK_INT >= 30) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null && ZoomMath.usesZoomRatio(Build.VERSION.SDK_INT, z, range.lower, range.upper)) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                return
            }
        }
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val c = ZoomMath.cropRegion(active.width(), active.height(), z)
        b.set(
            CaptureRequest.SCALER_CROP_REGION,
            android.graphics.Rect(active.left + c[0], active.top + c[1], active.left + c[2], active.top + c[3])
        )
    }

    // ------------------------------------------------------------------ capture

    /** Déclenche une rafale ; [onDone] est appelé sur le thread principal. Retourne false si impossible. */
    fun capture(zoomStep: Float, textMode: Boolean, onDone: (CameraOutcome) -> Unit): Boolean {
        val s: CameraCaptureSession
        val device: CameraDevice
        val r: ImageReader
        synchronized(lock) {
            if (closed || pending != null) return false
            s = session ?: return false
            device = camera ?: return false
            r = reader ?: return false
        }
        val raw = info.strategy == CaptureStrategy.RAW
        val defer = deferred && raw
        val w = info.captureSize.width
        val h = info.captureSize.height
        val crop = ZoomMath.centerCrop(w, h, zoomStep)
        val region = if (raw) ZoomMath.region(crop, w, h, ALIGN_MARGIN) else crop
        val n = if (defer) {
            val frameBytes = (region[2] - region[0]).toLong() * (region[3] - region[1]) * 2L
            val budget = minOf(Runtime.getRuntime().maxMemory() * 45 / 100, 240L * 1024 * 1024)
            DeferredRules.frameCount(frameBytes, budget)
        } else BurstRules.frameCount(textMode, zoomStep)
        val rel = if (raw) ZoomMath.relative(crop, region) else intArrayOf(0, 0, crop[2] - crop[0], crop[3] - crop[1])
        val idx = aeIndex()
        val tone = ExposureMath.tone(
            look, ExposureMath.residualEv(ev, info.aeCompStep, idx),
            if (defer) DeferredRules.DENOISE_DEFERRED else DeferredRules.DENOISE_IMMEDIATE,
            sharpness.amount
        )
        val p = Pending(n, region, rel, ZoomMath.outputFactor(zoomStep), textMode, displayRotation(), tone, defer, onDone)
        try {
            val reqs = ArrayList<CaptureRequest>()
            val preview = lastPreview
            for (i in 0 until n) {
                val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(r.surface)
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
                if (textMode && preview != null) applyTextExposure(b, preview)
                reqs.add(b.build())
            }
            synchronized(lock) { pending = p }
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    synchronized(lock) { p.results.add(result) }
                    maybeFinish(p)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    fail(p, "Une trame de la rafale a échoué")
                }
            }, handler)
        } catch (e: CameraAccessException) {
            synchronized(lock) { pending = null }
            return false
        } catch (e: IllegalStateException) {
            synchronized(lock) { pending = null }
            return false
        }
        return true
    }

    private fun applyTextExposure(b: CaptureRequest.Builder, preview: TotalCaptureResult) {
        val exp = preview.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val iso = preview.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val er = info.exposureRange ?: return
        val ir = info.isoRange ?: return
        val (e2, i2) = ExposureLogic.textMode(exp, iso, er.first, er.last, ir.first, ir.last)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e2)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, i2)
    }

    private fun displayRotation(): Int {
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return when (dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireNextImage() } catch (_: IllegalStateException) { null } ?: return
        try {
            val p = synchronized(lock) { pending }
            if (p == null || p.finished) return
            val full = synchronized(lock) { p.raw.size + p.yuv.size >= p.n }
            if (full) return
            if (info.strategy == CaptureStrategy.RAW) {
                val data = extractRaw(img, p.region)
                synchronized(lock) { p.raw.add(data) }
            } else {
                val data = extractYuv(img, p.region)
                synchronized(lock) { p.yuv.add(data) }
            }
            maybeFinish(p)
        } catch (e: Throwable) {
            synchronized(lock) { pending }?.let { fail(it, "Lecture de trame impossible : ${e.message}") }
        } finally {
            img.close()
        }
    }

    private fun maybeFinish(p: Pending) {
        val go = synchronized(lock) {
            if (p.finished) return
            val got = if (info.strategy == CaptureStrategy.RAW) p.raw.size else p.yuv.size
            if (got >= p.n && p.results.size >= p.n) {
                p.finished = true
                if (pending === p) pending = null
                true
            } else false
        }
        if (go) Thread({ process(p) }, "pixr-fuse").start()
    }

    private fun fail(p: Pending, msg: String) {
        val go = synchronized(lock) {
            if (p.finished) return
            p.finished = true
            if (pending === p) pending = null
            true
        }
        if (go) main.post { p.onDone(CameraOutcome(false, msg, null, null)) }
    }

    // ------------------------------------------------------------------ extraction des trames

    private fun extractRaw(img: Image, region: IntArray): ShortArray {
        val plane = img.planes[0]
        val rw = region[2] - region[0]
        val rh = region[3] - region[1]
        val sb = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rowShorts = plane.rowStride / 2
        val out = ShortArray(rw * rh)
        for (y in 0 until rh) {
            sb.position((region[1] + y) * rowShorts + region[0])
            sb.get(out, y * rw, rw)
        }
        return out
    }

    private fun extractYuv(img: Image, region: IntArray): IntArray {
        val rw = region[2] - region[0]
        val rh = region[3] - region[1]
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val out = IntArray(rw * rh)
        for (y in 0 until rh) {
            val sy = region[1] + y
            for (x in 0 until rw) {
                val sx = region[0] + x
                val yy = yBuf.get(sy * yP.rowStride + sx * yP.pixelStride).toInt() and 0xFF
                val ci = (sy shr 1) * uP.rowStride + (sx shr 1) * uP.pixelStride
                val u = (uBuf.get(ci).toInt() and 0xFF) - 128
                val cj = (sy shr 1) * vP.rowStride + (sx shr 1) * vP.pixelStride
                val v = (vBuf.get(cj).toInt() and 0xFF) - 128
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                out[y * rw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    // ------------------------------------------------------------------ fusion + enregistrement

    private fun process(p: Pending) {
        val outcome = try {
            check(NativeEngine.available) { "Moteur natif indisponible" }
            val res = p.results[0]
            val rot = (info.sensorOrientation - p.rotation + 360) % 360
            val exif = CameraExif(
                make = info.make,
                model = info.model,
                dateTime = CameraExifFormat.exifDate(System.currentTimeMillis()),
                fNumber = res.get(CaptureResult.LENS_APERTURE),
                exposureTimeSec = res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1e9 },
                iso = res.get(CaptureResult.SENSOR_SENSITIVITY),
                focalLengthMm = res.get(CaptureResult.LENS_FOCAL_LENGTH)
            )
            if (info.strategy == CaptureStrategy.RAW) {
                val g = res.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val gains = WhiteBalance.resolve(
                    g?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) },
                    res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { arr -> FloatArray(arr.size) { arr[it].toFloat() } }
                )
                val resultCcm = res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { toMatrix(it) }
                val ccm = ColorMath.resolveCcm(if (ColorMath.isNearIdentity(resultCcm)) null else resultCcm, info.forwardMatrix)
                val noise = res.get(CaptureResult.SENSOR_NOISE_PROFILE)
                    ?.let { arr -> DoubleArray(arr.size * 2) { if (it % 2 == 0) arr[it / 2].first else arr[it / 2].second } }
                val tone = p.tone.copyOf()
                tone[3] = NoiseModel.denoiseStrength(NoiseModel.sigmaMid255(noise), p.raw.size, p.deferred)
                NoiseModel.meanSO(noise)?.let { (so, oo) -> tone[6] = so; tone[7] = oo }
                val job = RawJob(
                    frames = ArrayList(p.raw),
                    w = p.region[2] - p.region[0],
                    h = p.region[3] - p.region[1],
                    cfa = info.cfa,
                    blacks = info.blackLevels,
                    white = info.whiteLevel,
                    gains = gains,
                    ccm = ccm,
                    factor = p.factor,
                    relCrop = p.relCrop,
                    textMode = p.textMode,
                    tone = tone,
                    rotationDeg = rot,
                    exif = exif
                )
                p.raw.clear()
                if (p.deferred) {
                    RawJobStore.write(appContext, job)
                    RawJobStore.schedule(appContext)
                    val queued = RawJobStore.pending(appContext).size
                    CameraOutcome(true, "${job.frames.size} trames en file : développement en arrière-plan ($queued en attente)", null, null)
                } else {
                    RawDeveloper.develop(appContext, job)
                }
            } else {
                val rw = p.region[2] - p.region[0]
                val rh = p.region[3] - p.region[1]
                val pixels = NativeEngine.fuse(p.yuv.toTypedArray(), rw, rh, 50) ?: error("Fusion YUV refusée par le moteur")
                p.yuv.clear()
                var bmp = Bitmap.createBitmap(pixels, rw * 2, rh * 2, Bitmap.Config.ARGB_8888)
                if (rot != 0) {
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                val note = CameraExifFormat.note(false, p.n)
                val saved = Exporter.save(appContext, bmp, null, p.factor, ExportFormat.JPEG98, note, exif)
                if (saved == null) {
                    CameraOutcome(false, "Enregistrement impossible", null, null)
                } else {
                    val scale = 480f / maxOf(bmp.width, bmp.height)
                    val thumb = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                    CameraOutcome(true, "Enregistré dans Pictures/PixR (${p.n} trames, ${bmp.width}×${bmp.height})", thumb, saved.uri)
                }
            }
        } catch (t: Throwable) {
            CameraOutcome(false, "Échec : ${t.message}", null, null)
        }
        main.post { p.onDone(outcome) }
    }

    private fun toMatrix(t: ColorSpaceTransform): FloatArray {
        val m = FloatArray(9)
        for (row in 0 until 3) for (col in 0 until 3) m[row * 3 + col] = t.getElement(col, row).toFloat()
        return m
    }

    companion object {
        private const val ALIGN_MARGIN = 32

        /** Détecte la caméra arrière principale et ses capacités réelles (aucune permission requise). */
        fun detect(context: Context): CameraInfo? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var best: CameraInfo? = null
            var bestArea = -1L
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val hasRawCap = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { it.width to it.height } ?: emptyList()
                val rawPick = SizePicker.pickRaw(rawSizes)
                val strategy = StrategyChooser.choose(hasRawCap, rawPick != null)
                val captureSize: Size
                if (strategy == CaptureStrategy.RAW) {
                    captureSize = Size(rawPick!!.first, rawPick.second)
                } else {
                    val yuv = SizePicker.pickYuv(map.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height } ?: emptyList())
                        ?: continue
                    captureSize = Size(yuv.first, yuv.second)
                }
                val previewPick = SizePicker.pickPreview(
                    map.getOutputSizes(SurfaceTexture::class.java)?.map { it.width to it.height } ?: emptyList()
                ) ?: continue
                val area = captureSize.width.toLong() * captureSize.height + (if (strategy == CaptureStrategy.RAW) 1L shl 40 else 0L)
                if (area <= bestArea) continue
                bestArea = area

                val bp = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                val blacks = IntArray(4) { i -> bp?.getOffsetForIndex(i and 1, i shr 1) ?: 0 }
                val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                var maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                if (Build.VERSION.SDK_INT >= 30) {
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { maxZoom = maxOf(maxZoom, it.upper) }
                }
                val fwd = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { t ->
                    FloatArray(9).also { m ->
                        for (row in 0 until 3) for (col in 0 until 3) m[row * 3 + col] = t.getElement(col, row).toFloat()
                    }
                }
                best = CameraInfo(
                    id = id,
                    strategy = strategy,
                    hasRawCapability = hasRawCap,
                    hasBurstCapability = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE),
                    captureSize = captureSize,
                    previewSize = Size(previewPick.first, previewPick.second),
                    cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0,
                    blackLevels = blacks,
                    whiteLevel = white,
                    maxZoom = maxZoom,
                    sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    forwardMatrix = fwd,
                    make = Build.MANUFACTURER,
                    model = Build.MODEL,
                    exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper },
                    isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper },
                    aeCompMin = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.lower ?: 0,
                    aeCompMax = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.upper ?: 0,
                    aeCompStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
                )
            }
            return best
        }
    }
}
