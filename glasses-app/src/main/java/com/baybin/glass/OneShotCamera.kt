package com.baybin.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Live viewfinder plus one JPEG per [capture]. The session stays bound while [open]
 * so a second tap does not pay camera start-up again.
 *
 * Why CameraX and a preview surface: on the RG glasses' Qualcomm HAL, a hand-built
 * Camera2 session (JPEG + an ImageReader warm-up stream) never returns the JPEG; requests
 * pile up until the camera provider aborts. Preview + ImageCapture is the combination
 * 镜译 already runs on this hardware. TextureView is used instead of a 1px SurfaceView so
 * the viewfinder is visible and a still can be drawn over it without unbinding.
 *
 * ImageCapture is asked for 1024x768 at quality 80, which the camera produces directly,
 * so no Bitmap is decoded on the capture path. [scaleDown] only runs if a camera hands
 * back something larger. Rotation is not applied to the JPEG; [Shot.rotation] goes to the
 * phone. The viewfinder uses that same rotation so the picture is upright on the lens.
 */
class OneShotCamera(private val context: Context, private val previewView: TextureView) {

    class Shot(
        val jpeg: ByteArray,
        /** Clockwise degrees the image must be rotated to be upright. */
        val rotation: Int,
        /** 0 when the preview was already running. */
        val openMs: Long,
        /** 0 when auto-exposure had already settled. */
        val exposeMs: Long,
        /** capture() call → JPEG bytes in hand. */
        val totalMs: Long,
    )

    /** Always called on the main thread, exactly once per [capture]. */
    fun interface Callback {
        fun onShot(shot: Shot?, error: String?)
    }

