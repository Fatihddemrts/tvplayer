package com.orhan.iptv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.orhan.iptv.R
import com.orhan.iptv.data.Catalog
import com.orhan.iptv.data.CatalogLoader
import com.orhan.iptv.data.HistoryEntry
import com.orhan.iptv.data.MediaItem
import com.orhan.iptv.data.MediaKind
import com.orhan.iptv.data.Repository
import com.orhan.iptv.data.SourceType
import com.orhan.iptv.data.meta.MetadataCache
import com.orhan.iptv.player.PlaybackActivity
import com.orhan.iptv.ui.detail.DetailsActivity
import com.orhan.iptv.ui.music.MusicBrowseActivity
import com.orhan.iptv.ui.nas.NasBrowseActivity
import com.orhan.iptv.ui.photo.PhotoViewerActivity
import com.orhan.iptv.ui.profile.ProfilesActivity
import com.orhan.iptv.ui.search.SearchActivity
import com.orhan.iptv.ui.settings.SettingsActivity
import com.orhan.iptv.ui.setup.AddSourceActivity
import kotlinx.coroutines.launch

/** Kenar çubuğundaki bölümler. */
private enum class Section { HOME, LIVE, MOVIES, SERIES }

/** İçerik listesinin çizim biçimi. */
private enum class ViewMode(val label: String) {
    GRID("Kılavuz"),
    LIST("Liste"),
    TILE("Karo")
}

/** Sıralama ölçütü. */
private enum class SortMode(val label: String) {
    ORIGINAL("Sıra: Listedeki"),
    NAME("Sıra: Ada göre")
}

/**
 * Ana sayfa billboard'unda gösterilen içerik.
 * [entry] doluysa bir "izlemeye devam et" kaydıdır; [list] canlı kanallarda
 * oynatıcıya verilecek sıradır (kanal yukarı/aşağı bu listede gezer).
 */
private data class Hero(
    val item: MediaItem,
    val entry: HistoryEntry? = null,
    val list: List<MediaItem> = emptyList()
)

/**
 * Ana ekran.
 *
 * Ana Sayfa Netflix düzeninde: üstte odaklanan içeriğe göre değişen bir
 * billboard (görsel, ad, künye, özet, Oynat/Detaylar), altında yatay raflar.
 * Diğer bölümler solda kategori sütunu, sağda içerik ızgarası. Katalog bir
 * kez yüklenip bellekte tutuluyor, bölüm ve kategori değişimi ağ beklemeden
 * çiziliyor.
 */
class MainActivity : AppCompatActivity() {

    // Ana Sayfa
    private lateinit var homePane: View
    private lateinit var homeScroll: NestedScrollView
    private lateinit var railsContainer: LinearLayout
    private lateinit var emptyHint: TextView

    // Billboard
    private lateinit var heroBackdrop: ImageView
    private lateinit var heroKicker: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private lateinit var heroButtons: View
    private lateinit var heroPlay: TextView
    private lateinit var heroMore: TextView

    /** Billboard'da şu an gösterilen içerik; düğmeler buna göre çalışır. */
    private var hero: Hero? = null
    private var heroImageUrl: String? = null

    /** Geçmiş yokken öne çıkarılan içerik; oturum boyunca sabit kalsın. */
    private var featuredId: String? = null

    /**
     * Kumanda raflarda hızla gezinirken billboard her kartta yeniden
     * çizilmesin: odak kısa bir süre aynı kartta kalınca güncellenir.
     */
    private val heroHandler = Handler(Looper.getMainLooper())
    private var pendingHero: Runnable? = null

    // Gözat
    private lateinit var browsePane: View
    private lateinit var sectionTitle: TextView
    private lateinit var categoryList: RecyclerView
    private lateinit var contentGrid: RecyclerView
    private lateinit var contentTitle: TextView
    private lateinit var contentEmpty: TextView
    private lateinit var viewModeButton: TextView
    private lateinit var sortButton: TextView

    private lateinit var loading: View

    private val navIcons = HashMap<Section, TextView>()

    private var catalog = Catalog()
    private var section = Section.HOME

    private var viewMode = ViewMode.GRID
    private var sortMode = SortMode.ORIGINAL

    /** Geçerli bölümün kategori adı → öğeler eşlemesi, ekrandaki sırayla. */
    private var groups: List<Pair<String, List<MediaItem>>> = emptyList()

    private lateinit var categoryAdapter: CategoryAdapter
    private var contentAdapter: MediaAdapter? = null

