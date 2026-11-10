package com.orhan.iptv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.orhan.iptv.R
import com.orhan.iptv.data.HistoryEntry

/**
 * Ana sayfadaki "İzlemeye Devam Et" rafı.
 *
 * Her kart bir dizi veya film. Dizide kart, *şimdi oynatılacak* bölümü
 * gösterir: yarıda kalan bölüm ("▶ Devam · S2 · B14 · 23 dk kaldı") ya da
 * önceki bittiyse sıradaki ("Sıradaki · S2 · B15").
 */
class ContinueAdapter(
    private val onClick: (HistoryEntry) -> Unit,
    /** Uzun basış: kaydı raftan kaldır. */
    private val onLongClick: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<ContinueAdapter.VH>() {

    private var items: List<HistoryEntry> = emptyList()

    init {
        // Kararlı kimlik: oynatıcıdan dönüp liste tazelendiğinde kumanda odağı
        // aynı kartta kalsın.
        setHasStableIds(true)
    }

    fun submit(list: List<HistoryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_continue, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val frame: View = view.findViewById(R.id.card_frame)
        private val image: ImageView = view.findViewById(R.id.poster)
        private val badge: TextView = view.findViewById(R.id.badge)
        private val progress: ProgressBar = view.findViewById(R.id.progress)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)

        fun bind(entry: HistoryEntry) {
            title.text = entry.parent.title

            val remaining = if (entry.durationMs > 0) {
                ((entry.durationMs - entry.positionMs) / 60_000L).coerceAtLeast(1)
            } else -1L
            val remainingText = if (remaining > 0) "$remaining dk kaldı" else "Kaldığı yerden"

            when {
                entry.finished -> {
                    // Sıradaki bölüm tıklanınca sağlayıcıdan sorulacak.
                    badge.text = "Sıradaki"
                    subtitle.text = "${entry.playable.episodeLabel} bitti · sıradaki bölüm"
                }
                entry.isUpNext -> {
                    badge.text = "Sıradaki"
                    subtitle.text = entry.playable.episodeLabel
                }
                entry.isSeries -> {
                    badge.text = "▶ Devam"
                    subtitle.text = "${entry.playable.episodeLabel} · $remainingText"
                }
                else -> {
                    badge.text = "▶ Devam"
                    subtitle.text = remainingText
                }
            }

            if (!entry.isUpNext && entry.positionMs > 0) {
                progress.visibility = View.VISIBLE
                progress.progress = if (entry.durationMs > 0) {
                    (entry.positionMs * 100 / entry.durationMs).toInt().coerceIn(2, 100)
                } else 35
            } else {
                progress.visibility = View.GONE
            }

            // Bölümün kendi karesi varsa onu, yoksa dizinin afişini göster.
            val url = entry.episode?.logoUrl?.takeIf { it.isNotBlank() }
                ?: entry.parent.logoUrl?.takeIf { it.isNotBlank() }
            val placeholder = if (entry.isSeries) R.drawable.ic_series else R.drawable.ic_movie
            if (url == null) {
                Glide.with(image).clear(image)
                image.scaleType = ImageView.ScaleType.CENTER
                image.setImageResource(placeholder)
            } else {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(image).load(url).placeholder(placeholder).error(placeholder).into(image)
            }

            frame.setOnClickListener { onClick(entry) }
            frame.setOnLongClickListener { onLongClick(entry); true }
            frame.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.1f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
                title.animate().alpha(if (hasFocus) 1f else 0.8f).setDuration(140).start()
            }
            title.alpha = if (frame.hasFocus()) 1f else 0.8f
        }
    }
}
