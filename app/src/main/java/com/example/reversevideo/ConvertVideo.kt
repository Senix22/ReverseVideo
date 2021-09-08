package com.example.reversevideo

import android.app.IntentService
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

class ConvertVideo : IntentService("ConversionService") {
    override fun onHandleIntent(intent: Intent?) {
        intent?.let {
            val reverseVideoSettings = ReverseVideoSettings()
            val keyFrame = KeyFrameSettings()
            val outPath = it.getStringExtra(KEY_OUT_PATH)
            var inputVidUri = it.getParcelableExtra<Uri>(KEY_INPUT_VID_URI)
            val allKeyFrames = it.getBooleanExtra(KEY_ALL_IFRAMES, true)

            reverseVideo(allKeyFrames, inputVidUri!!, outPath!!, keyFrame, reverseVideoSettings)
//            forward(allKeyFrames,inputVidUri!!,outPath!!,keyFrame)
        }
    }


    private fun reverseVideo(
        allKeyFrames: Boolean,
        inputVidUri1: Uri?,
        outPath: String?,
        keyFrame: KeyFrameSettings,
        reverseVideoSettings: ReverseVideoSettings
    ) {
        var inputVidUri = inputVidUri1
        if (allKeyFrames) {
            val tmpVidPath = cacheDir.absolutePath + "/out.vid"
            if (inputVidUri != null) {
                contentResolver.openFileDescriptor(inputVidUri, "r").use {
                    if (it != null) {
                        if (outPath != null) {
                            KeyFrameConverter(keyFrame).convert(tmpVidPath, it.fileDescriptor)
                        }
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
                        ReverseVideo(reverseVideoSettings).convert(
                            outPath,
                            it.fileDescriptor,
                            "ASa"
                        )
                    }
                }
            }
        }
    }

    private fun forward(
        allKeyFrames: Boolean,
        inputVidUri: Uri,
        outPath: String,
        keyFrame: KeyFrameSettings
    ): Uri? {
        // Convert all frames to keyframes?
        if (allKeyFrames) {
            val tmpVidPath = cacheDir.absolutePath + "/out.vid"
            if (inputVidUri != null) {
                contentResolver.openFileDescriptor(inputVidUri, "r").use {
                    if (it != null) {
                        if (outPath != null) {
                            KeyFrameConverter(keyFrame).convert(outPath, it.fileDescriptor)
                        }
                    }
                }
            }
            return Uri.fromFile(File(tmpVidPath))
        }
        return null
    }

    companion object {
        const val TAG = "VidProcService"
        const val KEY_OUT_PATH = "OUT_PATH"
        const val KEY_INPUT_VID_URI = "INPUT_VID_URI"
        const val KEY_RESULT_INTENT = "RESULT_INTENT"
        const val KEY_ALL_IFRAMES = "eALL_IFRAMES"
    }
}