package com.trackercollector.app

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.trackercollector.app.core.OfficeExporter
import com.trackercollector.app.data.Record
import com.trackercollector.app.data.RecordDb
import com.trackercollector.app.databinding.ActivityPreviewBinding
import com.trackercollector.app.export.ExportType
import com.trackercollector.app.export.ExportUi
import com.trackercollector.app.ui.setupLightBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows exactly what the Excel / Word file will look like before exporting. */
class PreviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityPreviewBinding
    private var records: List<Record> = emptyList()
    private var type = ExportType.EXCEL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupLightBars(b.root, b.bottomPanel)

        if (intent.getStringExtra(EXTRA_TYPE) == ExportType.WORD.name) {
            type = ExportType.WORD
            b.toggle.check(R.id.tabWord)
        }

        b.btnBack.setOnClickListener { finish() }
        b.toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            type = if (id == R.id.tabWord) ExportType.WORD else ExportType.EXCEL
            render()
        }
        b.btnExport.setOnClickListener {
            ExportUi.run(this, records, type, b.root) { busy -> b.btnExport.isEnabled = !busy }
        }

        lifecycleScope.launch {
            records = withContext(Dispatchers.IO) { RecordDb(this@PreviewActivity).all() }
            render()
        }
    }

    private fun render() {
        val excel = type == ExportType.EXCEL
        b.txtSub.text = "${records.size} entries · ${if (excel) "TrackerCollector.xlsx" else "TrackerCollector.docx"}"
        b.txtHint.text = if (excel) "Excel sheet preview" else "Word document preview"
        b.btnExport.text = if (excel) "Export Excel" else "Export Word"
        b.btnExport.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (excel) R.color.excel else R.color.word)
        )
        b.tableHost.removeAllViews()
        b.tableHost.addView(if (excel) buildExcel() else buildWord())
        b.vScroll.scrollTo(0, 0); b.hScroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ Excel look

    private fun buildExcel(): View {
        val widths = intArrayOf(40, 56, 220, 190, 140)
        val table = gridContainer()
        // Column letters
        table.addView(row(
            listOf("", "A", "B", "C", "D"), widths,
            bgs = List(5) { R.drawable.bg_cell_rowhead }, bold = false, center = true,
            color = R.color.text_secondary, size = 12f
        ))
        // Header row (row 1)
        table.addView(row(
            listOf("1") + OfficeExporter.HEADERS, widths,
            bgs = listOf(R.drawable.bg_cell_rowhead) + List(4) { R.drawable.bg_cell_header },
            bold = true, center = true, firstGray = true
        ))
        ExportUi.rowsOf(records).forEachIndexed { i, r ->
            table.addView(row(
                listOf((i + 2).toString(), r.serial, r.name, r.designation, r.mobile), widths,
                bgs = listOf(R.drawable.bg_cell_rowhead) + List(4) { R.drawable.bg_cell },
                bold = false, centerCols = setOf(0, 1, 4), firstGray = true
            ))
        }
        return FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(table)
        }
    }

    // ------------------------------------------------------------------ Word look

    private fun buildWord(): View {
        val widths = intArrayOf(50, 200, 170, 130)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            elevation = dp(3).toFloat()
            setPadding(dp(28), dp(28), dp(28), dp(40))
            minimumHeight = dp(560)
        }
        page.addView(TextView(this).apply {
            text = "Customer List"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(14) }
        })
        val table = gridContainer()
        table.addView(row(OfficeExporter.HEADERS, widths, bgs = List(4) { R.drawable.bg_cell_header }, bold = true, center = true))
        ExportUi.rowsOf(records).forEach { r ->
            table.addView(row(
                listOf(r.serial, r.name, r.designation, r.mobile), widths,
                bgs = List(4) { R.drawable.bg_cell }, bold = false, centerCols = setOf(0, 3)
            ))
        }
        page.addView(table)
        return FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(page)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Grid colour around the cells provides the outer left/top border. */
    private fun gridContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.sheet_grid))
        setPadding(1, 1, 0, 0)
    }

    private fun row(
        values: List<String>,
        widthsDp: IntArray,
        bgs: List<Int>,
        bold: Boolean,
        center: Boolean = false,
        centerCols: Set<Int> = emptySet(),
        firstGray: Boolean = false,
        color: Int = R.color.text_primary,
        size: Float = 13f
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEachIndexed { i, v ->
            row.addView(TextView(this).apply {
                text = v
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                val c = if (firstGray && i == 0) R.color.text_secondary else color
                setTextColor(ContextCompat.getColor(context, c))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL or
                    if (center || i in centerCols || (firstGray && i == 0)) Gravity.CENTER_HORIZONTAL else Gravity.START
                setBackgroundResource(bgs[i])
                setPadding(dp(8), dp(7), dp(8), dp(7))
                minHeight = dp(34)
                layoutParams = LinearLayout.LayoutParams(dp(widthsDp[i]), ViewGroup.LayoutParams.MATCH_PARENT)
            })
        }
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TYPE = "type"
    }
}
