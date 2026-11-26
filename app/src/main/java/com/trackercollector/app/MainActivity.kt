package com.trackercollector.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.trackercollector.app.core.ExportRow
import com.trackercollector.app.core.ParsedRow
import com.trackercollector.app.data.Record
import com.trackercollector.app.data.RecordDb
import com.trackercollector.app.databinding.ActivityMainBinding
import com.trackercollector.app.databinding.DialogRecordBinding
import com.trackercollector.app.export.ExportHelper
import com.trackercollector.app.export.ExportResult
import com.trackercollector.app.export.ExportType
import com.trackercollector.app.ocr.OcrProcessor
import com.trackercollector.app.ui.RecordAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var db: RecordDb
    private lateinit var ocr: OcrProcessor
    private val adapter = RecordAdapter { showEditDialog(it) }
    private var records: List<Record> = emptyList()

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res: ActivityResult ->
        if (res.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uris = result?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) processImages(uris)
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) processImages(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = RecordDb(this)
        ocr = OcrProcessor(this)

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_add -> { showEditDialog(null); true }
                R.id.action_clear -> { confirmClear(); true }
                else -> false
            }
        }
        b.btnScan.setOnClickListener { startScanner() }
        b.btnGallery.setOnClickListener { galleryLauncher.launch("image/*") }
        b.btnExcel.setOnClickListener { export(ExportType.EXCEL) }
        b.btnWord.setOnClickListener { export(ExportType.WORD) }
        b.edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })

        reload()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        ocr.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ scanning

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                // Scanner needs Google Play services; fall back to picking photos.
                Toast.makeText(this, "Scanner not available (${e.message}). Pick a photo instead.", Toast.LENGTH_LONG).show()
                galleryLauncher.launch("image/*")
            }
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            intent.action = null
            processImages(uris)
        }
    }

    private fun processImages(uris: List<Uri>) {
        showProgress("Reading ${uris.size} page(s)…")
        lifecycleScope.launch {
            val found = ArrayList<ParsedRow>()
            var failed = 0
            uris.forEachIndexed { i, uri ->
                b.txtProgress.text = "Reading page ${i + 1} of ${uris.size}…"
                try {
                    found += ocr.extract(uri)
                } catch (e: Exception) {
                    failed++
                }
            }
            // Split into new rows and duplicates (already saved, or repeated in this scan).
            val seen = HashSet<String>()
            val fresh = ArrayList<ParsedRow>()
            var duplicates = 0
            withContext(Dispatchers.IO) {
                for (r in found) {
                    val m = RecordDb.normalizeMobile(r.mobile) ?: continue
                    if (!seen.add(m) || db.mobileExists(m)) duplicates++
                    else fresh += r.copy(mobile = m)
                }
            }
            hideProgress()
            when {
                fresh.isEmpty() && duplicates > 0 -> info("No new entries", "All $duplicates entries in this scan are already saved (duplicates skipped).")
                fresh.isEmpty() -> info(
                    "Nothing found",
                    "No mobile numbers could be read" + (if (failed > 0) " ($failed page(s) failed)" else "") +
                        ".\n\nTips: keep the page flat, fill the frame with the table, and use good light."
                )
                else -> showReviewDialog(fresh, duplicates)
            }
        }
    }

    private fun showReviewDialog(rows: List<ParsedRow>, duplicates: Int) {
        val labels = rows.map { r ->
            buildString {
                if (r.serial.isNotBlank()) append(r.serial).append(". ")
                append(r.name.ifBlank { "(no name)" })
                append("\n").append(r.designation.ifBlank { "(no designation)" })
                append(" • ").append(r.mobile)
            }
        }.toTypedArray()
        val checked = BooleanArray(rows.size) { true }
        val title = "${rows.size} new found" + if (duplicates > 0) " • $duplicates duplicate skipped" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                lifecycleScope.launch {
                    var saved = 0
                    withContext(Dispatchers.IO) {
                        rows.forEachIndexed { i, r ->
                            if (checked[i] && db.insert(Record(serial = r.serial, name = r.name, designation = r.designation, mobile = r.mobile))) saved++
                        }
                    }
                    reload()
                    snack("$saved saved. Tap any entry to correct it.")
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ list

    private fun reload() {
        lifecycleScope.launch {
            records = withContext(Dispatchers.IO) { db.all() }
            render()
        }
    }

    private fun render() {
        val q = b.edtSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val numbered = records.mapIndexed { i, r -> (i + 1) to r }
        val shown = if (q.isEmpty()) numbered else numbered.filter { (_, r) ->
            r.name.lowercase().contains(q) || r.designation.lowercase().contains(q) || r.mobile.contains(q)
        }
        adapter.submit(shown)
        b.txtCount.text = "${records.size} record(s) collected" + if (q.isNotEmpty()) " • ${shown.size} shown" else ""
        b.txtEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(existing: Record?) {
        val d = DialogRecordBinding.inflate(LayoutInflater.from(this))
        existing?.let {
            d.edtName.setText(it.name)
            d.edtDesignation.setText(it.designation)
            d.edtMobile.setText(it.mobile)
            d.edtSerial.setText(it.serial)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) getString(R.string.add_manually) else "Edit entry")
            .setView(d.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
        if (existing != null) builder.setNeutralButton(R.string.delete) { _, _ ->
            db.delete(existing.id); reload(); snack("Deleted")
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val mobile = RecordDb.normalizeMobile(d.edtMobile.text.toString())
                if (mobile == null) {
                    d.edtMobile.error = "Enter a valid 11-digit mobile number"
                    return@setOnClickListener
                }
                val rec = Record(
                    id = existing?.id ?: 0,
                    serial = d.edtSerial.text.toString().trim(),
                    name = d.edtName.text.toString().trim(),
                    designation = d.edtDesignation.text.toString().trim(),
                    mobile = mobile,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                val ok = if (existing == null) db.insert(rec) else db.update(rec)
                if (!ok) {
                    d.edtMobile.error = "This mobile number is already saved"
                    return@setOnClickListener
                }
                dialog.dismiss()
                reload()
            }
        }
        dialog.show()
    }

    private fun confirmClear() {
        if (records.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear all?")
            .setMessage("Delete all ${records.size} collected entries? Export first if you need them.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Delete all") { _, _ -> db.clear(); reload() }
            .show()
    }

    // ------------------------------------------------------------------ export

    private fun export(type: ExportType) {
        if (records.isEmpty()) {
            snack("Nothing to export yet — scan a document first.")
            return
        }
        val rows = records.mapIndexed { i, r -> ExportRow((i + 1).toString(), r.name, r.designation, r.mobile) }
        showProgress("Creating ${if (type == ExportType.EXCEL) "Excel" else "Word"} file…")
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { ExportHelper.export(this@MainActivity, rows, type) }
            } catch (e: Exception) {
                hideProgress(); info("Export failed", e.message ?: e.toString()); return@launch
            }
            hideProgress()
            showExportDone(result)
        }
    }

    private fun showExportDone(r: ExportResult) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (r.type == ExportType.EXCEL) "Excel file ready" else "Word file ready")
            .setMessage("${records.size} entries exported.\n\nSaved to:\n${r.savedLocation}")
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = r.type.mime
                    putExtra(Intent.EXTRA_STREAM, r.shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share ${r.fileName}"))
            }
            .setNeutralButton("Open") { _, _ ->
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(r.shareUri, r.type.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(view)
                } catch (e: Exception) {
                    snack("No app found to open .${r.type.ext} files")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun showProgress(msg: String) {
        b.txtProgress.text = msg
        b.progressBox.visibility = View.VISIBLE
        setButtonsEnabled(false)
    }

    private fun hideProgress() {
        b.progressBox.visibility = View.GONE
        setButtonsEnabled(true)
    }

    private fun setButtonsEnabled(e: Boolean) {
        b.btnScan.isEnabled = e; b.btnGallery.isEnabled = e
        b.btnExcel.isEnabled = e; b.btnWord.isEnabled = e
    }

    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()

    private fun info(title: String, msg: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
}
