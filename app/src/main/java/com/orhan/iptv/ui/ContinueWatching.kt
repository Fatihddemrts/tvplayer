package com.orhan.iptv.ui

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orhan.iptv.data.HistoryEntry
import com.orhan.iptv.data.MediaItem
import com.orhan.iptv.data.Repository
import com.orhan.iptv.data.SourceType
import com.orhan.iptv.data.xtream.XtreamClient
import com.orhan.iptv.player.PlaybackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bir "izlemeye devam et" kaydını tek tıkla oynatır.
 *
 * Film: kaldığı saniyeden açılır.
 * Dizi: kayıttaki bölüm kaldığı saniyeden açılır; bölüm bitmişse sıradaki
 * bölüm başlar. Oynatıcıya dizinin *tüm* bölümleri sıra olarak verilir, böylece
 * bölüm bitince sezon atlansa bile bir sonrakine kendiliğinden geçilir.
 */
object ContinueWatching {

    /** Bölüm listesi indirilirken ikinci tıklamayı yok saymak için. */
    private var busy = false

    /**
     * [knownEpisodes] doluysa (detay ekranında zaten yüklü) ağa gidilmez;
     * boşsa bölümler sağlayıcıdan çekilir ve bu sırada [loading] gösterilir.
     */
    fun play(
        activity: AppCompatActivity,
        entry: HistoryEntry,
        loading: View? = null,
        knownEpisodes: List<MediaItem> = emptyList()
    ) {
        if (!entry.isSeries) {
            PlaybackActivity.start(activity, entry.parent)
            return
        }
        if (knownEpisodes.isNotEmpty()) {
            startFromList(activity, entry, knownEpisodes)
            return
        }

        val seriesId = entry.parent.seriesId
        val source = Repository.sources().firstOrNull { it.type == SourceType.XTREAM }
        if (seriesId == null || source == null) {
            startFromList(activity, entry, emptyList())
            return
        }

        if (busy) return
        busy = true
        loading?.visibility = View.VISIBLE
        activity.lifecycleScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    runCatching { XtreamClient(source).allEpisodes(seriesId) }.getOrDefault(emptyList())
                }
                startFromList(activity, entry, episodes)
            } finally {
                // Ekran kapanıp iş iptal edilse de kilit açılsın.
                loading?.visibility = View.GONE
                busy = false
            }
        }
    }

    private fun startFromList(activity: AppCompatActivity, entry: HistoryEntry, episodes: List<MediaItem>) {
        val episode = entry.episode ?: return
        val index = episodes.indexOfFirst { it.id == episode.id }

        when {
            // Liste alınamadı (ağ hatası, M3U dizisi): en azından bölümün kendisini aç.
            index < 0 && !entry.finished ->
                PlaybackActivity.start(activity, listOf(episode), 0, entry.parent)

            index < 0 ->
                toast(activity, "Sıradaki bölüm alınamadı, bağlantıyı kontrol edin")

            entry.finished && index + 1 < episodes.size ->
                PlaybackActivity.start(activity, episodes, index + 1, entry.parent)

            entry.finished -> {
                toast(activity, "${entry.parent.title}: yayınlanan tüm bölümleri izlediniz")
                Repository.removeHistory(entry.key)
            }

            else -> PlaybackActivity.start(activity, episodes, index, entry.parent)
        }
    }

    private fun toast(activity: AppCompatActivity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
