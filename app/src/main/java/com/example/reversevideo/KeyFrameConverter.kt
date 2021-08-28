package com.example.reversevideo

import android.annotation.SuppressLint
import android.media.*
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.reversevideo.MainActivity.Companion.TAG
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.security.InvalidParameterException


class KeyFrameSettings(){
     val outMime = "video/avc"
     val mediaCodedTimeoutUs = 10000L
     var videoIndex = -1
     var audioIndex = -1
     var width = -1
     var height = -1
}

class KeyFrameConverter(private val keyFrameSettings: KeyFrameSettings) {
    // Format for the greyscale video output file
    private val maxChunkSize : Int = 1024*1024
    // Main classes from Android's API responsible
    // for processing of the video
    private var videoExtractor: MediaExtractor = MediaExtractor()
    private var muxer: MediaMuxer? = null
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null

    private var audioFormat = MediaFormat()
    private var audioExtractor: MediaExtractor = MediaExtractor()
    private var surface: Surface? = null


    // These control the state of video processing
    private var allInputExtracted = false
    private var allInputDecoded = false
    private var allOutputEncoded = false

    interface StartConvert{
        fun startConvert()
    }
    var startConvert : StartConvert? = null

    fun convert(videoPath: String, inputVidFd: FileDescriptor) {
        try {
            initConverter(videoPath, inputVidFd)
            convert()
            muxAudio()
        } finally {
            releaseConverter()
        }
        startConvert?.startConvert()
    }

    private fun initConverter(videoPath: String, inputVidFd: FileDescriptor) {
        // Init extractor
        //video Extract
        videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(inputVidFd)
        val inFormat = selectVideoTrack(videoExtractor)
        //audioExtract
        audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(inputVidFd)
        audioFormat = selectAudioTrack(audioExtractor)

        // Create H.264 encoder
        encoder = MediaCodec.createEncoderByType(keyFrameSettings.outMime)

        // Prepare output format for the encoder
        val outFormat = getOutputFormat(inFormat)
        keyFrameSettings.width = outFormat.getInteger(MediaFormat.KEY_WIDTH)
        keyFrameSettings.height = outFormat.getInteger(MediaFormat.KEY_HEIGHT)

        // Configure the encoder
        encoder?.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder?.createInputSurface()

        // Init decoder
        decoder =
            inFormat.getString(MediaFormat.KEY_MIME)?.let { MediaCodec.createDecoderByType(it) }
        decoder?.configure(inFormat, surface, null, 0)

        // Init muxer
        muxer = MediaMuxer(videoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.e(TAG, "initConverter: $keyFrameSettings.audioIndex videoIndex = $keyFrameSettings.videoIndex")

        encoder?.start()
        decoder?.start()
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
    private fun muxAudio() {
        val audioBufferInfo = MediaCodec.BufferInfo()
        val audioBuffer = ByteBuffer.allocate(maxChunkSize)

        while (true) {
            val chunkSize = audioExtractor.readSampleData(audioBuffer, 0)

            if (chunkSize > 0) {
                audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags
                audioBufferInfo.size = chunkSize

                muxer?.writeSampleData(keyFrameSettings.audioIndex, audioBuffer, audioBufferInfo)
                audioExtractor.advance()
                Log.e(TAG, "muxAudio: ${ audioExtractor.advance()}", )
            } else {
                break
            }
        }
    }

    private fun getOutputFormat(inputFormat: MediaFormat): MediaFormat {
        // Preferably the output vid should have same resolution as input vid
        val inputSize = Size(
            inputFormat.getInteger(MediaFormat.KEY_WIDTH),
            inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        )
        val outputSize = getSupportedVideoSize(encoder!!, keyFrameSettings.outMime, inputSize)

        return MediaFormat.createVideoFormat(keyFrameSettings.outMime, outputSize.width, outputSize.height).apply {
            //A key describing the color format of the content in a video format. Constants are declared in
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            //A key describing the average bitrate in bits/sec. The associated value is an integer
            setInteger(MediaFormat.KEY_BIT_RATE, 200000000)
            //A key describing the frame rate of a video format in frames/sec.
            setInteger(
                MediaFormat.KEY_FRAME_RATE,
                inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
            )

            // All frames should be encoded as key frames
            //A key describing the frequency of key frames expressed in seconds between key frames.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            //A key describing the mime type of the MediaFormat. The associated value is a string.
            setString(MediaFormat.KEY_MIME, keyFrameSettings.outMime)
        }
    }

    private fun convert() {

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
                val outBufferId = encoder?.dequeueOutputBuffer(bufferInfo, keyFrameSettings.mediaCodedTimeoutUs)
                if (outBufferId != null) {
                    if (outBufferId >= 0) {

                        val encodedBuffer = encoder?.getOutputBuffer(outBufferId)

                        if (encodedBuffer != null) {
                            muxer?.writeSampleData(keyFrameSettings.videoIndex, encodedBuffer, bufferInfo)
                        }

                        encoder?.releaseOutputBuffer(outBufferId, false)

                        // Are we finished here?
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            allOutputEncoded = true
                            break
                        }
                    } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        encoderOutputAvailable = false
                    } else if (outBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        keyFrameSettings.videoIndex = muxer!!.addTrack(encoder!!.outputFormat)
                        keyFrameSettings.audioIndex = muxer!!.addTrack(audioFormat)
                        muxer?.start()
                    }
                }

                if (outBufferId != MediaCodec.INFO_TRY_AGAIN_LATER)
                    continue

                // Get output from decoder and feed it to encoder
                if (!allInputDecoded) {
                    val outBufferId = decoder?.dequeueOutputBuffer(bufferInfo, keyFrameSettings.mediaCodedTimeoutUs)
                    if (outBufferId != null) {
                        if (outBufferId >= 0) {
                            val render = bufferInfo.size > 0

                            // Get the content of the decoded buffer
                            decoder?.releaseOutputBuffer(outBufferId, render)

                            // Did we get all output from decoder?
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                allInputDecoded = true
                                encoder?.signalEndOfInputStream()
                            }
                        } else if (outBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            decoderOutputAvailable = false
                        }
                    }
                }
            }
        }
    }

    private fun feedInputToDecoder() {
        val inBufferId = decoder?.dequeueInputBuffer(keyFrameSettings.mediaCodedTimeoutUs)
        if (inBufferId != null) {
            if (inBufferId >= 0) {
                val buffer = decoder?.getInputBuffer(inBufferId)
                val sampleSize = buffer?.let { videoExtractor.readSampleData(it, 0) }

                if (sampleSize != null) {
                    if (sampleSize >= 0) {
                        decoder?.queueInputBuffer(
                            inBufferId, 0, sampleSize,
                            videoExtractor.sampleTime, videoExtractor.sampleFlags
                        )

                        videoExtractor.advance()
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
    }

    private fun releaseConverter() {
        videoExtractor.release()

        decoder?.stop()
        decoder?.release()
        decoder = null

        encoder?.stop()
        encoder?.release()
        encoder = null

        muxer?.stop()
        muxer?.release()
        muxer = null

        surface?.release()
        surface = null

        keyFrameSettings.width = -1
        keyFrameSettings.height = -1
        keyFrameSettings.videoIndex = -1
    }
}