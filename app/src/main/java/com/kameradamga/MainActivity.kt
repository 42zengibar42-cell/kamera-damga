package com.kameradamga

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.location.Location
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var source: StampSource

    private lateinit var previewView: PreviewView
    private lateinit var grid: GridOverlay
    private lateinit var shutter: View
    private lateinit var txtCountdown: TextView
    private lateinit var btnFlash: TextView
    private lateinit var btnTimer: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val overlayThread = HandlerThread("overlay").apply { start() }
    private val overlayHandler by lazy { Handler(overlayThread.looper) }

    private var provider: ProcessCameraProvider? = null
    @Volatile private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var effect: OverlayEffect? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var flashState = 0 // 0 kapalı, 1 otomatik, 2 açık
    private var countingDown = false
    private var recIndex: Int? = null

    private var deviceRotation = Surface.ROTATION_0
    @Volatile private var drawRotation = Surface.ROTATION_0
    @Volatile private var cfg = StampConfig.default()
    @Volatile private var liveLines: List<String> = emptyList()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        source.start()
        if (hasCamera()) startCamera() else toast(getString(R.string.perm_needed))
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    o in 45..134 -> Surface.ROTATION_270
                    o in 135..224 -> Surface.ROTATION_180
                    o in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = deviceRotation
                if (recording == null) drawRotation = deviceRotation
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            liveLines = source.lines(recIndex ?: (prefs.index + 1))
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        source = StampSource(this)

        previewView = findViewById(R.id.previewView)
        grid = findViewById(R.id.grid)
        shutter = findViewById(R.id.shutter)
        txtCountdown = findViewById(R.id.txtCountdown)
        btnFlash = findViewById(R.id.btnFlash)
        btnTimer = findViewById(R.id.btnTimer)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        findViewById<View>(R.id.btnSwitch).setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnFlash.setOnClickListener {
            flashState = (flashState + 1) % 3
            applyFlash()
        }
        btnTimer.setOnClickListener {
            prefs.timerSec = when (prefs.timerSec) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            updateTimerLabel()
        }
        modePhoto.setOnClickListener { setMode(false) }
        modeVideo.setOnClickListener { setMode(true) }
        shutter.setOnClickListener { onShutter() }

        setupTouch()
        updateTimerLabel()
        updateModeUi()

        if (hasCamera()) startCamera() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        cfg = prefs.config()
        grid.visibility = if (prefs.grid) View.VISIBLE else View.GONE
        source.start()
        orientationListener.enable()
        main.removeCallbacks(ticker)
        main.post(ticker)
        if (provider != null && recording == null) bindCamera()
    }

    override fun onPause() {
        super.onPause()
        recording?.stop()
        source.stop()
        orientationListener.disable()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        effect?.close()
        overlayThread.quitSafely()
        worker.shutdown()
    }

    // ---------- İzinler ----------

    private fun hasCamera() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = provider ?: return
        provider.unbindAll()
        effect?.close()
        effect = null

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val ratio = if (videoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val group = UseCaseGroup.Builder().addUseCase(preview)
        val targets: Int
        if (videoMode) {
            val q = when (prefs.videoQuality) {
                "uhd" -> Quality.UHD
                "hd" -> Quality.HD
                else -> Quality.FHD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc
            imageCapture = null
            group.addUseCase(vc)
            targets = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
        } else {
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(deviceRotation)
                .build()
            imageCapture = ic
            videoCapture = null
            group.addUseCase(ic)
            targets = CameraEffect.PREVIEW
        }

        val ov = OverlayEffect(targets, 0, overlayHandler) { t -> Log.e("KameraDamga", "Overlay hatası", t) }
        ov.setOnDrawListener { frame -> drawFrame(frame) }
        effect = ov
        group.addEffect(ov)

        try {
            camera = provider.bindToLifecycle(this, selector, group.build())
        } catch (e: Exception) {
            Log.e("KameraDamga", "Kamera bağlanamadı", e)
            toast(e.message ?: "Kamera hatası")
            return
        }
        applyFlash()
    }

    /** Canlı önizleme ve video karelerine damgayı çizer. */
    private fun drawFrame(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val bw = frame.size.width
        val bh = frame.size.height
        val rot = camera?.cameraInfo?.getSensorRotationDegrees(drawRotation) ?: 90

        // Kamera tamponu hedeften (video: 16:9, önizleme: 4:3) daha geniş olabilir;
        // görünen ve kaydedilen kısım ortadaki kesittir. Damgayı o kesite göre çiz.
        var target = if (videoMode) 16f / 9f else 4f / 3f
        if (bw < bh) target = 1f / target
        val bufAspect = bw.toFloat() / bh
        val cropW: Float
        val cropH: Float
        if (bufAspect > target) {
            cropH = bh.toFloat(); cropW = bh * target
        } else {
            cropW = bw.toFloat(); cropH = bw / target
        }

        canvas.save()
        val uw: Float
        val uh: Float
        val ucw: Float
        val uch: Float
        when (rot) {
            90 -> {
                canvas.translate(0f, bh.toFloat()); canvas.rotate(-90f)
                uw = bh.toFloat(); uh = bw.toFloat(); ucw = cropH; uch = cropW
            }
            180 -> {
                canvas.translate(bw.toFloat(), bh.toFloat()); canvas.rotate(180f)
                uw = bw.toFloat(); uh = bh.toFloat(); ucw = cropW; uch = cropH
            }
            270 -> {
                canvas.translate(bw.toFloat(), 0f); canvas.rotate(90f)
                uw = bh.toFloat(); uh = bw.toFloat(); ucw = cropH; uch = cropW
            }
            else -> {
                uw = bw.toFloat(); uh = bh.toFloat(); ucw = cropW; uch = cropH
            }
        }
        canvas.translate((uw - ucw) / 2f, (uh - uch) / 2f)
        StampRenderer.draw(canvas, ucw.toInt(), uch.toInt(), liveLines, cfg)
        canvas.restore()
        return true
    }

    private fun applyFlash() {
        btnFlash.text = when (flashState) {
            0 -> "⚡✕"
            1 -> "⚡A"
            else -> "⚡"
        }
        if (videoMode) {
            camera?.cameraControl?.enableTorch(flashState == 2)
        } else {
            imageCapture?.flashMode = when (flashState) {
                1 -> ImageCapture.FLASH_MODE_AUTO
                2 -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    private fun updateTimerLabel() {
        val s = prefs.timerSec
        btnTimer.text = if (s == 0) "⏱" else "⏱$s"
    }

    private fun setMode(video: Boolean) {
        if (recording != null || countingDown || video == videoMode) return
        videoMode = video
        updateModeUi()
        bindCamera()
    }

    private fun updateModeUi() {
        modePhoto.alpha = if (videoMode) 0.5f else 1f
        modeVideo.alpha = if (videoMode) 1f else 0.5f
        shutter.setBackgroundResource(if (videoMode) R.drawable.shutter_video else R.drawable.shutter_photo)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value ?: return true
                camera?.cameraControl?.setZoomRatio(z.zoomRatio * d.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (prefs.tapShoot) {
                    onShutter()
                } else {
                    val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                }
                return true
            }
        })
        previewView.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    // ---------- Çekim ----------

    private fun onShutter() {
        if (videoMode) {
            if (recording != null) recording?.stop() else startWithTimer { startRecording() }
        } else {
            startWithTimer { takePhoto() }
        }
    }

    private fun startWithTimer(action: () -> Unit) {
        if (countingDown) return
        val secs = prefs.timerSec
        if (secs <= 0) {
            action()
            return
        }
        countingDown = true
        txtCountdown.visibility = View.VISIBLE
        var left = secs
        val r = object : Runnable {
            override fun run() {
                if (left > 0) {
                    txtCountdown.text = left.toString()
                    left--
                    main.postDelayed(this, 1000)
                } else {
                    txtCountdown.visibility = View.GONE
                    countingDown = false
                    action()
                }
            }
        }
        main.post(r)
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val idx = prefs.index + 1
        prefs.index = idx
        val lines = source.lines(idx)
        val loc = source.location
        val config = cfg
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val mirror = front && prefs.mirrorFront
        val keep = prefs.keepOriginal
        val file = File.createTempFile("cap", ".jpg", cacheDir)

        if (prefs.shutterSound) MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            worker,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    val ok = try {
                        processPhoto(file, lines, loc, config, mirror, keep)
                    } catch (e: Throwable) {
                        Log.e("KameraDamga", "Foto işlenemedi", e)
                        false
                    } finally {
                        file.delete()
                    }
                    runOnUiThread { toast(getString(if (ok) R.string.saved else R.string.save_error)) }
                }

                override fun onError(e: ImageCaptureException) {
                    file.delete()
                    Log.e("KameraDamga", "Çekim hatası", e)
                    runOnUiThread { toast(getString(R.string.save_error)) }
                }
            }
        )
    }

    private fun processPhoto(
        file: File, lines: List<String>, loc: Location?, config: StampConfig,
        mirror: Boolean, keepOriginal: Boolean,
    ): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        if (keepOriginal) {
            insertMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMG_${stamp}_orig.jpg", "image/jpeg") { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        }

        val opts = BitmapFactory.Options().apply { inMutable = true }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return false

        val orientation = ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (mirror) m.postScale(-1f, 1f)
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)

        StampRenderer.draw(Canvas(bmp), bmp.width, bmp.height, lines, config)

        val uri = insertMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMG_$stamp.jpg", "image/jpeg") { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bmp.recycle()
        if (uri != null && loc != null) writeGps(uri, loc)
        return uri != null
    }

    private fun writeGps(uri: Uri, loc: Location) {
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setGpsInfo(loc)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w("KameraDamga", "GPS EXIF yazılamadı", e)
        }
    }

    private fun insertMedia(collection: Uri, name: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { write(it) } ?: return null
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            null
        }
    }

    // ---------- Video ----------

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val idx = prefs.index + 1
        prefs.index = idx
        recIndex = idx
        drawRotation = deviceRotation
        vc.targetRotation = deviceRotation

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$stamp.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending = vc.output.prepareRecording(this, opts)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                recIndex = null
                drawRotation = deviceRotation
                shutter.setBackgroundResource(R.drawable.shutter_video)
                toast(getString(if (ev.hasError()) R.string.save_error else R.string.saved))
            }
        }
        shutter.setBackgroundResource(R.drawable.shutter_recording)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
