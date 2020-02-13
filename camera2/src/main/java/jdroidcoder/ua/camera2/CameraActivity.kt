package jdroidcoder.ua.camera2

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jdroidcoder.ua.camera2.helper.BaseBuilder
import jdroidcoder.ua.camera2.helper.Camera2.CAMERA_MODE_FRONT
import jdroidcoder.ua.camera2.helper.Camera2.PHOTO_URL
import jdroidcoder.ua.camera2.helper.CompareSizesByArea
import jdroidcoder.ua.camera2.helper.ImageSaver
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class CameraActivity : AppCompatActivity() {
    companion object {
        private val ORIENTATIONS = SparseIntArray()
        private val ORIENTATIONS_FRONT = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 180)
            ORIENTATIONS.append(Surface.ROTATION_270, 270)

            ORIENTATIONS_FRONT.append(Surface.ROTATION_0, 0)
            ORIENTATIONS_FRONT.append(Surface.ROTATION_90, 90)
            ORIENTATIONS_FRONT.append(Surface.ROTATION_180, 180)
            ORIENTATIONS_FRONT.append(Surface.ROTATION_270, 270)
        }

        private const val TAG = "CameraActivity"
        private const val STATE_PREVIEW = 0
        private const val STATE_WAITING_LOCK = 1
        private const val STATE_WAITING_PRECAPTURE = 2
        private const val STATE_WAITING_NON_PRECAPTURE = 3
        private const val STATE_PICTURE_TAKEN = 4
        private const val CAMERA_PERMISSION = 10012
    }

    private var isFlashEnabled = false
    private var cameraId: String = "0"
    private var captureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private lateinit var previewSize: Size
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private lateinit var file: File
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var previewRequest: CaptureRequest
    private var state = STATE_PREVIEW
    private val cameraOpenCloseLock = Semaphore(1)
    private var flashSupported = false
    private var sensorOrientation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        cameraId = intent.getStringExtra(BaseBuilder.CAMERA_MODE) ?: "0"
        if (false == intent?.getBooleanExtra(BaseBuilder.CAMERA_ROTATE_ENABLED, true)) {
            rotateCamera?.visibility = View.GONE
        }
        if (false == intent?.getBooleanExtra(BaseBuilder.CAMERA_FLASH_ENABLED, true)) {
            flash?.visibility = View.GONE
        }
        textureView.surfaceTextureListener = surfaceTextureListener
        file = File(getExternalFilesDir(null), "photo.jpg")
        backButton?.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        flash?.setOnClickListener { this?.flash() }
        rotateCamera?.setOnClickListener { this?.rotateCamera() }
        takePhoto?.setOnClickListener { this?.takePhoto() }
    }

    private fun takePhoto() {
        lockFocus()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun requestCameraPermission() {
        if (Camera.getNumberOfCameras() > 0 || packageManager?.hasSystemFeature(
                PackageManager.FEATURE_CAMERA_ANY
            ) == true
        ) {
            if (Build.VERSION.SDK_INT >= 23) {
                if (this?.let {
                        ContextCompat.checkSelfPermission(
                            it,
                            Manifest.permission.CAMERA
                        )
                    } != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
                }
            }
        } else {
            this?.let {
                androidx.appcompat.app.AlertDialog.Builder(it)
                    .setTitle(getString(R.string.camera_error))
                    .setMessage(getString(R.string.not_have_camera))
                    .setPositiveButton(getString(R.string.ok_label)) { p0, p1 ->
                        p0?.dismiss()
                    }.show()
            }
        }
    }

    private fun setUpCameraOutputs() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return
            val largest = Collections.max(
                listOf(*map.getOutputSizes(ImageFormat.JPEG)),
                CompareSizesByArea()
            )
            imageReader = ImageReader.newInstance(
                largest.width, largest.height,
                ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?.let {
                sensorOrientation = it
            }
            previewSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), largest)
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }
            flashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!flashSupported || false == intent?.getBooleanExtra(
                    BaseBuilder.CAMERA_FLASH_ENABLED,
                    true
                )
            ) {
                flash?.visibility = View.GONE
            } else {
                flash?.visibility = View.VISIBLE
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun openCamera(width: Int, height: Int) {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }
        setUpCameraOutputs()
        configureTransform(width, height)
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundThread?.looper?.let {
            backgroundHandler = Handler(it)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(texture)
            cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )?.also { previewRequestBuilder = it }
            previewRequestBuilder.addTarget(surface)
            cameraDevice?.createCaptureSession(
                listOf(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = cameraCaptureSession
                        try {
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            previewRequest = previewRequestBuilder.build()
                            captureSession?.setRepeatingRequest(
                                previewRequest,
                                captureCallback, backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, e.toString())
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale =
                (viewHeight.toFloat() / previewSize.height).coerceAtLeast(viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun lockFocus() {
        try {
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            state = STATE_WAITING_LOCK
            captureStillPicture()
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun runPrecaptureSequence() {
        try {
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            state = STATE_WAITING_PRECAPTURE
            captureSession?.capture(
                previewRequestBuilder.build(), captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun captureStillPicture() {
        try {
            if (this == null || cameraDevice == null) return
            val rotation = this.windowManager.defaultDisplay.rotation
            val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            )?.apply {
                imageReader?.surface?.let { addTarget(it) }

                if ("0" == cameraId) {
                    set(
                        CaptureRequest.JPEG_ORIENTATION,
                        (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360
                    )
                } else {
//                    mCamera.setDisplayOrientation(90);
//val matrix =  Matrix()
//matrix.setScale(-1f, 1f)
//                    textureView?.width?.toFloat()?.let { matrix.postTranslate(it, 0f) }
//textureView.setTransform(matrix)

                    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val characteristics = manager.getCameraCharacteristics(cameraId)
                    val x =   sensorToDeviceRotation(characteristics, rotation)
                    set(
                        CaptureRequest.JPEG_ORIENTATION,
                        sensorToDeviceRotation(characteristics, rotation)
                    )

                }
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    unlockFocus()
                    setResult(Activity.RESULT_OK, intent.putExtra(PHOTO_URL, file.absolutePath))
                    this@CameraActivity.finish()
                }
            }
            captureSession?.apply {
                stopRepeating()
                abortCaptures()
                captureBuilder?.build()?.let { capture(it, captureCallback, null) }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun unlockFocus() {
        try {
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            )
            captureSession?.capture(
                previewRequestBuilder.build(), captureCallback,
                backgroundHandler
            )
            state = STATE_PREVIEW
            captureSession?.setRepeatingRequest(
                previewRequest, captureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun flash() {
        try {
            isFlashEnabled = if (!isFlashEnabled) {
                previewRequestBuilder?.set(
                    CaptureRequest.FLASH_MODE,
                    CameraMetadata.FLASH_MODE_TORCH
                )
                previewRequestBuilder?.build()
                    ?.let { captureSession?.setRepeatingRequest(it, null, null) }
                true
            } else {
                previewRequestBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                previewRequestBuilder?.build()
                    ?.let { captureSession?.setRepeatingRequest(it, null, null) }
                false
            }
            checkFlashState()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun checkFlashState() {
        if (isFlashEnabled) {
            flash?.setImageResource(R.drawable.ic_flashlight_on)
        } else {
            flash?.setImageResource(R.drawable.ic_flashlight_off)
        }
    }

    private fun rotateCamera() {
        isFlashEnabled = false
        cameraId = if ("0" == cameraId) {
            "1"
        } else {
            "0"
        }
        closeCamera()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    private fun chooseOptimalSize(
        choices: Array<Size>,
        aspectRatio: Size
    ): Size {
        val bigEnough = ArrayList<Size>()
        val w = aspectRatio.width
        val h = aspectRatio.height
        val ratio = h / w
        for (option in choices) {
            val optionRatio = option.height / option.width
            if (ratio == optionRatio) {
                bigEnough.add(option)
            }
        }
        return if (bigEnough.size > 0) {
            Collections.max(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    }
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraActivity.cameraDevice = cameraDevice
            createCameraPreviewSession()
            checkFlashState()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraActivity.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@CameraActivity.finish()
        }

    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        backgroundHandler?.post(ImageSaver(it.acquireNextImage(), file, CAMERA_MODE_FRONT == cameraId))
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (state) {
                STATE_PREVIEW -> Unit
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        state = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState == null) {
                captureStillPicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    state = STATE_PICTURE_TAKEN
                    captureStillPicture()
                } else {
                    runPrecaptureSequence()
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
    }

    private fun sensorToDeviceRotation(c: CameraCharacteristics, deviceOrientationTemp: Int): Int {
        var deviceOrientation = deviceOrientationTemp
        if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return 0
        val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        deviceOrientation = (deviceOrientation + 45) / 90 * 90
        val facingFront =
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        if (facingFront) deviceOrientation = -deviceOrientation
        return (sensorOrientation + deviceOrientation + 360) % 360
    }
}