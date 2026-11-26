package com.trackercollector.app.export

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.trackercollector.app.core.ExportRow
import com.trackercollector.app.data.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared "export → saved → open / share" flow used by the main and preview screens. */
object ExportUi {

    fun rowsOf(records: List<Record>) =
        records.mapIndexed { i, r -> ExportRow((i + 1).toString(), r.name, r.designation, r.mobile) }

    fun run(
        activity: AppCompatActivity,
        records: List<Record>,
        type: ExportType,
        anchor: View,
        onBusy: (Boolean) -> Unit
    ) {
        if (records.isEmpty()) {
            Snackbar.make(anchor, "Nothing to export yet — scan a document first.", Snackbar.LENGTH_LONG).show()
            return
        }
        onBusy(true)
        activity.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { ExportHelper.export(activity, rowsOf(records), type) }
            } catch (e: Exception) {
                onBusy(false)
                MaterialAlertDialogBuilder(activity).setTitle("Export failed")
                    .setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show()
                return@launch
            }
            onBusy(false)
            showDone(activity, result, records.size, anchor)
        }
    }

    private fun showDone(activity: AppCompatActivity, r: ExportResult, count: Int, anchor: View) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (r.type == ExportType.EXCEL) "Excel file ready" else "Word file ready")
            .setMessage("$count entries exported.\n\nSaved to:\n${r.savedLocation}")
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = r.type.mime
                    putExtra(Intent.EXTRA_STREAM, r.shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, "Share ${r.fileName}"))
            }
            .setNeutralButton("Open") { _, _ ->
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(r.shareUri, r.type.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(view)
                } catch (e: Exception) {
                    Snackbar.make(anchor, "No app found to open .${r.type.ext} files", Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
