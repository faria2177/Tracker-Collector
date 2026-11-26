package com.trackercollector.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trackercollector.app.data.Record
import com.trackercollector.app.databinding.ItemRecordBinding

class RecordAdapter(private val onClick: (Record) -> Unit) : RecyclerView.Adapter<RecordAdapter.VH>() {

    /** Pair of (export serial, record). */
    private var items: List<Pair<Int, Record>> = emptyList()

    fun submit(list: List<Pair<Int, Record>>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val (sl, r) = items[position]
        h.b.txtSl.text = sl.toString()
        h.b.txtName.text = r.name.ifBlank { "(no name)" }
        h.b.txtDesignation.text = r.designation.ifBlank { "(no designation)" }
        h.b.txtMobile.text = r.mobile
        h.b.txtDocSl.visibility = if (r.serial.isBlank()) View.GONE else View.VISIBLE
        h.b.txtDocSl.text = "Doc SL ${r.serial}"
        h.b.root.setOnClickListener { onClick(r) }
    }
}
