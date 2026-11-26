package com.trackercollector.app.core

/** Axis-aligned box in image pixel coordinates. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f

    fun union(o: Box) = Box(
        minOf(left, o.left), minOf(top, o.top),
        maxOf(right, o.right), maxOf(bottom, o.bottom)
    )
}

/** One OCR word (ML Kit "element"). */
data class OcrWord(val text: String, val box: Box)

/** One OCR line (ML Kit "line") made of words. */
data class OcrLine(val words: List<OcrWord>) {
    val text: String get() = words.joinToString(" ") { it.text }
    val box: Box get() = words.map { it.box }.reduce { a, b -> a.union(b) }
}

/** One extracted table row. */
data class ParsedRow(
    val serial: String,
    val name: String,
    val designation: String,
    val mobile: String
)
