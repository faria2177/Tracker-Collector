package com.trackercollector.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import com.trackercollector.app.core.ParsedRow
import com.trackercollector.app.data.Record
import com.trackercollector.app.data.RecordDb
import com.trackercollector.app.databinding.ActivityMainBinding
import com.trackercollector.app.databinding.DialogRecordBinding
import com.trackercollector.app.databinding.ViewNavItemBinding
import com.trackercollector.app.ui.InfoDialogs
import com.trackercollector.app.export.ExportType
import com.trackercollector.app.export.ExportUi
import com.trackercollector.app.ui.setupLightBars
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
        setupLightBars(b.root, b.bottomPanel)
        db = RecordDb(this)
        ocr = OcrProcessor(this)

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.btnAdd.setOnClickListener { showEditDialog(null) }
        b.btnMore.setOnClickListener { v -> showMoreMenu(v) }
        b.btnScan.setOnClickListener { startScanner() }

        setupNav(b.navGallery, "Gallery", R.drawable.ic_gallery, R.drawable.bg_tile_green, R.color.tile_green_icon) {
            galleryLauncher.launch("image/*")
        }
        setupNav(b.navPreview, "Preview", R.drawable.ic_preview, R.drawable.bg_tile_amber, R.color.tile_amber_icon) {
            openPreview(ExportType.EXCEL)
        }
        setupNav(b.navExcel, "Excel", R.drawable.ic_table, R.drawable.bg_tile_green, R.color.excel) {
            ExportUi.run(this, records, ExportType.EXCEL, b.root, ::setBusy)
        }
        setupNav(b.navWord, "Word", R.drawable.ic_doc, R.drawable.bg_tile_blue, R.color.word) {
            ExportUi.run(this, records, ExportType.WORD, b.root, ::setBusy)
        }
        b.edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })

        reload()
        handleShareIntent(intent)
        if (savedInstanceState == null && App.firstTutorial(this)) InfoDialogs.tutorial(this)
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            menu.findItem(R.id.action_dark).isChecked = App.isDark(this@MainActivity)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_add -> showEditDialog(null)
                    R.id.action_clear -> confirmClear()
                    R.id.action_dark -> App.setDark(this@MainActivity, !App.isDark(this@MainActivity))
                    R.id.action_tutorial -> InfoDialogs.tutorial(this@MainActivity)
                    R.id.action_credit -> InfoDialogs.credit(this@MainActivity)
                    R.id.action_version -> InfoDialogs.version(this@MainActivity)
                    R.id.action_about -> InfoDialogs.about(this@MainActivity)
                }
                true
            }
        }.show()
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
        b.txtCount.text = records.size.toString()
        b.txtCountLabel.text = (if (records.size == 1) "record collected" else "records collected") +
            if (q.isNotEmpty()) " · ${shown.size} match" else ""
        b.txtSection.text = if (q.isNotEmpty()) "Search results" else "Collected entries"
        b.emptyBox.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
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

    // ------------------------------------------------------------------ preview / export

    private fun openPreview(type: ExportType) {
        if (records.isEmpty()) {
            snack("Nothing to preview yet — scan a document first.")
            return
        }
        startActivity(Intent(this, PreviewActivity::class.java).putExtra(PreviewActivity.EXTRA_TYPE, type.name))
    }

    private fun setupNav(t: ViewNavItemBinding, label: String, icon: Int, bg: Int, tint: Int, onClick: () -> Unit) {
        t.navLabel.text = label
        t.navIcon.setImageResource(icon)
        t.navIcon.setBackgroundResource(bg)
        t.navIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        t.root.setOnClickListener { onClick() }
    }

    private fun setBusy(busy: Boolean) {
        if (busy) showProgress("Creating file…") else hideProgress()
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
        b.btnScan.isEnabled = e; b.navGallery.root.isEnabled = e
        b.navExcel.root.isEnabled = e; b.navWord.root.isEnabled = e; b.navPreview.root.isEnabled = e
    }

    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()

    private fun info(title: String, msg: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }
}
