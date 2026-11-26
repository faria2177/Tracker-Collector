package com.trackercollector.app.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.trackercollector.app.core.Box
import com.trackercollector.app.core.OcrLine
import com.trackercollector.app.core.OcrWord
import com.trackercollector.app.core.ParsedRow
import com.trackercollector.app.core.TableParser
import kotlinx.coroutines.tasks.await

/** Runs ML Kit OCR on an image and extracts table rows from it. */
class OcrProcessor(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extract(uri: Uri): List<ParsedRow> {
        val image = InputImage.fromFilePath(context, uri)
        val text = recognizer.process(image).await()
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val words = line.elements.mapNotNull { el ->
                val r = el.boundingBox ?: return@mapNotNull null
                OcrWord(el.text, Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()))
            }
            if (words.isNotEmpty()) lines += OcrLine(words)
        }
        return TableParser.parse(lines)
    }

    fun close() = recognizer.close()
}
