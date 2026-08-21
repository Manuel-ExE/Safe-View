package com.safeview.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * Lightweight on-device NSFW classifier using TensorFlow Lite.
 *
 * Expected model: MobileNet-style, input 224x224 RGB float32,
 * output: [Drawing, Hentai, Neutral, Porn, Sexy] probabilities
 * (same order as NSFWJS / nsfw_model).
 *
 * Place the model file at:  app/src/main/assets/nsfw_mobilenet_v2.tflite
 * If the file is missing, [isReady] stays false and only heuristics run.
 */
class NsfwClassifier(private val context: Context) {

    data class Result(
        val blocked: Boolean,
        val explicitScore: Float,
        val revealingScore: Float,
        val labels: Map<String, Float> = emptyMap()
    )

    @Volatile
    private var interpreter: Interpreter? = null
    @Volatile
    private var imageProcessor: ImageProcessor? = null

    val isReady: Boolean get() = interpreter != null

    fun load() {
        if (interpreter != null) return
        try {
            val model = loadModelFile(MODEL_NAME) ?: run {
                Log.i(TAG, "Model $MODEL_NAME not found in assets — AI disabled")
                return
            }
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                // GPU delegate can be added later if desired
            }
            interpreter = Interpreter(model, options)
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f)) // 0-1 range
                .build()
            Log.i(TAG, "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            interpreter = null
        }
    }

    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
        imageProcessor = null
    }

    /**
     * Classify a bitmap. Returns null if the model is not ready.
     */
    fun classify(
        bitmap: Bitmap,
        explicitThreshold: Float = 0.40f,
        revealingThreshold: Float = 0.12f
    ): Result? {
        val interp = interpreter ?: return null
        val processor = imageProcessor ?: return null

        return try {
            var tensorImage = TensorImage.fromBitmap(bitmap)
            tensorImage = processor.process(tensorImage)

            val output = Array(1) { FloatArray(NUM_CLASSES) }
            interp.run(tensorImage.buffer, output)

            val scores = output[0]
            val labels = LABELS.mapIndexed { i, name -> name to scores[i] }.toMap()
            val porn = scores.getOrElse(3) { 0f }
            val hentai = scores.getOrElse(1) { 0f }
            val sexy = scores.getOrElse(4) { 0f }
            val explicit = max(porn, hentai)
            val revealing = sexy

            Result(
                blocked = explicit >= explicitThreshold || revealing >= revealingThreshold,
                explicitScore = explicit,
                revealingScore = revealing,
                labels = labels
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            null
        }
    }

    private fun loadModelFile(name: String): MappedByteBuffer? {
        return try {
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        } catch (e: Exception) {
            // File not present — expected until user adds a model
            null
        }
    }

    companion object {
        private const val TAG = "SafeView.TFLite"
        private const val MODEL_NAME = "nsfw_mobilenet_v2.tflite"
        private const val INPUT_SIZE = 224
        private const val NUM_CLASSES = 5
        private val LABELS = listOf("Drawing", "Hentai", "Neutral", "Porn", "Sexy")
    }
}
