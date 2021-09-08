package com.example.reversevideo

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.*
import android.media.*
import android.opengl.*
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.reversevideo.MainActivity.Companion.TAG
import com.example.reversevideo.textRender.TextAnimator
import com.example.reversevideo.textRender.TextureRenderer
import java.io.FileDescriptor
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.security.InvalidParameterException
import java.util.*


class ReverseVideoSettings() {
    val outMime = "video/avc"
    val audioMime = "audio/mpeg"
    var videoIndex = -1
    var audioIndex = -1
    val mediaCodedTimeoutUs = 10000L
    var endPresentationTimeUs = -1L
    var width = -1
    var height = -1


}

class AddTextToVideoSetting() {
    var videoRenderer: TextureRenderer? = null
    var textRenderer: TextureRenderer? = null

    // Helps to calculate the transformations for moving text around
    var textAnimator = TextAnimator()

    // Makes the decoded video frames available to OpenGL
    var surfaceTexture: SurfaceTexture? = null

    // EGL stuff for initializing OpenGL context
    var eglDisplay: EGLDisplay? = null
    var eglContext: EGLContext? = null
    var eglSurface: EGLSurface? = null

    // OpenGL transformation applied to UVs of the texture that holds
    // the decoded frame
    val texMatrix = FloatArray(16)


    // These control the state of video processing

    var thread: HandlerThread? = null


}


class ReverseVideo(private val reverseSettings: ReverseVideoSettings) {
    var text: String? = null

    @Volatile
    private var frameAvailable = false
    private val lock = Object()
    private var outputSurface: Surface? = null

    private val videoSampleTimes = Stack<Long>()
    private var audioSampleTimes = Stack<Long>()

    private var videoExtractor: MediaExtractor = MediaExtractor()
    private var muxer: MediaMuxer? = null
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var audioDecoder : MediaCodec? = null
    private var audioEncoder : MediaCodec? = null
    private var audioFormat = MediaFormat()
    private var audioExtractor: MediaExtractor = MediaExtractor()

    // These control the state of video processing
    private var allInputExtracted = false
    private var allInputDecoded = false
    private var allOutputEncoded = false
    private val maxChunkSize : Int = 1024*1024

    // Handle to raw video data used by MediaCodec encoder & decoder
    private var surface: Surface? = null


    private val addTextToVideoSetting = AddTextToVideoSetting()

    interface ReverseVideoCallback {
        //        fun convert()
        fun initExtractors()
        fun muxAudio()
        fun convertFile()
        fun finishExtractors()
        fun onError(errorMessage: String)
    }

    var callback: ReverseVideoCallback? = null


    fun convert(outPath: String, inputVidFd: FileDescriptor, text: String) {
        this.text = text
        try {
            init(outPath, inputVidFd)
            convert()
            muxAudio(maxChunkSize)
        } catch (e: IllegalStateException) {
            callback?.onError("$e")
        } finally {
            releaseConverter()
        }
//        startConvert?.startConvert()
    }

