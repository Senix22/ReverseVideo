package com.example.reversevideo

import android.app.IntentService
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

class ConvertVideo: IntentService("ConversionService") {
    override fun onHandleIntent(intent: Intent?) {
        intent?.let {
            val reverseVideoSettings = ReverseVideoSettings()
            val keyFrame = KeyFrameSettings()
            val outPath = it.getStringExtra(KEY_OUT_PATH)
            var inputVidUri = it.getParcelableExtra<Uri>(KEY_INPUT_VID_URI)
            val allKeyFrames = it.getBooleanExtra(KEY_ALL_IFRAMES, true)

            val startTime = System.currentTimeMillis()


            // Convert all frames to keyframes?
            if (allKeyFrames) {
                val tmpVidPath = cacheDir.absolutePath + "/out.vid"
                if (inputVidUri != null) {
                    contentResolver.openFileDescriptor(inputVidUri, "r").use {
                        if (it != null) {
                            KeyFrameConverter(keyFrame).convert(tmpVidPath, it.fileDescriptor)
                        }
                    }
                }
                inputVidUri = Uri.fromFile(File(tmpVidPath))
            }

            // Reverse video here
            if (inputVidUri != null) {
                contentResolver.openFileDescriptor(inputVidUri, "r").use {
                    if (it != null) {
                        if (outPath != null) {
                            ReverseVideo(reverseVideoSettings).convert(outPath, it.fileDescriptor,"ASa")
                        }
                    }
                }
            }
            Log.d(TAG, "Total processing duration=" + (System.currentTimeMillis() - startTime)/1000 +  " seconds")

            val pi = intent.getParcelableExtra<PendingIntent>(KEY_RESULT_INTENT)
            pi?.send()
        }
    }

    companion object {
        const val TAG = "VidProcService"
        const val KEY_OUT_PATH = "OUT_PATH"
        const val KEY_INPUT_VID_URI = "INPUT_VID_URI"
        const val KEY_RESULT_INTENT = "RESULT_INTENT"
        const val KEY_ALL_IFRAMES = "eALL_IFRAMES"
    }
}