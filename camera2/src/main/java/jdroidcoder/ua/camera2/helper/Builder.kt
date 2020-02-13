package jdroidcoder.ua.camera2.helper

abstract class Builder : BaseBuilder() {
    fun cameraMode(cameraMode: String): Builder {
        config?.putExtra(CAMERA_MODE, cameraMode)
        return this
    }

    fun cameraRotateEnabled(isRotateEnabled: Boolean): Builder {
        config?.putExtra(CAMERA_ROTATE_ENABLED, isRotateEnabled)
        return this
    }

    fun cameraFlashEnabled(isFlashEnabled: Boolean): Builder {
        config.putExtra(CAMERA_FLASH_ENABLED, isFlashEnabled)
        return this
    }

    abstract fun start()
}