    private fun init(videoPath: String, inputVidFd: FileDescriptor) {
        // Init extractor

        videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(inputVidFd)
        val inFormat = selectVideoTrack(videoExtractor)
        //audioExtract
        audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(inputVidFd)
//        val inAudioFormat = selectAudioTrack(audioExtractor)
        audioFormat = selectAudioTrack(audioExtractor)

        // Create H.264 encoder
        encoder = MediaCodec.createEncoderByType(reverseSettings.outMime)
     //   audioEncoder = MediaCodec.createEncoderByType(reverseSettings.audioMime)
        // Prepare output format for the encoder
        val outFormat = getOutputFormat(inFormat/*, audioFormat*/)
        reverseSettings.width = outFormat.getInteger(MediaFormat.KEY_WIDTH)
        reverseSettings.height = outFormat.getInteger(MediaFormat.KEY_HEIGHT)

        // Configure the encoder
        encoder!!.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder!!.createInputSurface()

        // Init input surface + make sure it's made current
        initEgl()

        // Init output surface
        addTextToVideoSetting.videoRenderer = TextureRenderer()
        addTextToVideoSetting.textRenderer = TextureRenderer(false)
        addTextToVideoSetting.surfaceTexture =
            addTextToVideoSetting.videoRenderer?.texId?.let { SurfaceTexture(it) }

        // Control the thread from which OnFrameAvailableListener will
        // be called
        addTextToVideoSetting.thread = HandlerThread("FrameHandlerThread")
        addTextToVideoSetting.thread?.start()

        addTextToVideoSetting. surfaceTexture!!.setOnFrameAvailableListener({
            synchronized(lock) {

                // New frame available before the last frame was process...we dropped some frames
                if (frameAvailable)
                    Log.d(
                        MainActivity.TAG,
                        "Frame available before the last frame was process...we dropped some frames"
                    )

                frameAvailable = true
                lock.notifyAll()
            }
        }, addTextToVideoSetting.thread?.looper?.let { Handler(it) })

        outputSurface = Surface(addTextToVideoSetting.surfaceTexture)

        // Init decoder
        decoder =
            inFormat.getString(MediaFormat.KEY_MIME)?.let { MediaCodec.createDecoderByType(it) }
        decoder?.configure(inFormat, outputSurface, null, 0)

//        audioDecoder = inAudioFormat.getString(MediaFormat.KEY_MIME)?.let { MediaCodec.createDecoderByType(it) }
//        audioDecoder?.configure(inAudioFormat, outputSurface, null, 0)

        // Init muxer
        muxer = MediaMuxer(videoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        encoder?.start()
//        audioEncoder?.start()
        decoder?.start()
//        audioDecoder?.start()
//
        callback?.initExtractors()
    }

    private fun initEgl() {
        addTextToVideoSetting.eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (addTextToVideoSetting.eglDisplay == EGL14.EGL_NO_DISPLAY)
            throw RuntimeException(
                "eglDisplay == EGL14.EGL_NO_DISPLAY: "
                        + GLUtils.getEGLErrorString(EGL14.eglGetError())
            )

        val version = IntArray(2)
        if (!EGL14.eglInitialize(addTextToVideoSetting.eglDisplay, version, 0, version, 1))
            throw RuntimeException("eglInitialize(): " + GLUtils.getEGLErrorString(EGL14.eglGetError()))

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val nConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                addTextToVideoSetting.eglDisplay,
                attribList,
                0,
                configs,
                0,
                configs.size,
                nConfigs,
                0
            )
        )
            throw RuntimeException(GLUtils.getEGLErrorString(EGL14.eglGetError()))

