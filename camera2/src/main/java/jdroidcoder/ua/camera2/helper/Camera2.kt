package jdroidcoder.ua.camera2.helper

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import jdroidcoder.ua.camera2.CameraActivity

object Camera2 {
    const val CAMERA_PHOTO_RESULT = 19999
    const val PHOTO_URL = "photo_url"
    const val CAMERA_MODE_FRONT = "1"
    const val CAMERA_MODE_BACK = "0"

    fun with(activity: AppCompatActivity) = ActivityBuilder(activity)

    fun with(fragment: Fragment) = FragmentBuilder(fragment)

    class ActivityBuilder(private val activity: AppCompatActivity) : Builder() {
        override fun start() {
            activity?.startActivityForResult(
                Intent(activity, CameraActivity::class.java).putExtras(config),
                CAMERA_PHOTO_RESULT
            )
        }
    }

    class FragmentBuilder(private val fragment: Fragment) : Builder() {
        override fun start() {
            fragment?.startActivityForResult(
                Intent(fragment.activity, CameraActivity::class.java).putExtras(config),
                CAMERA_PHOTO_RESULT
            )
        }
    }
}