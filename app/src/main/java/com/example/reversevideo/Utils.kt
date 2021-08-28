package com.example.reversevideo

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.absoluteValue

fun performVideoSearch(activity: AppCompatActivity, code: Int) {
    performFileSearch(activity, code, false,
        "video/*",
        "video/3gpp",
        "video/dl",
        "video/dv",
        "video/fli",
        "video/m4v",
        "video/mpeg",
        "video/mp4",
        "video/quicktime",
        "video/vnd.mpegurl",
        "video/x-la-asf",
        "video/x-mng",
        "video/x-ms-asf",
        "video/x-ms-wm",
        "video/x-ms-wmx",
        "video/x-ms-wvx",
        "video/x-msvideo",
        "video/x-webex")
}

fun isServiceRunning(context: Context, clazz: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    for (i in am.getRunningServices(Integer.MAX_VALUE)) {
        if (i.service.className == clazz.name)
            return true
    }

    return false
}
fun  performFileSearch(activity: AppCompatActivity, code: Int, multiple: Boolean, type: String,
                      vararg mimetype: String) {
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        this.type = type
        putExtra(Intent.EXTRA_MIME_TYPES, mimetype)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
    }

    activity.startActivityForResult(intent, code)
}

fun needsStoragePermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(
        Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
}

@RequiresApi(Build.VERSION_CODES.M)
fun requestStoragePermission(activity: AppCompatActivity, code: Int) {
    activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), code)
}

fun getSupportedVideoSize(mediaCodec: MediaCodec, mime: String, preferredResolution: Size): Size {
    // First check if exact combination supported
    if (mediaCodec.codecInfo.getCapabilitiesForType(mime)
            .videoCapabilities.isSizeSupported(
                preferredResolution.width,
                preferredResolution.height
            )
    )
        return preferredResolution

    val resolutions = arrayListOf(
        Size(176, 144),
        Size(320, 240),
        Size(320, 180),
        Size(640, 360),
        Size(720, 480),
        Size(1280, 720),
        Size(1920, 1080)
    )

    val pix = preferredResolution.width * preferredResolution.height
    val preferredAspect = preferredResolution.width.toFloat() / preferredResolution.height.toFloat()

    val nearestToFurthest = resolutions.sortedWith(
        compareBy(
            {
                pix - it.width * it.height
            },
            {
                val aspect = if (it.width < it.height) it.width.toFloat() / it.height.toFloat()
                else it.height.toFloat() / it.width.toFloat()
                (preferredAspect - aspect).absoluteValue
            })
    )

    for (size in nearestToFurthest) {
        if (mediaCodec.codecInfo.getCapabilitiesForType(mime)
                .videoCapabilities.isSizeSupported(size.width, size.height)
        )
            return size
    }

    throw RuntimeException("Couldn't find supported resolution")
}
