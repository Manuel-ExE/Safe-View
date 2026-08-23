package com.safeview.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * Local TFLite content classifier. The model is never uploaded or persisted
 * outside the application process.
 *
 * Supported contracts:
 *  - SafeView five-label float model: Drawing, Hentai, Neutral, Porn, Sexy
 *  - AutoML two-label UINT8 model: nonnude, nude
 */
class NsfwClassifier(private val context: Context) {
    data class Result(
        val blocked: Boolean,
        val explicitScore: Float,
        val revealingScore: Float,
        val labels: Map<String, Float> = emptyMap(),
        val category: ContentCategory = ContentCategory.UNCERTAIN,
        val confidence: Float = 0f
    )

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var inputType: DataType? = null
    @Volatile private var outputType: DataType? = null
    @Volatile private var inputWidth = INPUT_SIZE
    @Volatile private var inputHeight = INPUT_SIZE
    @Volatile private var outputCount = 0
    @Volatile private var inputScale = 1f
    @Volatile private var inputZeroPoint = 0
    @Volatile private var outputScale = 1f
    @Volatile private var outputZeroPoint = 0

    val isReady: Boolean get() = interpreter != null

    @Synchronized
    fun load() {
        if (interpreter != null) return
        try {
            val model = loadModelFile(MODEL_NAME) ?: run {
                Log.i(TAG, "Model $MODEL_NAME not found in assets — AI disabled")
                return
            }
            val options = Interpreter.Options().apply { setNumThreads(2) }
            val loaded = Interpreter(model, options)
            val input = loaded.getInputTensor(0)
            val output = loaded.getOutputTensor(0)
            val shape = input.shape()
            require(shape.size == 4 && shape[3] == 3) { "Unsupported input shape: ${shape.contentToString()}" }
            require(output.shape().size == 2 && output.shape()[0] == 1) {
                "Unsupported output shape: ${output.shape().contentToString()}"
            }
            interpreter = loaded
            inputType = input.dataType()
            outputType = output.dataType()
            inputHeight = shape[1]
            inputWidth = shape[2]
            outputCount = output.shape()[1]
            inputScale = input.quantizationParams().scale
            inputZeroPoint = input.quantizationParams().zeroPoint
            outputScale = output.quantizationParams().scale
            outputZeroPoint = output.quantizationParams().zeroPoint
            Log.i(TAG, "TFLite model loaded: ${shape.contentToString()} -> ${output.shape().contentToString()} ${output.dataType()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            interpreter?.close()
            interpreter = null
        }
    }

    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
        inputType = null
        outputType = null
        outputCount = 0
        inputScale = 1f
        inputZeroPoint = 0
        outputScale = 1f
        outputZeroPoint = 0
    }

    fun classify(bitmap: Bitmap, explicitThreshold: Float = 0.40f, revealingThreshold: Float = 0.12f): Result? {
        val interp = interpreter ?: return null
        val type = inputType ?: return null
        val resultType = outputType ?: return null
        return try {
            val input = makeInput(bitmap, type)
            val output = makeOutput(resultType, outputCount)
            interp.run(input, output)
            val scores = readScores(output, resultType, outputCount)
            if (scores.isEmpty()) return null

            if (scores.size == 2) {
                // The bundled AutoML model’s dict.txt order is: nonnude, nude.
                val nonnude = scores[0]
                val nude = scores[1]
                val labels = mapOf("nonnude" to nonnude, "nude" to nude)
                Result(
                    blocked = nude >= explicitThreshold,
                    explicitScore = nude,
                    revealingScore = 0f,
                    labels = labels,
                    category = if (nude >= explicitThreshold) ContentCategory.EXPLICIT else ContentCategory.SAFE,
                    confidence = max(nonnude, nude)
                )
            } else {
                val labels = LABELS.mapIndexed { i, name -> name to scores.getOrElse(i) { 0f } }.toMap()
                val porn = scores.getOrElse(3) { 0f }
                val hentai = scores.getOrElse(1) { 0f }
                val sexy = scores.getOrElse(4) { 0f }
                val explicit = max(porn, hentai)
                Result(
                    blocked = explicit >= explicitThreshold || sexy >= revealingThreshold,
                    explicitScore = explicit,
                    revealingScore = sexy,
                    labels = labels,
                    category = when {
                        explicit >= explicitThreshold -> ContentCategory.EXPLICIT
                        sexy >= revealingThreshold -> ContentCategory.REVEALING
                        else -> ContentCategory.SAFE
                    },
                    confidence = max(explicit, sexy)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            null
        }
    }

    private fun makeInput(bitmap: Bitmap, type: DataType): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val bytesPerValue = if (type == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * bytesPerValue)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            if (type == DataType.FLOAT32) {
                buffer.putFloat(r / 255f)
                buffer.putFloat(g / 255f)
                buffer.putFloat(b / 255f)
            } else if (type == DataType.UINT8) {
                buffer.put(quantize(r / 255f, inputScale, inputZeroPoint))
                    .put(quantize(g / 255f, inputScale, inputZeroPoint))
                    .put(quantize(b / 255f, inputScale, inputZeroPoint))
            } else {
                throw IllegalArgumentException("Unsupported input type: $type")
            }
        }
        buffer.rewind()
        if (scaled !== bitmap) scaled.recycle()
        return buffer
    }

    private fun makeOutput(type: DataType, count: Int): ByteBuffer {
        val bytesPerValue = when (type) {
            DataType.FLOAT32, DataType.INT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> throw IllegalArgumentException("Unsupported output type: $type")
        }
        return ByteBuffer.allocateDirect(count * bytesPerValue).order(ByteOrder.nativeOrder())
    }

    private fun readScores(buffer: ByteBuffer, type: DataType, count: Int): FloatArray {
        buffer.rewind()
        return FloatArray(count) {
            when (type) {
                DataType.FLOAT32 -> buffer.float
                DataType.UINT8 -> ((buffer.get().toInt() and 0xff) - outputZeroPoint) * outputScale
                DataType.INT8 -> (buffer.get().toInt() - outputZeroPoint) * outputScale
                DataType.INT32 -> buffer.int.toFloat()
                else -> throw IllegalArgumentException("Unsupported output type: $type")
            }
        }
    }

    private fun quantize(value: Float, scale: Float, zeroPoint: Int): Byte =
        ((value / scale) + zeroPoint).toInt().coerceIn(0, 255).toByte()

    private fun loadModelFile(name: String): MappedByteBuffer? = try {
        context.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "SafeView.TFLite"
        private const val MODEL_NAME = "nsfw_mobilenet_v2.tflite"
        private const val INPUT_SIZE = 224
        private val LABELS = listOf("Drawing", "Hentai", "Neutral", "Porn", "Sexy")
    }
}
