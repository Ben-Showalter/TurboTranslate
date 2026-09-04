package com.turbotranslate.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Plain scrolling list, D-pad Up/Down moves [selectedPosition]. */
class LanguagePickerAdapter(private var items: List<String>) :
    RecyclerView.Adapter<LanguagePickerAdapter.VH>() {

    private var selectedPosition: Int = 0

    class VH(view: TextView) : RecyclerView.ViewHolder(view) {
        val text: TextView = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_language, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = items[position]
        holder.text.text = name
        if (position == selectedPosition) {
            holder.text.setBackgroundColor(AppColors.SURFACE_2)
            holder.text.setTextColor(AppColors.ACCENT)
        } else {
            holder.text.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.text.setTextColor(AppColors.TEXT_PRIMARY)
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<String>) {
        items = newItems
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun currentItems(): List<String> = items

    fun setSelectedPosition(position: Int) {
        if (items.isEmpty()) return
        selectedPosition = position.coerceIn(0, items.size - 1)
        notifyDataSetChanged()
    }

    fun getSelectedPosition(): Int = selectedPosition
}
