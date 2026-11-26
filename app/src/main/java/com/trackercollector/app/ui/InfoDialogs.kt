package com.trackercollector.app.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trackercollector.app.R
import com.trackercollector.app.databinding.DialogInfoBinding
import com.trackercollector.app.databinding.ItemInfoRowBinding

/** Credit, Tutorial, Version and About screens shown from the ⋮ menu. */
object InfoDialogs {

    const val REPO_URL = "https://github.com/faria2177/Tracker-Collector"
    const val RELEASES_URL = "$REPO_URL/releases/latest"

    private class Row(val badge: String, val title: String, val text: String)

    private fun show(
        a: Activity,
        icon: Int, tileBg: Int, tint: Int,
        title: String, subtitle: String,
        rows: List<Row>,
        linkLabel: String? = null, url: String? = null
    ) {
        val d = DialogInfoBinding.inflate(a.layoutInflater)
        d.icon.setImageResource(icon)
        d.icon.setBackgroundResource(tileBg)
        d.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(a, tint))
        d.title.text = title
        d.subtitle.text = subtitle
        for (r in rows) {
            val rb = ItemInfoRowBinding.inflate(a.layoutInflater, d.rows, false)
            rb.badge.text = r.badge
            rb.rowTitle.text = r.title
            rb.rowText.text = r.text
            d.rows.addView(rb.root)
        }
        val builder = MaterialAlertDialogBuilder(a)
            .setView(d.root)
            .setPositiveButton("OK", null)
        if (linkLabel != null && url != null) builder.setNeutralButton(linkLabel) { _, _ -> open(a, url) }
        builder.show()
    }

    private fun open(a: Activity, url: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private fun versionInfo(a: Activity): Pair<String, Long> {
        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        return (info.versionName ?: "1.0") to PackageInfoCompat.getLongVersionCode(info)
    }

    fun tutorial(a: Activity) = show(
        a, R.drawable.ic_school, R.drawable.bg_tile_amber, R.color.tile_amber_icon,
        "How to use", "Collect a customer list in 4 steps",
        listOf(
            Row("1", "Scan the sheet", "Tap the round Scan button. Hold the page flat in good light; the scanner finds the edges and crops automatically. You can add several pages."),
            Row("2", "Or pick photos", "Tap Gallery to read sheets you already photographed, or share an image to Tracker Collector from any app."),
            Row("3", "Check and save", "SL, name, designation and mobile number are read for every row. Untick wrong rows and tap Save. Numbers already saved are skipped as duplicates."),
            Row("4", "Fix if needed", "Tap any entry to edit or delete it. Use + to add an entry by hand. Search finds name, designation or mobile."),
            Row("5", "Preview and export", "Preview shows the exact Excel and Word layout. Tap Excel or Word to save the file to Download/TrackerCollector and open or share it.")
        )
    )

    fun credit(a: Activity) = show(
        a, R.drawable.ic_star, R.drawable.bg_tile_amber, R.color.tile_amber_icon,
        "Credit", "Made with care",
        listOf(
            Row("★", "Developer", "Md Jahidul Islam"),
            Row("💡", "Idea", "Md Asaduzzaman"),
            Row("◎", "Text recognition", "Google ML Kit Text Recognition — works offline on the phone"),
            Row("▣", "Document scanner", "Google ML Kit Document Scanner"),
            Row("◆", "Design", "Material Design 3 components for Android")
        ),
        "GitHub", REPO_URL
    )

    fun version(a: Activity) {
        val (name, code) = versionInfo(a)
        show(
            a, R.drawable.ic_update, R.drawable.bg_tile_blue, R.color.tile_blue_icon,
            "Version $name", "Build $code",
            listOf(
                Row("✓", "Installed", "Tracker Collector $name (build $code)"),
                Row("↻", "Updates", "New versions are published on GitHub Releases. Install the new APK over this one — your saved entries stay.")
            ),
            "Check update", RELEASES_URL
        )
    }

    fun about(a: Activity) {
        val (name, _) = versionInfo(a)
        show(
            a, R.drawable.ic_info, R.drawable.bg_tile_green, R.color.tile_green_icon,
            "Tracker Collector", "Version $name",
            listOf(
                Row("1", "What it does", "Scans application sheets and collects SL, name, designation and mobile number from the table."),
                Row("2", "No duplicates", "Each mobile number is saved only once, even if the same sheet is scanned again."),
                Row("3", "Excel & Word", "Exports the list as .xlsx and .docx with SL, Name of Customer, Customer Designation and Mobile Number."),
                Row("4", "Private", "Everything is processed and stored on your phone. Nothing is uploaded.")
            ),
            "Website", REPO_URL
        )
    }
}
