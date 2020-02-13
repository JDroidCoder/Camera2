package jdroidcoder.ua.cameralib

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import jdroidcoder.ua.camera2.CameraActivity
import jdroidcoder.ua.camera2.helper.Camera2

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//        Camera2.with(this).cameraMode(Camera2.CAMERA_MODE_BACK).cameraRotateEnabled(false)
//            .cameraFlashEnabled(false).start()
        supportFragmentManager.beginTransaction().replace(android.R.id.content,TestFragment()).commit()

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        println("MainActivity $resultCode")
//        supportFragmentManager.beginTransaction().replace(android.R.id.content,TestFragment()).commit()
    }
}
