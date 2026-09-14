package com.apn201.blinker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
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
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var controlBar: LinearLayout
    private lateinit var aeButton: Button

    private val receiver = Receiver()
    private lateinit var analysisExecutor: ExecutorService

    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    @Volatile private var aeAwbEnabled = true
    private var appliedLock: Boolean? = null

    private val frameTimes = ArrayDeque<Long>()   // fps, analyzer thread only

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission is required to receive", Toast.LENGTH_LONG).show()
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
            receiver.fullReset()
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
            receiver.fullReset()
            startCamera()
        }
        return bar
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            // 640x480 is what the reference works at; higher is slower and gains nothing.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, analysis)
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
        run {
            val now = System.currentTimeMillis()
            frameTimes.addLast(now)
            while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1000) frameTimes.removeFirst()
            val fps = frameTimes.size

            val t0 = System.nanoTime()
            val frame = YuvToHsv.convert(proxy, proxy.imageInfo.rotationDegrees)
            val yuvMs = ((System.nanoTime() - t0) / 1_000_000L).toInt()
            val result = receiver.process(frame, now)
            val frameMs = ((System.nanoTime() - t0) / 1_000_000L).toInt()
            if (loggedFrames < 12) {
                loggedFrames++
                Log.i(TAG, "camera ${proxy.width}x${proxy.height} rot=${proxy.imageInfo.rotationDegrees}" +
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
    }

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

    private var loggedFrames = 0

    private companion object {
        const val TAG = "Blinker"
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
