package jdroidcoder.ua.cameralib

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import jdroidcoder.ua.camera2.helper.Camera2
import kotlinx.android.synthetic.main.activity_main.*

class TestFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater?.inflate(R.layout.activity_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Camera2.with(this).cameraMode(Camera2.CAMERA_MODE_FRONT).cameraRotateEnabled(true)
            .cameraFlashEnabled(false).start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
        println("TestFragment $resultCode")
        if(null != data && data.hasExtra(Camera2.PHOTO_URL)){
            println("photo_url  ${data.getStringExtra(Camera2.PHOTO_URL)}")
//            Glide.with(this).load(data.getStringExtra(Camera2.PHOTO_URL)).diskCacheStrategy(
//                DiskCacheStrategy.NONE).skipMemoryCache(true).into(photo)
            photo?.setImageURI(Uri.parse(data.getStringExtra(Camera2.PHOTO_URL)))
        }
    }
}