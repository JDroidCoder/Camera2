package jdroidcoder.ua.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jdroidcoder.ua.camera2.helper.BaseBuilder
import jdroidcoder.ua.camera2.helper.Camera2.PHOTO_URL
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CameraActivity : AppCompatActivity(), Executor {

    companion object {
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    //    private var cameraId = CameraX.LensFacing.BACK
//    private lateinit var preview: Preview
//    private lateinit var imageCapture: ImageCapture
    private var isFlashEnabled = false

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        cameraExecutor = Executors.newSingleThreadExecutor()
        if (allPermissionsGranted()) {
            textureView.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        rotateCamera?.setOnClickListener {
            rotateCamera()
        }
        flash?.setOnClickListener {
            flash()
        }
        lensFacing = if ("0" == intent.getStringExtra(BaseBuilder.CAMERA_MODE) ?: "0") {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        if (false == intent?.getBooleanExtra(BaseBuilder.CAMERA_ROTATE_ENABLED, true)) {
            rotateCamera?.visibility = View.GONE
        }
        if (false == intent?.getBooleanExtra(BaseBuilder.CAMERA_FLASH_ENABLED, true)) {
            flash?.visibility = View.GONE
        }
        backButton?.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        takePhoto.setOnClickListener {
            val file = File(getExternalFilesDir(null), "${System.currentTimeMillis()}.jpg")
            val metadata = ImageCapture.Metadata().apply {
            }
            if (null != imageCapture) {
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file)
                    .setMetadata(metadata)
                    .build()
                imageCapture?.takePicture(
                    outputOptions,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            setResult(
                                Activity.RESULT_OK,
                                intent.putExtra(PHOTO_URL, file.absolutePath)
                            )
                            this@CameraActivity.finish()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            exception?.printStackTrace()
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }
                    })
            }
        }
    }

    private fun flash() {
        isFlashEnabled = !isFlashEnabled
        camera?.cameraControl?.enableTorch(isFlashEnabled)
        if (isFlashEnabled) {
            flash?.setImageResource(R.drawable.ic_flashlight_on)
        } else {
            flash?.setImageResource(R.drawable.ic_flashlight_off)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun startCamera() {
        this?.let {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(it)
            cameraProviderFuture.addListener(Runnable {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                camera?.cameraControl?.enableTorch(isFlashEnabled)
                if (false == camera?.cameraInfo?.hasFlashUnit() || false == intent?.getBooleanExtra(
                        BaseBuilder.CAMERA_FLASH_ENABLED,
                        true
                    )
                ) {
                    flash?.visibility = View.INVISIBLE
                } else {
                    flash?.visibility = View.VISIBLE
                }
            }, ContextCompat.getMainExecutor(it))
        }
    }

    private fun bindCameraUseCases() {
        val metrics = DisplayMetrics().also { textureView?.display?.getRealMetrics(it) }
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        val rotation = textureView?.display?.rotation
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        preview = rotation?.let {
            Preview.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(it)
                .build()
        }
        imageCapture = rotation?.let {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(
                    if (true == camera?.cameraInfo?.hasFlashUnit() && isFlashEnabled) {
                        ImageCapture.FLASH_MODE_ON
                    } else {
                        ImageCapture.FLASH_MODE_OFF
                    }
                )
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(it)
                .build()
        }

        val imageAnalyzer = rotation?.let { r ->
            ImageAnalysis.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .setTargetRotation(r)
                .build()
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, imageAnalyzer
            )
            preview?.setSurfaceProvider(textureView.createSurfaceProvider())
        } catch (exc: Exception) {
            exc.printStackTrace()
        }
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    @SuppressLint("RestrictedApi")
    private fun rotateCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        try {
            startCamera()
        } catch (exc: Exception) {
            exc.printStackTrace()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

//    @SuppressLint("RestrictedApi")
//    private fun captureImage() {
//        val imageCaptureConfig = ImageCaptureConfig.Builder()
//            .apply {
//                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
//                setLensFacing(cameraId)
//                setFlashMode(
//                    if (true == CameraX.getCameraInfo(cameraId)?.isFlashAvailable?.value && isFlashEnabled)
//                        FlashMode.ON
//                    else
//                        FlashMode.OFF
//                )
//                setTargetRotation(textureView.display.rotation)
//            }.build()
//        imageCapture = ImageCapture(imageCaptureConfig)
//        CameraX.bindToLifecycle(this, imageCapture)
//        takePhoto.setOnClickListener {
//            val file = File(getExternalFilesDir(null), "${System.currentTimeMillis()}.jpg")
//            val metadata = ImageCapture.Metadata().apply {
////                isReversedHorizontal = cameraId == CameraX.LensFacing.FRONT
//            }
//            imageCapture.takePicture(
//                file,
//                metadata,
//                this,
//                object : ImageCapture.OnImageSavedListener {
//                    override fun onImageSaved(file: File) {
//                        setResult(Activity.RESULT_OK, intent.putExtra(PHOTO_URL, file.absolutePath))
//                        this@CameraActivity.finish()
//                    }
//
//                    override fun onError(
//                        imageCaptureError: ImageCapture.ImageCaptureError,
//                        message: String,
//                        cause: Throwable?
//                    ) {
//                        cause?.printStackTrace()
//                        setResult(Activity.RESULT_CANCELED)
//                        finish()
//                    }
//                })
//        }
//}

    override fun execute(command: Runnable) {
        command.run()
    }

//    private fun updateTransform() {
//        val matrix = Matrix()
//        val centerX = textureView.width / 2f
//        val centerY = textureView.height / 2f
//        val rotationDegrees = when (textureView.display.rotation) {
//            Surface.ROTATION_0 -> 0
//            Surface.ROTATION_90 -> 90
//            Surface.ROTATION_180 -> 180
//            Surface.ROTATION_270 -> 270
//            else -> return
//        }
//        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)
//        textureView.setTransform(matrix)
//    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                textureView?.post { startCamera() }
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
//        imageCapture.let {
//            CameraX.unbind(imageCapture)
//        }
        cameraExecutor.shutdown()
    }
}