    /** "İzlemeye Devam Et" rafı; oynatıcıdan dönünce yalnız bu tazeleniyor. */
    private var continueRail: View? = null
    private var continueAdapter: ContinueAdapter? = null

    /** Açılışta kumanda odağı bir kez "devam et" kartına verilir. */
    private var firstHomeRender = true

    /** Katalog yüklenirken ikinci bir yükleme başlatılmasın. */
    private var loadInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Profil seçilmeden buraya gelinirse profil ekranına dön.
        if (Repository.activeProfile() == null) {
            startActivity(Intent(this, ProfilesActivity::class.java))
            finish()
            return
        }

        bindViews()
        setupSidebar()
        setupBrowse()
        // Geçmiş yerel olarak kayıtlı: katalog ağdan gelmeden "devam et"
        // rafı çizilsin, kullanıcı açar açmaz OK'a basabilsin.
        renderHome()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!::railsContainer.isInitialized) return
        if (catalog.isEmpty && Repository.sources().isNotEmpty()) {
            load()
        } else if (section == Section.HOME) {
            // Oynatıcıdan dönüldü: kaldığı yer ve sıradaki bölüm değişmiş olabilir.
            refreshContinueRail()
        }
    }

    override fun onDestroy() {
        heroHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindViews() {
        homePane = findViewById(R.id.home_pane)
        homeScroll = findViewById(R.id.home_scroll)
        railsContainer = findViewById(R.id.rails_container)
        emptyHint = findViewById(R.id.empty_hint)

        heroBackdrop = findViewById(R.id.hero_backdrop)
        heroKicker = findViewById(R.id.hero_kicker)
        heroTitle = findViewById(R.id.hero_title)
        heroMeta = findViewById(R.id.hero_meta)
        heroDesc = findViewById(R.id.hero_desc)
        heroButtons = findViewById(R.id.hero_buttons)
        heroPlay = findViewById(R.id.hero_play)
        heroMore = findViewById(R.id.hero_more)

        browsePane = findViewById(R.id.browse_pane)
        sectionTitle = findViewById(R.id.section_title)
        categoryList = findViewById(R.id.category_list)
        contentGrid = findViewById(R.id.content_grid)
        contentTitle = findViewById(R.id.content_title)
        contentEmpty = findViewById(R.id.content_empty)
        viewModeButton = findViewById(R.id.btn_view_mode)
        sortButton = findViewById(R.id.btn_sort)

        loading = findViewById(R.id.loading)

        heroPlay.setOnClickListener { playHero() }
        heroMore.setOnClickListener { openHeroDetails() }
        // Billboard düğmesine gelindiğinde raflar başa dönsün; düğmeler ve
        // ilk raf birlikte görünür kalır.
        val scrollTopOnFocus = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) homeScroll.smoothScrollTo(0, 0)
        }
        heroPlay.onFocusChangeListener = scrollTopOnFocus
        heroMore.onFocusChangeListener = scrollTopOnFocus

        findViewById<View>(R.id.btn_search).setOnClickListener { openSearchForSection() }
    }

    /** Gözat ekranındaki arama yalnız o bölümde arar; ana sayfadan her şeyde. */
    private fun openSearchForSection() = SearchActivity.start(
        this,
        when (section) {
            Section.LIVE -> SearchActivity.Scope.LIVE
            Section.MOVIES -> SearchActivity.Scope.VOD
            Section.SERIES -> SearchActivity.Scope.SERIES
            Section.HOME -> SearchActivity.Scope.ALL
        }
    )

    // --- Kenar çubuğu ---

    private fun setupSidebar() {
        navIcons[Section.HOME] = findViewById(R.id.nav_home)
        navIcons[Section.LIVE] = findViewById(R.id.nav_live)
        navIcons[Section.MOVIES] = findViewById(R.id.nav_movies)
        navIcons[Section.SERIES] = findViewById(R.id.nav_series)

        applySidebarLabels()

        navIcons.forEach { (target, icon) -> icon.setOnClickListener { show(target) } }
        findViewById<View>(R.id.nav_search).setOnClickListener { openSearchForSection() }
        findViewById<View>(R.id.nav_music).setOnClickListener {
            startActivity(Intent(this, MusicBrowseActivity::class.java))
        }
        findViewById<View>(R.id.nav_nas).setOnClickListener { openNas() }
        // Yenile: Xtream/M3U kataloğunu sunucudan baştan indirir; sağlayıcıda
        // kategori eklenip çıkarıldığında uygulamanın haberi olsun.
        findViewById<View>(R.id.nav_refresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.nav_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        markActiveNav()
    }

    /**
     * Dar ekranlarda kenar çubuğunu ikon-only yapar.
     *
     * Etiketler XML'de duruyor; burada yalnız gizleniyor. Böylece erişilebilirlik
     * için `contentDescription` yerine gerçek metin korunuyor ve büyük ekranda
     * aynı düzen etiketli çalışıyor.
     */
    private fun applySidebarLabels() {
        if (resources.getBoolean(R.bool.sidebar_show_labels)) return

        val allItems = navIcons.values + listOf<TextView>(
            findViewById(R.id.nav_search),
            findViewById(R.id.nav_music),
            findViewById(R.id.nav_nas),
            findViewById(R.id.nav_refresh),
            findViewById(R.id.nav_settings)
        )
        allItems.forEach { item ->
            item.contentDescription = item.text
            item.text = ""
            // İkonu ortala: metin gidince drawableStart sola yapışık kalıyor.
            item.gravity = Gravity.CENTER
        }
        // Marka adı sığmıyor; baş harfi kalsın.
        findViewById<TextView>(R.id.nav_brand).apply {
            text = getString(R.string.app_name).take(1)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
    }

    private fun markActiveNav() {
        navIcons.forEach { (target, icon) -> icon.isSelected = target == section }
    }

    private fun show(target: Section) {
        section = target
        markActiveNav()
        render()
    }

    // --- Gözat kurulumu ---

    private fun setupBrowse() {
        categoryAdapter = CategoryAdapter { index, _ -> showCategory(index) }
        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter

        viewModeButton.setOnClickListener {
            // Üç mod arasında sırayla dolaş; TV'de menü açmaktan hızlı.
            viewMode = ViewMode.values()[(viewMode.ordinal + 1) % ViewMode.values().size]
            viewModeButton.text = viewMode.label
            showCategory(currentCategoryIndex)
        }
        sortButton.setOnClickListener {
            sortMode = SortMode.values()[(sortMode.ordinal + 1) % SortMode.values().size]
            sortButton.text = sortMode.label
            showCategory(currentCategoryIndex)
        }
        viewModeButton.text = viewMode.label
        sortButton.text = sortMode.label
    }

    // --- Yükleme ---

    /**
     * Katalogları sunucudan baştan indirir.
     *
     * Sağlayıcıda kategori/kanal eklenip çıkarıldığında uygulama bunu
     * kendiliğinden fark etmiyor; katalog yalnız açılışta çekiliyor.
     */
    private fun refresh() {
        if (Repository.sources().isEmpty()) {
            Toast.makeText(this, "Yenilenecek kaynak yok", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "İçerik yenileniyor…", Toast.LENGTH_SHORT).show()
        catalog = Catalog()
        load()
    }

    private fun load() {
        val sources = Repository.sources()
        if (sources.isEmpty()) {
            showEmptyState()
            return
        }
        if (loadInProgress) return
        loadInProgress = true

        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val errors = ArrayList<String>()
            try {
                catalog = CatalogLoader.loadAll(sources) { source, error ->
                    errors += "${source.name}: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                loadInProgress = false
            }
            loading.visibility = View.GONE
            render()
            if (errors.isNotEmpty()) {
                Toast.makeText(this@MainActivity, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showEmptyState() {
        showHomeChrome(true)
        emptyHint.visibility = View.VISIBLE
        // Ana sayfada "Kaynak Ekle" düğmesi yok; kaynak yönetimi Ayarlar'da.
        emptyHint.text = "Henüz kaynak yok. Sol alttaki ⚙ Ayarlar'dan M3U, Xtream veya NAS ekleyin."
    }

    // --- Çizim ---

    private fun render() {
        if (section == Section.HOME) {
            showHomeChrome(true)
            renderHome()
        } else {
            showHomeChrome(false)
            renderBrowse()
        }
    }

    /** Ana Sayfa ile Gözat arasında geçiş. */
    private fun showHomeChrome(home: Boolean) {
        homePane.visibility = if (home) View.VISIBLE else View.GONE
        browsePane.visibility = if (home) View.GONE else View.VISIBLE
    }

    private fun renderHome() {
        // Odak ana sayfadaysa (veya hiç yoksa) yeniden çizimden sonra "devam et"
        // kartına geri ver; kenar çubuğundaysa kullanıcıyı oradan koparma.
        val takeFocus = firstHomeRender || homePane.hasFocus() || currentFocus == null
        firstHomeRender = false

        railsContainer.removeAllViews()
        continueRail = null
        continueAdapter = null
        emptyHint.visibility = View.GONE

        // İzlemeye devam et en üstte: kullanıcının çoğu zaman aradığı şey bu.
        val entries = continueEntries()
        addContinueRail(entries, takeFocus)

        // Favoriler türlerine göre ayrı raflarda; boş raf gösterilmiyor.
        val favorites = Repository.favorites()
        addRail("Favori Kanallar", favorites.filter { it.kind == MediaKind.LIVE }, CardStyle.CHANNEL)
        addRail("Favori Filmler", favorites.filter { it.kind == MediaKind.VOD }, CardStyle.POSTER)
        addRail("Favori Diziler", favorites.filter { it.kind == MediaKind.SERIES }, CardStyle.POSTER)

        // Katalogdan tadımlık raflar: her türün ilk birkaç kategorisi.
        addKindRails(catalog.vod, getString(R.string.row_vod))
        addKindRails(catalog.series, getString(R.string.row_series))
        addRail(getString(R.string.row_live), catalog.live.take(PREVIEW_COUNT), CardStyle.CHANNEL)

        showHero(defaultHero(entries))
        // "Devam et" yoksa odak billboard'un Oynat düğmesine.
        if (takeFocus && continueRail == null && heroButtons.visibility == View.VISIBLE) {
            heroPlay.requestFocus()
        }

        if (catalog.isEmpty && Repository.sources().isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = getString(R.string.empty_hint)
        }
    }

    // --- Billboard ---

    /**
     * Açılıştaki billboard: son izlenen içerik; geçmiş yoksa görseli olan
     * filmlerden/dizilerden rastgele biri (oturum boyunca aynı kalır).
     */
    private fun defaultHero(entries: List<HistoryEntry>): Hero? {
        entries.firstOrNull()?.let { return Hero(it.parent, it) }

        val pool = (
            catalog.vod.asSequence().filter { !it.logoUrl.isNullOrBlank() }.take(FEATURED_POOL) +
                catalog.series.asSequence().filter { !it.logoUrl.isNullOrBlank() }.take(FEATURED_POOL)
            ).toList()
        if (pool.isEmpty()) return null
        val featured = pool.firstOrNull { it.id == featuredId }
            ?: pool.random().also { featuredId = it.id }
        return Hero(featured)
    }

    /** Kart odağı biraz oturunca billboard'u o içeriğe çevirir. */
    private fun scheduleHero(target: Hero) {
        pendingHero?.let { heroHandler.removeCallbacks(it) }
        val task = Runnable { showHero(target) }
        pendingHero = task
        heroHandler.postDelayed(task, HERO_DELAY_MS)
    }

    private fun showHero(target: Hero?) {
        hero = target
        if (target == null) {
            heroKicker.visibility = View.GONE
            heroTitle.text = getString(R.string.app_name)
            heroMeta.visibility = View.GONE
            heroDesc.visibility = View.GONE
            heroButtons.visibility = View.GONE
            setHeroImage(null)
            return
        }

        val item = target.item
        val entry = target.entry
        val detail = (MetadataCache.get(item.id) as? MetadataCache.Entry.Hit)?.detail

        heroKicker.visibility = View.VISIBLE
        heroKicker.text = when {
            entry != null -> "İZLEMEYE DEVAM ET"
            item.kind == MediaKind.LIVE -> "CANLI YAYIN"
            item.kind == MediaKind.SERIES -> "DİZİ"
            item.kind == MediaKind.VOD -> "FİLM"
            else -> ""
        }
        heroTitle.text = item.title

        val meta = when {
            entry != null -> continueMeta(entry)
            detail != null -> listOfNotNull(
                detail.year,
                detail.rating?.let { "★ %.1f".format(it) },
                detail.durationText,
                detail.genre
            ).joinToString("  ·  ")
            else -> item.group.orEmpty()
        }
        heroMeta.text = meta
        heroMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        val description = (entry?.episode?.description ?: detail?.plot ?: item.description)
            ?.takeIf { it.isNotBlank() }
        heroDesc.text = description.orEmpty()
        heroDesc.visibility = if (description == null) View.GONE else View.VISIBLE

        heroButtons.visibility = View.VISIBLE
        heroPlay.text = when {
            entry != null && entry.isUpNext -> "▶  Sıradaki Bölüm"
            entry != null -> "▶  Devam Et"
            item.kind == MediaKind.LIVE -> "▶  İzle"
            item.kind == MediaKind.VOD && Repository.position(item.id) > 0 -> "▶  Devam Et"
            else -> "▶  Oynat"
        }
        val hasDetails = item.kind == MediaKind.VOD || item.kind == MediaKind.SERIES
        if (!hasDetails && heroMore.hasFocus()) heroPlay.requestFocus()
        heroMore.visibility = if (hasDetails) View.VISIBLE else View.GONE

        // Canlı kanal logoları küçük; geniş görsel olarak çirkin büyüyor.
        val image = if (item.kind == MediaKind.LIVE) null else {
            entry?.episode?.logoUrl?.takeIf { it.isNotBlank() }
                ?: detail?.posterUrl?.takeIf { it.isNotBlank() }
                ?: item.logoUrl?.takeIf { it.isNotBlank() }
        }
        setHeroImage(image)
    }

    private fun setHeroImage(url: String?) {
        if (url == heroImageUrl) return
        heroImageUrl = url
        if (url == null) {
            Glide.with(this).clear(heroBackdrop)
            heroBackdrop.setImageDrawable(null)
        } else {
            Glide.with(this)
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade(HERO_FADE_MS))
                .into(heroBackdrop)
        }
    }

    /** "S2 · B14 · 23 dk kaldı" gibi; "devam et" kartındaki satırla aynı dil. */
    private fun continueMeta(entry: HistoryEntry): String {
        val remaining = if (entry.durationMs > 0) {
            ((entry.durationMs - entry.positionMs) / 60_000L).coerceAtLeast(1)
        } else -1L
        val remainingText = if (remaining > 0) "$remaining dk kaldı" else "Kaldığı yerden"
        return when {
            entry.finished -> "${entry.playable.episodeLabel} bitti · sıradaki bölüm"
            entry.isUpNext -> "Sıradaki: ${entry.playable.episodeLabel}"
            entry.isSeries -> "${entry.playable.episodeLabel}  ·  $remainingText"
            else -> remainingText
        }
    }

    private fun playHero() {
        val target = hero ?: return
        val entry = target.entry
        when {
            entry != null -> ContinueWatching.play(this, entry, loading)
            target.item.kind == MediaKind.LIVE ->
                openFromList(target.list.ifEmpty { listOf(target.item) }, target.item)
            // Film kaldığı yerden doğrudan başlar; dizide bölüm seçimi gerekir.
            target.item.kind == MediaKind.VOD -> PlaybackActivity.start(this, target.item)
            else -> open(target.item)
        }
    }

    private fun openHeroDetails() {
        val item = hero?.item ?: return
        if (item.kind != MediaKind.VOD && item.kind != MediaKind.SERIES) return
        startActivity(
            Intent(this, DetailsActivity::class.java).putExtra(DetailsActivity.EXTRA_ITEM, item)
        )
    }

    /**
     * Bir rafta kart odak aldı: raf billboard'un hemen altına kaysın
     * (Netflix TV'deki gibi odaklı raf hep aynı yerde) ve billboard o
     * içeriği göstersin.
     */
    private fun onCardFocused(rail: View, target: Hero) {
        homeScroll.post { homeScroll.smoothScrollTo(0, railsContainer.top + rail.top) }
        scheduleHero(target)
    }

    // --- İzlemeye Devam Et ---

    /**
     * Geçmiş kayıtları, son izlenen başta. Bu özellikten önce kaldığı yeri
     * kaydedilmiş filmler de (geçmişte kaydı olmayanlar) sona eklenir ki
     * güncellemeden sonra raf boş görünmesin.
     */
    private fun continueEntries(): List<HistoryEntry> {
        val history = Repository.history()
        val keys = history.mapTo(HashSet()) { it.key }
        val legacy = catalog.vod.asSequence()
            .filter { it.id !in keys && Repository.position(it.id) > 0 }
            .take(10)
            .map { HistoryEntry(it.id, it, null, Repository.position(it.id)) }
            .toList()
        return history + legacy
    }

    @android.annotation.SuppressLint("InvalidSetHasFixedSize")
    private fun addContinueRail(entries: List<HistoryEntry>, takeFocus: Boolean) {
        if (entries.isEmpty()) return

        val rail = layoutInflater.inflate(R.layout.view_rail, railsContainer, false)
        rail.findViewById<TextView>(R.id.rail_title).text = continueTitle(entries)
        val list = rail.findViewById<RecyclerView>(R.id.rail_list)
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.setHasFixedSize(true)
        list.clipChildren = false
        (rail as android.view.ViewGroup).clipChildren = false

        val adapter = ContinueAdapter(
            onClick = { entry -> ContinueWatching.play(this, entry, loading) },
            onLongClick = { entry ->
                Repository.removeHistory(entry.key)
                if (!entry.isSeries) Repository.clearPosition(entry.key)
                Toast.makeText(this, "${entry.parent.title} listeden kaldırıldı", Toast.LENGTH_SHORT).show()
                refreshContinueRail()
            },
            onFocus = { entry -> onCardFocused(rail, Hero(entry.parent, entry)) }
        )
        list.adapter = adapter
        adapter.submit(entries)

        railsContainer.addView(rail, 0)
        continueRail = rail
        continueAdapter = adapter

        if (takeFocus) focusFirstCard(list)
    }

    private fun continueTitle(entries: List<HistoryEntry>) =
        if (entries.size > 1) "İzlemeye Devam Et  ·  ${entries.size}" else "İzlemeye Devam Et"

    /** Yalnız "devam et" rafını günceller; diğer raflar ve odak yerinde kalır. */
    private fun refreshContinueRail() {
        val entries = continueEntries()
        val rail = continueRail
        val adapter = continueAdapter

        // Billboard bir geçmiş kaydı gösteriyorsa en son izlenene güncellensin.
        if (hero == null || hero?.entry != null) showHero(defaultHero(entries))

        if (rail == null || adapter == null) {
            if (entries.isNotEmpty()) addContinueRail(entries, takeFocus = currentFocus == null)
            return
        }
        val hadFocus = rail.hasFocus()
        if (entries.isEmpty()) {
            railsContainer.removeView(rail)
            continueRail = null
            continueAdapter = null
            if (hadFocus) {
                if (heroButtons.visibility == View.VISIBLE) heroPlay.requestFocus()
                else railsContainer.requestFocus()
            }
            return
        }

        rail.findViewById<TextView>(R.id.rail_title).text = continueTitle(entries)
        adapter.submit(entries)
        // Az önce izlenen içerik en başa geçti; odak da onunla gitsin.
        if (hadFocus || currentFocus == null) {
            focusFirstCard(rail.findViewById(R.id.rail_list))
        }
    }

    private fun focusFirstCard(list: RecyclerView) {
        list.scrollToPosition(0)
        list.doOnNextLayout {
            list.layoutManager?.findViewByPosition(0)
                ?.findViewById<View>(R.id.card_frame)
                ?.requestFocus()
            homeScroll.smoothScrollTo(0, 0)
        }
        list.requestLayout()
    }

    /**
     * Bir türün (film/dizi) ilk birkaç kategorisini ayrı raflar olarak ekler.
     * Listede kategori yoksa ya da tek kategori varsa tek bir raf çizilir.
     */
    private fun addKindRails(items: List<MediaItem>, fallbackTitle: String) {
        if (items.isEmpty()) return
        // groupBy ilk görülme sırasını koruyor: sağlayıcının sıralaması
        // (çoğunlukla "en yeniler" başta) raflara da yansısın.
        val byGroup = items.filter { !it.group.isNullOrBlank() }.groupBy { it.group!! }
        if (byGroup.size < 2) {
            addRail(fallbackTitle, items.take(PREVIEW_COUNT), CardStyle.POSTER)
            return
        }
        byGroup.entries.take(MAX_GROUP_RAILS).forEach { (name, list) ->
            addRail(name, list.take(PREVIEW_COUNT), CardStyle.POSTER)
        }
    }

    private fun renderBrowse() {
        val items = when (section) {
            Section.LIVE -> catalog.live
            Section.MOVIES -> catalog.vod
            Section.SERIES -> catalog.series
            Section.HOME -> emptyList()
        }
        // Kısa başlıklar: kategori sütunu dar, uzun adlar kırpılıyor.
        sectionTitle.text = when (section) {
            Section.LIVE -> "Canlı"
            Section.MOVIES -> "Filmler"
            Section.SERIES -> "Diziler"
            Section.HOME -> ""
        }

        // Kategoriler playlist'in grup başlıklarından geliyor; grupsuz öğeler
        // sona "Diğer" olarak toplanıyor.
        val named = items
            .filter { !it.group.isNullOrBlank() }
            .groupBy { it.group!! }
            .toList()
            .sortedBy { it.first.lowercase() }
        val ungrouped = items.filter { it.group.isNullOrBlank() }

        groups = buildList {
            if (items.isNotEmpty() && named.size != 1) {
                add("Tümü" to items)
            }
            addAll(named)
            if (ungrouped.isNotEmpty()) add("Diğer" to ungrouped)
        }

        categoryAdapter.submit(groups.map { Category(it.first, it.second.size) })
        currentCategoryIndex = 0
        showCategory(0)
    }

    private var currentCategoryIndex = 0

    private fun showCategory(index: Int) {
        currentCategoryIndex = index
        val group = groups.getOrNull(index)
        val items = group?.second.orEmpty().let { list ->
            when (sortMode) {
                SortMode.ORIGINAL -> list
                SortMode.NAME -> list.sortedBy { it.title.lowercase() }
            }
        }

        contentTitle.text = group?.let { "${it.first}  ·  ${it.second.size}" }.orEmpty()
        contentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        contentEmpty.text = "Bu kategoride içerik yok"

        val style = when (viewMode) {
            ViewMode.LIST -> CardStyle.LIST
            ViewMode.TILE -> CardStyle.CHANNEL
            ViewMode.GRID -> if (section == Section.LIVE) CardStyle.CHANNEL else CardStyle.POSTER
        }

        // Kanal kartları sütunu doldursun: sabit 160dp'de "SD: beIN Sports 1"
        // gibi adlar "SD: b…" diye kırpılıyor.
        val fillWidth = style == CardStyle.CHANNEL

        contentGrid.layoutManager = if (style == CardStyle.LIST) {
            LinearLayoutManager(this)
        } else {
            // Kart genişliği sabit; kaç sütun sığdığını ölçülen genişlikten bul.
            GridLayoutManager(this, spanCountFor(style))
        }
        // Odaklanan kart 1.1 ölçekte büyürken komşularına taşabilsin.
        contentGrid.clipChildren = false

        val adapter = MediaAdapter(
            style = style,
            fillWidth = fillWidth,
            // Sırayı da geçiriyoruz: oynatıcıda önceki/sonraki ve kanal
            // tuşları bu liste üzerinde gezinsin.
            onClick = { openFromList(items, it) },
            onLongClick = { item -> toggleFavorite(item) }
        )
        contentAdapter = adapter
        contentGrid.adapter = adapter
        adapter.submit(items)
        contentGrid.scrollToPosition(0)
    }

    private fun toggleFavorite(item: MediaItem) {
        val added = Repository.toggleFavorite(item)
        Toast.makeText(
            this,
            if (added) "${item.title} favorilere eklendi"
            else "${item.title} favorilerden çıkarıldı",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Ölçülen genişliğe kaç kart sığıyor; en az 1. */
    private fun spanCountFor(style: CardStyle): Int {
        val cardWidthDp = when (style) {
            CardStyle.POSTER -> 110 + 16
            CardStyle.CHANNEL -> 160 + 16
            else -> 200 + 16
        }
        val density = resources.displayMetrics.density
        val availablePx = contentGrid.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - (260 * density).toInt())
        return (availablePx / (cardWidthDp * density)).toInt().coerceAtLeast(1)
    }

    /**
     * Yatay bir raf ekler; boş listede hiçbir şey çizmez.
     *
     * Lint uyarısı bastırılıyor: kontrol, düzendeki wrap_content'i kaydırma
     * yönü sanıyor. Raf YATAY kaydırıyor, o yöndeki ölçü match_parent;
     * wrap_content yalnız yükseklikte ve kart boyları sabit.
     */
    @android.annotation.SuppressLint("InvalidSetHasFixedSize")
    private fun addRail(title: String, items: List<MediaItem>, style: CardStyle) {
        if (items.isEmpty()) return
        val rail = layoutInflater.inflate(R.layout.view_rail, railsContainer, false)
        rail.findViewById<TextView>(R.id.rail_title).text = title

        val list = rail.findViewById<RecyclerView>(R.id.rail_list)
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.setHasFixedSize(true)
        list.clipChildren = false
        (rail as? android.view.ViewGroup)?.clipChildren = false

        val adapter = MediaAdapter(
            style = style,
            onClick = { openFromList(items, it) },
            onFocus = { item -> onCardFocused(rail, Hero(item, list = items)) },
            onLongClick = { item -> toggleFavorite(item) }
        )
        list.adapter = adapter
        adapter.submit(items)

        railsContainer.addView(rail)
    }

    // --- Gezinme ---

    /**
     * Öğeyi içinde bulunduğu listeyle birlikte açar; oynatıcı önceki/sonraki
     * için bu sırayı kullanır.
     */
    private fun openFromList(list: List<MediaItem>, item: MediaItem) {
        // Film ve diziler önce detay sayfası açar: özet, puan ve dizilerde
        // sezon/bölüm seçimi oradan yapılıyor. Canlı kanallar doğrudan açılır,
        // çünkü orada gösterilecek bir ayrıntı yok.
        if (item.kind == MediaKind.VOD || item.kind == MediaKind.SERIES) {
            startActivity(
                Intent(this, DetailsActivity::class.java)
                    .putExtra(DetailsActivity.EXTRA_ITEM, item)
            )
            return
        }
        if (item.kind == MediaKind.PHOTO) {
            open(item)
            return
        }
        val index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        PlaybackActivity.start(this, list, index)
    }

    private fun open(item: MediaItem) {
        when (item.kind) {
            // Xtream dizileri bölüm listesi açar. M3U listelerinde her bölüm
            // zaten ayrı bir oynatılabilir öğe olduğu için doğrudan başlatılır.
            MediaKind.SERIES ->
                if (item.seriesId != null) {
                    startActivity(
                        Intent(this, DetailsActivity::class.java)
                            .putExtra(DetailsActivity.EXTRA_ITEM, item)
                    )
                } else {
                    PlaybackActivity.start(this, item)
                }
            MediaKind.PHOTO -> PhotoViewerActivity.start(this, listOf(item), 0)
            else -> PlaybackActivity.start(this, item)
        }
    }

    private fun openAddSource() = startActivity(Intent(this, AddSourceActivity::class.java))

    private fun openNas() {
        val nasSources = Repository.sources().filter { it.type == SourceType.SMB }
        if (nasSources.isEmpty()) {
            Toast.makeText(this, "Önce bir NAS kaynağı ekleyin", Toast.LENGTH_LONG).show()
            openAddSource()
            return
        }
        startActivity(
            Intent(this, NasBrowseActivity::class.java)
                .putExtra(NasBrowseActivity.EXTRA_SOURCE, nasSources.first())
        )
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            // İçerikteyken önce kategori sütununa dön.
            section != Section.HOME && contentGrid.hasFocus() -> categoryList.requestFocus()
            section != Section.HOME -> show(Section.HOME)
            // Raflarda aşağı inilmişse GERİ önce başa, billboard'a döner.
            homeScroll.hasFocus() && heroButtons.visibility == View.VISIBLE -> {
                homeScroll.smoothScrollTo(0, 0)
                heroPlay.requestFocus()
            }
            else -> super.onBackPressed()
        }
    }

    /**
     * Kumandanın gezinme dışı tuşları.
     *
     * Sony kumandasında SEARCH tuşu yok; arama renkli tuşa ve GUIDE'a
     * bağlanıyor. Renkli tuşlar referans IPTV uygulamalarındaki alışkanlığı
     * izliyor: kırmızı arama, yeşil görünüm, sarı sıralama.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_SEARCH,
            android.view.KeyEvent.KEYCODE_PROG_RED -> {
                openSearchForSection()
                return true
            }

            android.view.KeyEvent.KEYCODE_PROG_GREEN -> {
                if (section != Section.HOME) { viewModeButton.performClick(); return true }
            }

            android.view.KeyEvent.KEYCODE_PROG_YELLOW -> {
                if (section != Section.HOME) { sortButton.performClick(); return true }
            }

            android.view.KeyEvent.KEYCODE_PROG_BLUE -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }

            // Kanal tuşlarıyla bölümler arasında dolaş.
            android.view.KeyEvent.KEYCODE_CHANNEL_UP -> {
                show(Section.values()[(section.ordinal + 1) % Section.values().size])
                return true
            }

            android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val values = Section.values()
                show(values[(section.ordinal - 1 + values.size) % values.size])
                return true
            }

            android.view.KeyEvent.KEYCODE_GUIDE -> {
                show(Section.LIVE)
                return true
            }

            android.view.KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        /** Ana sayfada her raftan gösterilen tadımlık öğe sayısı. */
        const val PREVIEW_COUNT = 20

        /** Film ve dizilerde ana sayfaya taşınan kategori sayısı. */
        const val MAX_GROUP_RAILS = 3

        /** Öne çıkan içerik için bakılan film/dizi sayısı (her türden). */
        const val FEATURED_POOL = 30

        /** Kart odağı bu kadar kalınca billboard güncellenir. */
        const val HERO_DELAY_MS = 220L
        const val HERO_FADE_MS = 300
    }
}