        var err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        addTextToVideoSetting.eglContext =
            EGL14.eglCreateContext(addTextToVideoSetting.eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_NONE
        )
        addTextToVideoSetting.eglSurface =
            EGL14.eglCreateWindowSurface(addTextToVideoSetting.eglDisplay, configs[0], surface, surfaceAttribs, 0)
        err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS)
            throw RuntimeException(GLUtils.getEGLErrorString(err))

        if (!EGL14.eglMakeCurrent(addTextToVideoSetting.eglDisplay, addTextToVideoSetting.eglSurface, addTextToVideoSetting.eglSurface, addTextToVideoSetting.eglContext))
            throw RuntimeException("eglMakeCurrent(): " + GLUtils.getEGLErrorString(EGL14.eglGetError()))
    }

    private fun selectVideoTrack(extractor: MediaExtractor): MediaFormat {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) {
                extractor.selectTrack(i)
                return format
            }
        }

        throw InvalidParameterException("File contains no video track")
    }

    private fun selectAudioTrack(extractor: MediaExtractor): MediaFormat {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                extractor.selectTrack(i)
                return format
            }
        }

        throw InvalidParameterException("File contains no audio track")
    }

    @SuppressLint("WrongConstant")
    private fun muxAudio(maxChunkSize: Int) {
        val audioBufferInfo = MediaCodec.BufferInfo()
        val audioBuffer = ByteBuffer.allocate(maxChunkSize)

        while (true) {
            if (audioExtractor.sampleFlags == MediaExtractor.SAMPLE_FLAG_SYNC) {
                audioSampleTimes.push(audioExtractor.sampleTime)
                Log.d(TAG, "muxAudio: ${audioSampleTimes.push(audioExtractor.sampleTime)}", )
            }

            if (!audioExtractor.advance())
                break
        }

        while (audioSampleTimes.isNotEmpty()) {

            val next = audioSampleTimes.pop()
            audioExtractor.seekTo(next, MediaExtractor.SEEK_TO_NEXT_SYNC)
            val chunkSize = audioExtractor.readSampleData(audioBuffer, 0)

            if (chunkSize > 0) {
                audioBufferInfo.flags = audioExtractor.sampleFlags
                audioBufferInfo.size = chunkSize

                muxer?.writeSampleData(
                    reverseSettings.audioIndex,
                    audioBuffer,
                    audioBufferInfo
                )
            } else {
                break
            }
        }
        callback?.muxAudio()
    }

    private fun getOutputFormat(inputFormat: MediaFormat): MediaFormat {
        // Preferably the output vid should have same resolution as input vid
        val inputSize = Size(
            inputFormat.getInteger(MediaFormat.KEY_WIDTH),
            inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        )
        val outputSize =
            getSupportedVideoSize(encoder!!, reverseSettings.outMime, inputSize)

        return MediaFormat.createVideoFormat(
            reverseSettings.outMime,
            outputSize.width,
            outputSize.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 20000000)
            setInteger(
                MediaFormat.KEY_FRAME_RATE,
                inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            )
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 15)
            setString(MediaFormat.KEY_MIME, reverseSettings.outMime)
        }
    }

    private fun convert() {
        while (true) {
            if (videoExtractor.sampleFlags == MediaExtractor.SAMPLE_FLAG_SYNC) {
                videoSampleTimes.push(videoExtractor.sampleTime)
            }

            if (!videoExtractor.advance())
                break
        }
       // addTextToVideoSetting.textAnimator.setCamera(reverseSettings.width, reverseSettings.height)
        reverseSettings.endPresentationTimeUs = videoSampleTimes.lastElement()


        allInputExtracted = false
        allInputDecoded = false
        allOutputEncoded = false
        val bufferInfo = MediaCodec.BufferInfo()

        // Extract, decode, edit, encode, and mux
        while (!allOutputEncoded) {
            // Feed input to decoder
            if (!allInputExtracted)
                feedInputToDecoder()

            var encoderOutputAvailable = true
            var decoderOutputAvailable = !allInputDecoded

            while (encoderOutputAvailable || decoderOutputAvailable) {
                // Drain Encoder & mux to output file first
                val outBufferId =
                    encoder!!.dequeueOutputBuffer(bufferInfo, reverseSettings.mediaCodedTimeoutUs)
                if (outBufferId >= 0) {

                    val encodedBuffer = encoder!!.getOutputBuffer(outBufferId)

                    if (encodedBuffer != null) {
                        muxer!!.writeSampleData(
                            reverseSettings.videoIndex,
                            encodedBuffer,
                            bufferInfo
                        )
                    }

                    encoder!!.releaseOutputBuffer(outBufferId, false)

                    // Are we finished here?
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        allOutputEncoded = true
                        break
                    }
                } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    encoderOutputAvailable = false
                } else if (outBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reverseSettings.videoIndex = muxer!!.addTrack(encoder!!.outputFormat)
                    reverseSettings.audioIndex = muxer!!.addTrack(audioFormat)
                    muxer!!.start()
                }

                if (outBufferId != MediaCodec.INFO_TRY_AGAIN_LATER)
                    continue

                // Get output from decoder and feed it to encoder
                if (!allInputDecoded) {
                    val outBufferId = decoder!!.dequeueOutputBuffer(
                        bufferInfo,
                        reverseSettings.mediaCodedTimeoutUs
                    )
                    if (outBufferId >= 0) {
                        val render = bufferInfo.size > 0
                        // Give the decoded frame to SurfaceTexture (onFrameAvailable() callback should
                        // be called soon after this)
                        decoder!!.releaseOutputBuffer(outBufferId, render)
                        if (render) {
                            // Wait till new frame available after onFrameAvailable has been called
                            waitTillFrameAvailable()

                            addTextToVideoSetting.surfaceTexture?.updateTexImage()
                            addTextToVideoSetting.surfaceTexture?.getTransformMatrix(addTextToVideoSetting.texMatrix)

                            // Draw texture with opengl
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                            GLES20.glClearColor(0f, 0f, 0f, 0f)
                            GLES20.glViewport(0, 0, reverseSettings.width, reverseSettings.height)

                            addTextToVideoSetting.videoRenderer?.draw(getMVP(), addTextToVideoSetting.texMatrix, null)

                            addTextToVideoSetting.textAnimator.update()
                            addTextToVideoSetting.textRenderer!!.draw(
                                addTextToVideoSetting.textAnimator.getMVP(), null,
                                textToBitmap(text!!, reverseSettings.width, reverseSettings.height)
                            )

                            EGLExt.eglPresentationTimeANDROID(
                                addTextToVideoSetting.eglDisplay, addTextToVideoSetting.eglSurface,
                                bufferInfo.presentationTimeUs * 1000
                            )

                            EGL14.eglSwapBuffers(addTextToVideoSetting.eglDisplay, addTextToVideoSetting.eglSurface)
                        }

                        // Did we get all output from decoder?
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            allInputDecoded = true
                            encoder!!.signalEndOfInputStream()
                        }
                    } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        decoderOutputAvailable = false
                    }

                }
            }
        }
        callback?.convertFile()
    }


    //take last frame
    private fun feedInputToDecoder() {
        val inBufferId = decoder?.dequeueInputBuffer(reverseSettings.mediaCodedTimeoutUs)
        if (inBufferId != null) {
            if (inBufferId >= 0) {
                if (videoSampleTimes.isNotEmpty() && videoSampleTimes.peek() > 0) { // If we're not yet at the beginning
                    val buffer = decoder?.getInputBuffer(inBufferId)
                    val sampleSize = buffer?.let { videoExtractor.readSampleData(it, 0) }
                    if (sampleSize != null) {
                        if (sampleSize > 0) {
                            decoder?.queueInputBuffer(
                                inBufferId,
                                0,
                                sampleSize,
                                /*if present time equals 0 = lost frames */
                                reverseSettings.endPresentationTimeUs - videoExtractor.sampleTime,
                                audioExtractor.sampleFlags
                            )
                        }
                    }
                    val next = videoSampleTimes.pop()
                    Log.e(TAG, "POP $next" )
                    videoExtractor.seekTo(next, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                } else {
                    decoder?.queueInputBuffer(
                        inBufferId, 0, 0,
                        0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    allInputExtracted = true
                }
            }
        }
    }

    private fun releaseEgl() {
        if (addTextToVideoSetting.eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(addTextToVideoSetting.eglDisplay, addTextToVideoSetting.eglSurface)
            EGL14.eglDestroyContext(addTextToVideoSetting.eglDisplay, addTextToVideoSetting.eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(addTextToVideoSetting.eglDisplay)
        }

        addTextToVideoSetting.eglDisplay = EGL14.EGL_NO_DISPLAY
        addTextToVideoSetting.eglContext = EGL14.EGL_NO_CONTEXT
        addTextToVideoSetting.eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun getMVP(): FloatArray {
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)

        return mvp
    }

    private fun waitTillFrameAvailable() {
        synchronized(lock) {
            while (!frameAvailable) {
                lock.wait(200)
                if (!frameAvailable)
                    Log.e(MainActivity.TAG, "Surface frame wait timed out")
            }
            frameAvailable = false
        }
    }


    private fun releaseConverter() {
        videoExtractor.release()
        audioExtractor.release()

        decoder?.stop()
        decoder?.release()
        decoder = null

        encoder?.stop()
        encoder?.release()
        encoder = null
        releaseEgl()

        outputSurface?.release()
        outputSurface = null

        muxer?.stop()
        muxer?.release()
        muxer = null

        addTextToVideoSetting.thread?.quitSafely()
        addTextToVideoSetting.thread = null
        muxer?.stop()
        muxer?.release()
        muxer = null

        surface?.release()
        surface = null

        reverseSettings.width = -1
        reverseSettings.height = -1
        reverseSettings.videoIndex = -1
        callback?.finishExtractors()
    }

    companion object {
        fun textToBitmap(text: String, width: Int, height: Int): Bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Pick an initial size to calculate the requested size later
            paint.textSize = 62f

            // Configure your text properties
            paint.color = Color.parseColor("#FF009FE3")
            paint.textAlign = Paint.Align.LEFT // This affects the origin of x in Canvas.drawText()
            // setTypeface(), setUnderlineText(), ....

            // After setting parameters that could affect the size and position,
            // now try to fit text within requested bitmap width & height
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            paint.textSize = paint.textSize * width.toFloat() / bounds.width()

            // Or fit to height
//            paint.textSize = ceil(paint.textSize * height.toDouble() / bounds.height()).toFloat()

            // You can also affect the aspect ratio of text and try to fit both, width and height,
            // with paint.setTextScaleX()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Measure once again to get current top, left position, so that
            // we can position the final text from fop left corner
            paint.getTextBounds(text, 0, text.length, bounds)

            canvas.drawText(text, -bounds.left.toFloat(), -bounds.top.toFloat(), paint)
            return bitmap
        }
    }
}