package com.turbotranslate.app

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One turn of the conversation. [fromEnglish] = true means the English
 *  speaker held the right softkey (or a photographed English source was
 *  translated out of English); false means the reverse. [originalText]
 *  is what Whisper transcribed (or what the vision model read off a
 *  photo) in the speaker's own language; [translatedText] is what the
 *  *other* person should read — the prominent line in the bubble, since
 *  that's the part that actually matters to whoever's looking at the
 *  phone. [isPhoto] just adds a camera icon to the label so photo
 *  entries are visually distinct from spoken ones in the transcript. */
data class TranslatorEntry(
    val fromEnglish: Boolean,
    val originalText: String,
    val translatedText: String,
    val otherLanguageName: String,
    val isPhoto: Boolean = false
)

/** Rolling window size for both the in-memory transcript and what gets
 *  persisted to disk — oldest entries drop off past this. */
const val MAX_TRANSCRIPT_ENTRIES = 20

class TranslatorAdapter(private var items: List<TranslatorEntry> = emptyList()) :
    RecyclerView.Adapter<TranslatorAdapter.VH>() {

    private var selectedPosition: Int? = null

    class VH(view: LinearLayout) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val original: TextView = view.findViewById(R.id.originalText)
        val translated: TextView = view.findViewById(R.id.translatedText)
        val langLabel: TextView = view.findViewById(R.id.langLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_translator_entry, parent, false) as LinearLayout
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val context = holder.itemView.context
        val entry = items[position]
        val density = context.resources.displayMetrics.density

        holder.original.text = entry.originalText
        holder.translated.text = entry.translatedText
        val icon = if (entry.isPhoto) "📷 " else ""
        holder.langLabel.text = if (entry.fromEnglish) "$icon" + "English → ${entry.otherLanguageName}"
            else "$icon${entry.otherLanguageName} → English"

        val fillColor = if (entry.fromEnglish) AppColors.BUBBLE_OUTGOING else AppColors.BUBBLE_INCOMING
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(fillColor)
            if (position == selectedPosition) {
                setStroke((2.5f * density).toInt(), AppColors.ACCENT)
            }
        }
        holder.bubble.background = bg
        holder.original.setTextColor(AppColors.TEXT_ORIGINAL)
        holder.translated.setTextColor(AppColors.BUBBLE_TEXT)
        holder.langLabel.setTextColor(AppColors.ACCENT_LIGHT)

        // English speaker's turn sits on the right; the other speaker's
        // turn sits on the left — matches the outgoing/incoming message
        // convention from FlipText's texting UI.
        holder.root.gravity = if (entry.fromEnglish) Gravity.END else Gravity.START
    }

    override fun getItemCount() = items.size

    fun addEntry(entry: TranslatorEntry) {
        items = items + entry
        if (items.size > MAX_TRANSCRIPT_ENTRIES) {
            items = items.takeLast(MAX_TRANSCRIPT_ENTRIES)
            notifyDataSetChanged()
        } else {
            notifyItemInserted(items.size - 1)
        }
    }

    fun currentItems(): List<TranslatorEntry> = items

    fun getEntry(position: Int): TranslatorEntry? = items.getOrNull(position)

    /** Highlights the bubble at [position] (with an accent border) so
     *  D-pad navigation shows which message center/OK would copy — or
     *  clears the highlight if null. */
    fun setSelectedPosition(position: Int?) {
        val old = selectedPosition
        selectedPosition = position
        if (old != null) notifyItemChanged(old)
        if (position != null) notifyItemChanged(position)
    }
}
