package com.orhan.iptv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.orhan.iptv.R
import com.orhan.iptv.data.MediaItem
import com.orhan.iptv.data.MediaKind
import com.orhan.iptv.data.Repository

/** Dikey listeler (NAS gezgini, dizi bölümleri) için satır adaptörü. */
class RowAdapter(
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    private var items: List<MediaItem> = emptyList()

    fun submit(list: List<MediaItem>) {
        items = list
        notifyDataSetChanged()
    }

    /** İzlendi / kaldığı yer işaretlerini yeniden çizer (oynatıcıdan dönünce). */
    fun refresh() = notifyDataSetChanged()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val frame: View = view.findViewById(R.id.card_frame)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)

        fun bind(item: MediaItem) {
            title.text = item.title

            // Bölüm listesinde hangi bölümün izlendiği / nerede kalındığı görünsün.
            val saved = Repository.position(item.id)
            val status = when {
                Repository.isWatched(item.id) -> "✓ İzlendi"
                saved > 0 -> "▶ Kaldığı yer ${formatTime(saved)}"
                else -> null
            }
            val text = listOfNotNull(status, item.group?.takeIf { it.isNotBlank() })
                .joinToString("  ·  ")
            subtitle.text = text
            subtitle.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE

            icon.setImageResource(
                when (item.kind) {
                    MediaKind.FOLDER -> R.drawable.ic_folder
                    MediaKind.LIVE -> R.drawable.ic_live
                    else -> R.drawable.ic_file_video
                }
            )

            frame.setOnClickListener { onClick(item) }
        }

        private fun formatTime(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
