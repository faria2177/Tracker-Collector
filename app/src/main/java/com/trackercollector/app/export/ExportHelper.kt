package com.trackercollector.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.trackercollector.app.core.ExportRow
import com.trackercollector.app.core.OfficeExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportType(val ext: String, val mime: String) {
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
}

class ExportResult(val shareUri: Uri, val savedLocation: String, val fileName: String, val type: ExportType)

object ExportHelper {

    /** Writes the file, copies it to Downloads/TrackerCollector and returns a shareable URI. */
    fun export(context: Context, rows: List<ExportRow>, type: ExportType): ExportResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TrackerCollector_$stamp.${type.ext}"

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val file = File(dir, fileName)
        file.outputStream().use { out ->
            when (type) {
                ExportType.EXCEL -> OfficeExporter.writeXlsx(rows, out)
                ExportType.WORD -> OfficeExporter.writeDocx(rows, out)
            }
        }

        val saved = try {
            saveToDownloads(context, file, type)
        } catch (e: Exception) {
            null
        }
        val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return ExportResult(shareUri, saved ?: "App storage", fileName, type)
    }

    private fun saveToDownloads(context: Context, file: File, type: ExportType): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, type.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TrackerCollector")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create file in Downloads")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Download/TrackerCollector/${file.name}"
        } else {
            // Android 8–9: app-specific folder (no storage permission needed).
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "TrackerCollector").apply { mkdirs() }
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }
    }
}
