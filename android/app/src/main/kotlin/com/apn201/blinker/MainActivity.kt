package com.apn201.blinker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.apn201.blinker.core.Receiver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receive screen: CameraX ImageAnalysis feeds each frame (as an upright HSV frame) to the
 * pure-Kotlin core Receiver; the result drives the HUD overlay. See ANDROID_PORT_SPEC
 * sections 3 and 4. Send mode (spec 3.3) is a separate, optional phase and not wired here.
 *
 * Threading: the Receiver is not thread-safe and is only ever touched on the analyzer
 * thread. UI actions that need a reset set [resetRequested]; the analyzer performs it at
 * the start of its next frame.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var controlBar: LinearLayout
    private lateinit var aeButton: Button

    private val receiver = Receiver()                 // analyzer thread only
    private lateinit var analysisExecutor: ExecutorService

    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    @Volatile private var aeAwbEnabled = true
    @Volatile private var appliedLock: Boolean? = null
    @Volatile private var resetRequested = false

    private val frameTimes = ArrayDeque<Long>()       // fps, analyzer thread only
    private var lastRotationDegrees = -1              // analyzer thread only
    private var loggedFrames = 0                      // analyzer thread only

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission is required to receive", Toast.LENGTH_LONG).show()
        }

    /**
     * The activity survives rotation (configChanges), so CameraX must be told the new display
     * rotation explicitly. A DisplayListener also catches 180-degree flips, which do not
     * change the screen size and so never produce a configuration change.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val display = previewView.display ?: return
            if (display.displayId != displayId) return
            previewUseCase?.targetRotation = display.rotation
            analysisUseCase?.targetRotation = display.rotation
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisExecutor = Executors.newSingleThreadExecutor()

        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = OverlayView(this)
        controlBar = buildControlBar()

        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(controlBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        // Keep the HUD clear of the status bar and the bottom control bar / gesture area.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            overlay.setSystemInsets(bars.top, bars.bottom)
            val p = (8 * resources.displayMetrics.density).toInt()
            controlBar.setPadding(p, p, p, p + bars.bottom)
            insets
        }
        controlBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            overlay.setBottomBarHeight(v.height)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onStop()
    }

    private fun buildControlBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(185, 0, 0, 0))
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        fun mk(text: String, onClick: (Button) -> Unit): Button {
            val b = Button(this)
            b.text = text
            b.textSize = 12f
            b.setOnClickListener { onClick(b) }
            bar.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
            return b
        }
        mk("Re-acquire") {
            resetRequested = true
            Toast.makeText(this, "Re-acquiring from scratch", Toast.LENGTH_SHORT).show()
        }
        aeButton = mk("AE/AWB: On") {
            aeAwbEnabled = !aeAwbEnabled
            it.text = if (aeAwbEnabled) "AE/AWB: On" else "AE/AWB: Off"
            appliedLock = null   // force re-apply on the next frame
        }
        mk("Flip") {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            resetRequested = true
            startCamera()
        }
        return bar
    }

    private fun currentRotation(): Int {
        previewView.display?.let { return it.rotation }
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val rotation = currentRotation()

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            // 640x480 is what the reference works at; higher is slower and gains nothing.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, analysis)
                previewUseCase = preview
                analysisUseCase = analysis
                appliedLock = null
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            analyzeFrame(proxy)
        } catch (t: Throwable) {
            // An analyzer that throws silently just stops updating the HUD, which looks
            // identical to "camera not working". Never let that be invisible again.
            Log.e(TAG, "frame analysis failed", t)
        } finally {
            proxy.close()
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        // Resets happen here, on the only thread that touches the Receiver. A rotation change
        // also forces one: the lock box and flicker history are in the old frame's pixel
        // coordinates and would point at the wrong place in the rotated frame.
        val rotationDegrees = proxy.imageInfo.rotationDegrees
        if (resetRequested || (lastRotationDegrees != -1 && rotationDegrees != lastRotationDegrees)) {
            resetRequested = false
            receiver.fullReset()
        }
        lastRotationDegrees = rotationDegrees

        val now = System.currentTimeMillis()
        frameTimes.addLast(now)
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1000) frameTimes.removeFirst()
        val fps = frameTimes.size

        val t0 = System.nanoTime()
        val frame = YuvToHsv.convert(proxy, rotationDegrees)
        val yuvMs = ((System.nanoTime() - t0) / 1_000_000L).toInt()
        val result = receiver.process(frame, now)
        val frameMs = ((System.nanoTime() - t0) / 1_000_000L).toInt()
        if (loggedFrames < 12) {
            loggedFrames++
            Log.i(TAG, "camera ${proxy.width}x${proxy.height} rot=$rotationDegrees" +
                    " -> working ${frame.width}x${frame.height}, total ${frameMs}ms" +
                    " (yuv ${yuvMs}ms, ${receiver.timingSummary()}) flickArea=${result.flickerArea}")
        }

        val dec = receiver.activeDecoder()
        val a = receiver.assembler
        overlay.update(
            HudState(
                box = result.box,
                srcW = frame.width,
                srcH = frame.height,
                locked = result.locked,
                active = result.active,
                cls = result.cls,
                fps = fps,
                frameMs = frameMs,
                msPerBit = dec.measuredMsPerBit(),
                colorsDesc = receiver.colors.describe(),
                boxV = result.v,
                boxSwing = result.swing,
                statesSeen = result.statesSeen,
                reason = result.reason,
                symA = receiver.symCount["A"] ?: 0,
                symB = receiver.symCount["B"] ?: 0,
                symSync = receiver.symCount["SYNC"] ?: 0,
                bufferBits = dec.collectedBits.size,
                totalBits = dec.totalBits,
                hdrTries = dec.headerAttempts,
                chunkTries = dec.chunkAttempts,
                lastHeader = dec.lastHeader,
                polarity = receiver.polarity,
                progress = a.progress(),
                progressFrac = a.total?.let { a.chunks.size.toDouble() / it } ?: 0.0,
                chunksOk = receiver.chunksOk,
                chunksSeen = receiver.chunksSeen,
                segments = a.richSegments(8)
            )
        )

        val desired = aeAwbEnabled && result.locked
        if (desired != appliedLock) {
            appliedLock = desired
            ContextCompat.getMainExecutor(this).execute { applyAeAwbLock(desired) }
        }
    }

    // androidx.annotation.OptIn, not kotlin.OptIn: ExperimentalCamera2Interop is an androidx
    // RequiresOptIn marker, enforced by Android lint (UnsafeOptInUsageError) rather than by
    // the Kotlin compiler - which is why kotlin.OptIn only produced a "no effect" warning.
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyAeAwbLock(lock: Boolean) {
        val cam = camera ?: return
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
                    .build()
            )
        } catch (_: Exception) {
            // best-effort: the relative-brightness threshold copes without it
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "Blinker"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