    private class Pending(
        val cb: Callback,
        val tCall: Long,
        val hadFrame: Boolean,
        val wasSettled: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "baybin-jpeg") }
    private val owner = AlwaysResumed()
    private var provider: ProcessCameraProvider? = null
    private var surfaceReady = false
    private var opened = false
    private var released = false
    private var imageCapture: ImageCapture? = null
    private var aeSettled = false
    private var frames = 0
    private var tFirstFrame = 0L
    private var shooting = false
    private var shotGen = 0
    private var pending: Pending? = null
    private var active: Pending? = null
    private var warmWaits = 0
    private var bufferSize: Size? = null
    private var bufferRotation = 0

    // Initialising CameraX takes ~3s the first time. Start it now (it doesn't open the
    // camera) so the first tap isn't 3s slower than the rest.
    private val providerFuture = ProcessCameraProvider.getInstance(context).also { f ->
        f.addListener({ provider = try { f.get() } catch (_: Exception) { null } }, mainExecutor)
    }

    init {
        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceReady = true
                if (opened) provider?.let { bind(it) }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                surfaceReady = false
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    /** Main thread. Bind and leave the preview running. */
    fun open() {
        if (released || opened) return
        opened = true
        val p = provider
        if (p != null) {
            bind(p)
            return
        }
        providerFuture.addListener({
            val got = try {
                providerFuture.get()
            } catch (e: Exception) {
                null
            }
            provider = got
            if (got == null) {
                failPending("camera provider")
                return@addListener
            }
            if (opened) bind(got)
        }, mainExecutor)
    }

    /** Main thread. Unbind until the next [open]. In-flight capture fails once. */
    fun close() {
        opened = false
        shotGen++
        shooting = false
        aeSettled = false
        frames = 0
        tFirstFrame = 0L
        imageCapture = null
        main.removeCallbacks(warmForce)
        main.removeCallbacks(shotTimeout)
        val waiting = listOfNotNull(pending, active)
        pending = null
        active = null
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
        }
        waiting.forEach { it.cb.onShot(null, "paused") }
    }

    /** Main thread only. Unbinds and stops everything; the instance is unusable afterwards. */
    fun release() {
        if (released) return
        released = true
        close()
        owner.destroy()
        io.shutdown()
    }

    /**
     * Main thread. If the preview is already exposed, the JPEG starts immediately.
     * Otherwise waits up to [MAX_WARM_MS] for auto-exposure, then shoots anyway.
     */
    fun capture(cb: Callback) {
        if (released) {
            cb.onShot(null, "released")
            return
        }
        if (shooting || pending != null) {
            cb.onShot(null, "busy")
            return
        }
        if (!opened) open()
        warmWaits = 0
        pending = Pending(cb, SystemClock.elapsedRealtime(), tFirstFrame != 0L, aeSettled)
        if (aeSettled && imageCapture != null) tryShoot()
        else main.postDelayed(warmForce, MAX_WARM_MS)
    }

    /** Upright still of what was just sent, small enough for the lens. Main-thread [cb]. */
    fun frame(jpeg: ByteArray, rotation: Int, cb: (Bitmap?) -> Unit) {
        if (released) {
            cb(null)
            return
        }
        io.execute {
            val bmp = upright(jpeg, rotation)
            main.post {
                if (released) bmp?.recycle() else cb(bmp)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind(p: ProcessCameraProvider) {
        if (!opened || released || imageCapture != null || !surfaceReady) return
        val texture = previewView.surfaceTexture
        if (texture == null) {
            surfaceReady = false
            return
        }
        aeSettled = false
        frames = 0
        tFirstFrame = 0L

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(selector(PREVIEW_SIZE))
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(aeWatcher)
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(mainExecutor) { request ->
            val st = previewView.surfaceTexture
            if (!surfaceReady || st == null) {
                request.willNotProvideSurface()
                return@setSurfaceProvider
            }
            bufferSize = request.resolution
            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            request.setTransformationInfoListener(mainExecutor) { info ->
                bufferRotation = info.rotationDegrees
                Log.i(TAG, "preview ${request.resolution.width}x${request.resolution.height} rot=$bufferRotation")
                applyTransform()
            }
            val surface = Surface(st)
            request.provideSurface(surface, mainExecutor) { surface.release() }
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(JPEG_QUALITY)
            .setTargetRotation(Surface.ROTATION_0)
            .setResolutionSelector(selector(Size(MAX_SIDE, MAX_SIDE * 3 / 4)))
            .build()
        imageCapture = capture

        try {
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        } catch (e: Exception) {
            imageCapture = null
            failPending("bind failed: ${e.message}")
        }
    }

    /** Sensor buffer is sideways (270). Rotate it so the viewfinder matches the JPEG. */
    private fun applyTransform() {
        val buffer = bufferSize ?: return
        val viewW = previewView.width
        val viewH = previewView.height
        if (viewW == 0 || viewH == 0) return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        val rotation = ((bufferRotation % 360) + 360) % 360
        if (rotation == 90 || rotation == 270) {
            val bufferRect = RectF(0f, 0f, buffer.height.toFloat(), buffer.width.toFloat())
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewH.toFloat() / buffer.height, viewW.toFloat() / buffer.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(rotation.toFloat(), cx, cy)
        } else if (rotation == 180) {
            matrix.postRotate(180f, cx, cy)
        }
        previewView.setTransform(matrix)
    }

    private val aeWatcher = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            // This HAL often stays in SEARCHING. Once the preview has been up long enough,
            // leave it settled: clearing it made every later tap wait another 800 ms.
            if (!opened || shooting || aeSettled) return
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            main.post { onPreviewFrame(ae) }
        }
    }

    private fun onPreviewFrame(ae: Int?) {
        if (!opened || aeSettled || shooting) return
        val now = SystemClock.elapsedRealtime()
        if (frames++ == 0) tFirstFrame = now
        val settled = ae == null ||
            ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
            ae == CaptureResult.CONTROL_AE_STATE_LOCKED
        if ((settled && frames >= MIN_WARM_FRAMES) || now - tFirstFrame >= MAX_WARM_MS) {
            aeSettled = true
            Log.d(TAG, "ae ready frames=$frames ae=$ae")
            tryShoot()
        }
    }

    private val warmForce: Runnable = Runnable {
        val p = pending ?: return@Runnable
        if (imageCapture == null) {
            if (++warmWaits >= 6) {
                pending = null
                p.cb.onShot(null, "camera timeout")
            } else {
                main.postDelayed(warmForce, MAX_WARM_MS)
            }
            return@Runnable
        }
        aeSettled = true
        tryShoot()
    }

    private fun tryShoot() {
        val p = pending ?: return
        val capture = imageCapture ?: return
        if (!aeSettled) return
        pending = null
        main.removeCallbacks(warmForce)
        active = p
        shooting = true
        val gen = ++shotGen
        val tShoot = SystemClock.elapsedRealtime()
        main.postDelayed(shotTimeout, TIMEOUT_MS)
        capture.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val raw = try {
                    val buf = image.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } finally {
                    image.close()
                }
                val jpeg = scaleDown(raw)
                val now = SystemClock.elapsedRealtime()
                val shot = Shot(
                    jpeg,
                    rotation,
                    if (p.hadFrame) 0 else (tFirstFrame - p.tCall).coerceAtLeast(0),
                    if (p.wasSettled) 0 else (tShoot - p.tCall).coerceAtLeast(0),
                    now - p.tCall,
                )
                main.post { deliver(gen, p, shot, null) }
            }

            override fun onError(e: ImageCaptureException) {
                main.post { deliver(gen, p, null, "capture failed: ${e.imageCaptureError} ${e.message}") }
            }
        })
    }

    private val shotTimeout: Runnable = Runnable {
        val p = active ?: return@Runnable
        active = null
        shooting = false
        shotGen++
        Log.w(TAG, "capture failed: camera timeout")
        p.cb.onShot(null, "camera timeout")
    }

    private fun deliver(gen: Int, p: Pending, shot: Shot?, error: String?) {
        if (gen != shotGen) return
        if (active !== p) return
        active = null
        shooting = false
        main.removeCallbacks(shotTimeout)
        if (error != null) Log.w(TAG, "capture failed: $error")
        p.cb.onShot(shot, error)
    }

    private fun failPending(error: String) {
        Log.w(TAG, "capture failed: $error")
        val p = pending ?: return
        pending = null
        main.removeCallbacks(warmForce)
        p.cb.onShot(null, error)
    }

    private fun selector(target: Size) = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()

    /** Only for cameras that hand back more than 1024px: decode subsampled, scale, re-encode, recycle. */
    private fun scaleDown(jpeg: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (max(bounds.outWidth, bounds.outHeight) <= MAX_SIDE) return jpeg
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return jpeg
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded
        return try {
            ByteArrayOutputStream(jpeg.size / 4).also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun upright(jpeg: ByteArray, rotation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FRAME_SIDE) sample *= 2
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = ((rotation % 360) + 360) % 360
        if (degrees == 0) return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (out !== src) src.recycle()
        return out
    }

    /** CameraX wants a LifecycleOwner; binding is managed by hand, so this one just stays resumed. */
    private class AlwaysResumed : LifecycleOwner {
        // lifecycle-runtime resolves to 2.0.0 here, which has handleLifecycleEvent but not setCurrentState.
        private val registry = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        override fun getLifecycle(): Lifecycle = registry
        fun destroy() = registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    companion object {
        private const val TAG = "BayBin"
        const val MAX_SIDE = 1024
        const val JPEG_QUALITY = 80
        /** Same preview size 镜译 uses on this camera. */
        private val PREVIEW_SIZE = Size(960, 720)
        private const val MIN_WARM_FRAMES = 3
        private const val MAX_WARM_MS = 800L
        private const val TIMEOUT_MS = 5000L
        private const val FRAME_SIDE = 640
    }
}
