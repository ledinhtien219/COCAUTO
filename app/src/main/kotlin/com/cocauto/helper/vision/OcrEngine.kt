package com.cocauto.helper.vision

import android.graphics.Bitmap
import com.cocauto.helper.model.NormRect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrResult(
    val fullText: String,
    val lines: List<OcrTextLine>,
)

/**
 * Offline on-device OCR wrapper powered by Google ML Kit Text Recognition.
 * Crops normalized ROIs, enhances contrast for Clash of Clans fonts, and maps
 * detected line bounding boxes back to full-screen normalized coordinates.
 */
class OcrEngine {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeRoi(
        frame: Bitmap,
        roi: NormRect,
        enhance: Boolean = true,
    ): OcrResult {
        val normRoi = roi.normalized()
        val crop = ImageAnalyzer.cropNormalized(frame, normRoi) ?: return OcrResult("", emptyList())
        val processed = if (enhance) ImageAnalyzer.enhanceForOcr(crop) else crop

        return try {
            val inputImage = InputImage.fromBitmap(processed, 0)
            val visionText = recognizer.process(inputImage).await()
            val pW = processed.width.toFloat().coerceAtLeast(1f)
            val pH = processed.height.toFloat().coerceAtLeast(1f)

            val mappedLines = buildList {
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val fullLeft = normRoi.left + (box.left / pW) * normRoi.width
                        val fullTop = normRoi.top + (box.top / pH) * normRoi.height
                        val fullRight = normRoi.left + (box.right / pW) * normRoi.width
                        val fullBottom = normRoi.top + (box.bottom / pH) * normRoi.height
                        add(
                            OcrTextLine(
                                text = line.text,
                                bounds = NormRect(fullLeft, fullTop, fullRight, fullBottom).normalized(),
                            )
                        )
                    }
                }
            }
            OcrResult(fullText = visionText.text, lines = mappedLines)
        } catch (_: Exception) {
            OcrResult("", emptyList())
        } finally {
            if (processed !== crop && !processed.isRecycled) {
                processed.recycle()
            }
            if (!crop.isRecycled) {
                crop.recycle()
            }
        }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
