package jdroidcoder.ua.camera2.helper

import android.content.Intent

abstract class BaseBuilder {
    companion object {
        const val CAMERA_MODE = "camera_mode"
        const val CAMERA_ROTATE_ENABLED = "camera_rotate_enabled"
        const val CAMERA_FLASH_ENABLED = "camera_flash_enabled"
    }

    protected var config: Intent = Intent()

    init {
        config?.putExtra(CAMERA_MODE, Camera2.CAMERA_MODE_BACK)
        config?.putExtra(CAMERA_ROTATE_ENABLED, true)
        config?.putExtra(CAMERA_FLASH_ENABLED, true)
    }
}