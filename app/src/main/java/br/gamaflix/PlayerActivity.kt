package br.gamaflix

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var osd: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private var index = 0
    private var triedTs = false
    private var resumeMs = 0L

    // Menu suspenso de canais (Ao vivo, tela cheia), aberto com a seta esquerda.
    private lateinit var menu: View
    private lateinit var rvMenuCats: RecyclerView
    private lateinit var rvMenuChannels: RecyclerView
    private var menuCats: List<Cat> = emptyList()
    private var menuCatIndex = 0
    private var menuChannels: List<Item> = emptyList()
    private val menuCatAdapter = CatAdapter { i, _ -> rvMenuCats.post { showMenuCategory(i) } }
    private val menuChannelAdapter = ChannelAdapter(
        onFocus = { },
        onClick = { p -> pickFromMenu(p) },
        onNeedEpg = { }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player)
        osd = findViewById(R.id.osd)
        menu = findViewById(R.id.menu)
        rvMenuCats = findViewById(R.id.rvMenuCats)
        rvMenuChannels = findViewById(R.id.rvMenuChannels)
        rvMenuCats.layoutManager = LinearLayoutManager(this)
        rvMenuChannels.layoutManager = LinearLayoutManager(this)
        rvMenuCats.adapter = menuCatAdapter
        rvMenuChannels.adapter = menuChannelAdapter
        // Seta esquerda na lista de canais volta às categorias do menu.
        menuChannelAdapter.onLeftEdge = { Ui.focusRow(rvMenuCats, menuCatIndex) }
        index = Store.playIndex
        resumeMs = Resume.pos
        Resume.pos = 0L
        view.useController = !Store.zap
        if (Store.playList.isEmpty()) finish()
    }

    override fun onStart() {
        super.onStart()
        if (Store.playList.isEmpty()) return

        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 2_000, 4_000)
            .setTargetBufferBytes(20 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val p = ExoPlayer.Builder(this, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cur = Store.playList.getOrNull(index)?.url
                if (Store.zap && !triedTs && cur != null && cur.contains("/live/") && cur.endsWith(".m3u8")) {
                    triedTs = true
                    start(cur.removeSuffix(".m3u8") + ".ts", 0)
                    return
                }
                showOsd("Não foi possível reproduzir (${error.errorCodeName})")
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                Store.playList.getOrNull(index)?.let { remember(it, 0L) }
                if (Store.autoNext && index < Store.playList.size - 1) play(index + 1) else finish()
            }
        })
        player = p
        view.player = p
        view.requestFocus()
        play(index, resumeMs)
        resumeMs = 0
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.release()
        player = null
        view.player = null
    }

    /** Guarda a posição atual para retomar depois (só filmes e séries). */
    private fun saveProgress() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) return
        val item = Store.playList.getOrNull(index) ?: return
        if (Store.zap || item.kind == Kind.LIVE) return
        val pos = p.currentPosition
        if (pos < 5_000) return
        resumeMs = pos
        remember(item, pos)
    }

    private fun remember(item: Item, posMs: Long) {
        val url = item.url ?: return
        History.save(this, Hist(item.id, item.kind, item.name, item.logo, url, posMs, System.currentTimeMillis()))
    }

    private fun play(i: Int, startMs: Long = 0) {
        val item = Store.playList.getOrNull(i) ?: return
        index = i
        triedTs = false
        showOsd(if (Store.zap) "${i + 1}  ·  ${item.name}" else item.name)
        remember(item, startMs)
        start(item.url ?: return, startMs)
    }

    private fun start(url: String, startMs: Long) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(url), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    private fun zap(delta: Int) {
        val n = Store.playList.size
        if (n == 0) return
        play(((index + delta) % n + n) % n)
    }

    private fun showOsd(text: String) {
        osd.text = text
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 3500)
    }

    // ---------- Menu suspenso (Ao vivo) ----------

    private val menuOpen get() = menu.visibility == View.VISIBLE

    /** Só existe para canais ao vivo: filmes e séries não têm esse menu. */
    private fun canOpenMenu(): Boolean =
        Store.zap && Store.cache[Kind.LIVE] != null && Store.playList.firstOrNull()?.kind == Kind.LIVE

    private fun openMenu() {
        val c = Store.cache[Kind.LIVE] ?: return
        menuCats = listOf(Cat("*", "Todos")) + c.cats.filter { c.byCat.containsKey(it.id) }
        // Abre na categoria do canal que está tocando (ou em "Todos").
        val current = Store.playList.getOrNull(index)
        menuCatIndex = menuCats.indexOfFirst { it.id == current?.cat }.takeIf { it >= 0 } ?: 0
        menuCatAdapter.setData(menuCats, menuCatIndex)
        menu.visibility = View.VISIBLE
        showMenuCategory(menuCatIndex)
        rvMenuCats.post { Ui.focusRow(rvMenuCats, menuCatIndex) }
    }

    private fun hideMenu() {
        if (!menuOpen) return
        menu.visibility = View.GONE
        view.requestFocus()
    }

    private fun showMenuCategory(i: Int) {
        val c = Store.cache[Kind.LIVE] ?: return
        val cat = menuCats.getOrNull(i) ?: return
        menuCatIndex = i
        menuCatAdapter.setSelected(i)
        menuChannels = if (cat.id == "*") c.items else c.byCat[cat.id].orEmpty()
        menuChannelAdapter.set(menuChannels)
        // Na categoria do canal atual, posiciona a lista no canal que está tocando.
        val current = Store.playList.getOrNull(index)
        val pos = menuChannels.indexOfFirst { it.id == current?.id }
        if (pos >= 0) rvMenuChannels.scrollToPosition(pos) else rvMenuChannels.scrollToPosition(0)
    }

    private fun pickFromMenu(p: Int) {
        if (p !in menuChannels.indices) return
        // Troca a lista de zapping pela categoria escolhida e toca o canal em tela cheia.
        Store.playList = menuChannels
        hideMenu()
        play(p)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (menuOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { hideMenu(); return true }
                // Descer no último canal fica nele (não volta ao topo).
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val vh = currentFocus?.let { rvMenuChannels.findContainingViewHolder(it) }
                    if (vh != null && vh.bindingAdapterPosition == menuChannels.size - 1) return true
                }
                // Seta esquerda na coluna de categorias fecha o menu (volta ao vídeo).
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (rvMenuCats.hasFocus()) { hideMenu(); return true }
            }
            // Com o menu aberto, as demais teclas navegam dentro dele (não zapeiam).
            return super.dispatchKeyEvent(event)
        }

        if (Store.zap) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (canOpenMenu()) { openMenu(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    showOsd("${index + 1}  ·  ${Store.playList[index].name}")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOsd)
        super.onDestroy()
    }
}
