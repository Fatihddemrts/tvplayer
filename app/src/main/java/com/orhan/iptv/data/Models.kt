package com.orhan.iptv.data

import java.io.Serializable

/** Bir kaynağın türü. */
enum class SourceType { M3U, XTREAM, SMB }

/**
 * Kullanıcının eklediği içerik kaynağı.
 * M3U      -> [url] dolu
 * XTREAM   -> [url] (http://host:port), [username], [password] dolu
 * SMB      -> [url] host, [share], [username], [password] dolu
 */
data class Source(
    val id: String,
    val type: SourceType,
    val name: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val share: String? = null
) : Serializable

/** Medya türü. */
enum class MediaKind { LIVE, VOD, SERIES, FILE, FOLDER, PHOTO, AUDIO }

/**
 * Oynatılabilir (veya içine girilebilir) tek bir öğe.
 * [playUrl] FOLDER için klasör yolu, diğerleri için doğrudan stream URL'i.
 */
data class MediaItem(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val playUrl: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val description: String? = null,
    /** Xtream serilerinde sezon/bölüm gezinmesi için. */
    val seriesId: String? = null,
    /** SMB için kimlik bilgileri; oynatıcı URL'e gömmek zorunda. */
    val smbUser: String? = null,
    val smbPass: String? = null,
    /**
     * Fotoğrafları jcifs ile okumak için gereken konum bilgisi.
     * [smbRoot] "smb://host/share/", [smbPath] paylaşım köküne göreli yol.
     * (Oynatma URL'inden türetilemiyor çünkü orada kimlik bilgileri gömülü ve
     * yüzde-kodlanmış durumda.)
     */
    val smbRoot: String? = null,
    val smbPath: String? = null,
    /** Bu öğe için tercih edilen motor; null ise otomatik seçilir. */
    val preferredEngine: Engine? = null,
    /**
     * Bu akış için zorunlu User-Agent. Birçok IPTV sağlayıcısı playlist'te
     * `user-agent="okhttp/4.10.0"` gibi bir değer belirtiyor ve başka bir
     * UA ile istek geldiğinde 403 dönüyor. Boşsa uygulama varsayılanı kullanılır.
     */
    val userAgent: String? = null,
    /** Sağlayıcının istediği Referer başlığı (bazı portallar zorunlu tutuyor). */
    val referer: String? = null,
    /** Dizi bölümlerinde sezon ve bölüm numarası ("S2 · B14" etiketi için). */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
) : Serializable {

    /** "S2 · B14" biçiminde kısa bölüm etiketi; numara yoksa başlık. */
    val episodeLabel: String
        get() = if (seasonNumber != null && episodeNumber != null) "S$seasonNumber · B$episodeNumber"
        else title
}

/**
 * "İzlemeye devam et" kaydı. Dizi başına tek kayıt tutulur.
 *
 * [episode] her zaman *şimdi oynatılacak* bölümü gösterir: bir bölüm
 * bittiğinde ve sıradaki bölüm biliniyorsa kayıt hemen ona kaydırılır.
 * Sıradaki bilinmiyorsa (sezonun/listenin sonu) [finished] true olur ve
 * sıradaki bölüm tıklandığında sağlayıcıdan sorulur. Böylece sağlayıcı yeni
 * bölüm eklediğinde de kayıt işe yarıyor.
 *
 * Filmlerde [episode] null, [parent] filmin kendisi.
 */
data class HistoryEntry(
    /** Dizinin veya filmin kimliği. */
    val key: String,
    val parent: MediaItem,
    val episode: MediaItem? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val finished: Boolean = false,
    val updatedAt: Long = 0L
) : Serializable {

    /** Oynatılacak öğe: dizide bölüm, filmde filmin kendisi. */
    val playable: MediaItem get() = episode ?: parent

    val isSeries: Boolean get() = episode != null

    /** Kart "sıradaki bölüm" olarak mı gösterilmeli (henüz başlanmamış). */
    val isUpNext: Boolean get() = isSeries && (finished || positionMs <= 0L)
}

enum class Engine { EXOPLAYER, VLC }

/** Oynatıcıdan gelen tek bir ses/altyazı kanalı. */
data class TrackOption(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
    /** Motorun kendi iç indeksi (VLC track id / ExoPlayer group index). */
    val engineIndex: Int = -1,
    val groupIndex: Int = -1,
    val trackIndexInGroup: Int = -1
)
