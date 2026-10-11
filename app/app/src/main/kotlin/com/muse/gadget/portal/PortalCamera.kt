package com.muse.gadget.portal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream

/** One foreground still at a time. No file storage, background stream or shutter bypass. */
@Suppress("DEPRECATION")
class PortalCamera {
    private val handler=Handler(Looper.getMainLooper())
    private var camera:Camera?=null
    private var surface:SurfaceTexture?=null
    private var completion:((ByteArray?,String?)->Unit)?=null
    private val timeout=Runnable{finish(null,"Camera capture timed out.")}

    fun capture(done:(ByteArray?,String?)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(completion!=null){done(null,"Camera is already taking a photo.");return}
        completion=done
        try {
            // Portal's RGB camera is ID 0; do not open the separate AI/IR camera.
            val c=Camera.open(0);camera=c
            val p=c.parameters
            val size=p.supportedPictureSizes.filter{it.width<=1280&&it.height<=1280}.maxByOrNull{it.width*it.height}
                ?:p.supportedPictureSizes.minByOrNull{it.width*it.height}!!
            p.setPictureSize(size.width,size.height);p.jpegQuality=75
            c.parameters=p
            surface=SurfaceTexture(0);c.setPreviewTexture(surface);c.startPreview()
            handler.postDelayed(timeout,10000)
            handler.postDelayed({
                if(camera===c)try{c.takePicture(null,null,Camera.PictureCallback{bytes,_->
                    try{finish(compact(bytes),null)}catch(_:Exception){finish(null,"Could not encode the photo.")}
                })}catch(_:Exception){finish(null,"Camera unavailable. Check the physical privacy switch.")}
            },700)
        }catch(_:Exception){finish(null,"Camera unavailable. Check permission and the physical privacy switch.")}
    }

    private fun compact(bytes:ByteArray):ByteArray {
        var bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:error("Invalid camera image")
        try {
            while(true) {
                for(quality in listOf(75,55,35)) {
                    val out=ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out))
                    if(out.size()<=128*1024)return out.toByteArray()
                }
                check(bitmap.width>320&&bitmap.height>240)
                val smaller=Bitmap.createScaledBitmap(bitmap,bitmap.width*3/4,bitmap.height*3/4,true)
                bitmap.recycle();bitmap=smaller
            }
        }finally{bitmap.recycle()}
    }
    private fun finish(bytes:ByteArray?,error:String?) {
        handler.removeCallbacks(timeout)
        try{camera?.stopPreview()}catch(_:Exception){}
        camera?.release();camera=null;surface?.release();surface=null
        val done=completion;completion=null;done?.invoke(bytes,error)
    }
    fun cancel(){finish(null,"Camera stopped because the app left the foreground.")}
}
