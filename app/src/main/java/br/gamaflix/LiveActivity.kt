package br.gamaflix

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
class LiveActivity : AppCompatActivity() {

    private lateinit var rvCats: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var preview: PlayerView
    private lateinit var progress: View
    private lateinit var tvEmpty: TextView
    private lateinit var tvPreviewHint: TextView
    private lateinit var tvNowTitle: TextView
    private lateinit var tvNowTime: TextView
    private lateinit var tvNowDesc: TextView
    private lateinit var tvNextTitle: TextView
    private lateinit var tvNextTime: TextView

    private val catAdapter = CatAdapter { i, _ -> onCat(i) }
    private val channelAdapter = ChannelAdapter(
        onFocus = { p -> schedulePreview(p) },
        onClick = { p -> openFull(p) }
    )

    private var cats: List<Cat> = emptyList()
    private var channels: List<Item> = emptyList()
    private var selected = -1
    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val epgCache = HashMap<String, Pair<Long, List<Prog>>>()
    private val previewRunnable = Runnable { previewSelected() }
    private val ticker = object : Runnable {
        override fun run() {
            refreshNowNext()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.source == null) Store.source = Prefs.buildSource(this)
        if (Store.source == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_live)
        rvCats = findViewById(R.id.rvCats)
        rvChannels = findViewById(R.id.rvChannels)
        preview = findViewById(R.id.preview)
        progress = findViewById(R.id.progress)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvPreviewHint = findViewById(R.id.tvPreviewHint)
        tvNowTitle = findViewById(R.id.tvNowTitle)
        tvNowTime = findViewById(R.id.tvNowTime)
        tvNowDesc = findViewById(R.id.tvNowDesc)
        tvNextTitle = findViewById(R.id.tvNextTitle)
        tvNextTime = findViewById(R.id.tvNextTime)

        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = channelAdapter
        rvCats.itemAnimator = null
        rvChannels.itemAnimator = null

        // Clicar na prévia também abre o canal em tela cheia.
        preview.setOnClickListener { if (selected >= 0) openFull(selected) }
        loadCatalog()
    }

    private fun loadCatalog() {
        val source = Store.source ?: return
        progress.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val c = Store.cache[Kind.LIVE] ?: source.load(Kind.LIVE).also { Store.cache[Kind.LIVE] = it }
                cats = listOf(Cat("*", "Todos")) + c.cats.filter { c.byCat.containsKey(it.id) }
                catAdapter.setData(cats, 0)
                showCategory(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvEmpty.text = "Falha ao carregar: ${e.message ?: e.javaClass.simpleName}"
                tvEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun onCat(i: Int) {
        catAdapter.setSelected(i)
        showCategory(i)
    }

    private fun showCategory(i: Int) {
        val c = Store.cache[Kind.LIVE] ?: return
        val cat = cats.getOrNull(i) ?: return
        channels = if (cat.id == "*") c.items else c.byCat[cat.id].orEmpty()
        channelAdapter.set(channels)
        rvChannels.scrollToPosition(0)
        tvEmpty.text = "Nenhum canal nesta categoria."
        tvEmpty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        selected = -1
        if (channels.isNotEmpty()) schedulePreview(0) else refreshNowNext()
    }

    /** Espera meio segundo parado no canal antes de carregar a prévia (evita carregar a cada tecla). */
    private fun schedulePreview(p: Int) {
        selected = p
        handler.removeCallbacks(previewRunnable)
        handler.postDelayed(previewRunnable, 500)
    }

    private fun previewSelected() {
        try {
            previewNow()
        } catch (e: Exception) {
            tvPreviewHint.text = "Erro na prévia"
            tvPreviewHint.visibility = View.VISIBLE
        }
    }

    private fun previewNow() {
        val item = channels.getOrNull(selected) ?: return
        val url = item.url ?: return
        val p = player ?: createPlayer().also { player = it }
        tvPreviewHint.text = "Carregando..."
        tvPreviewHint.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        loadEpg(item)
        refreshNowNext()
    }

    private fun createPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.volume = 0f // prévia sem som
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) tvPreviewHint.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                tvPreviewHint.text = "Sem sinal"
                tvPreviewHint.visibility = View.VISIBLE
            }
        })
        preview.player = p
        return p
    }

    private fun stopPreview() {
        handler.removeCallbacks(previewRunnable)
        player?.stop()
    }

    private fun loadEpg(item: Item) {
        val cached = epgCache[item.id]
        if (cached != null && System.currentTimeMillis() - cached.first < 5 * 60_000) return
        lifecycleScope.launch {
            val progs = try {
                Store.source?.epg(item) ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            epgCache[item.id] = System.currentTimeMillis() to progs
            channelAdapter.setNow(item.id, nowOf(progs, nowSec())?.title ?: "Sem programação")
            if (channels.getOrNull(selected)?.id == item.id) refreshNowNext()
        }
    }

    private fun refreshNowNext() {
        val item = channels.getOrNull(selected)
        val progs: List<Prog> = item?.let { epgCache[it.id]?.second }.orEmpty()
        val t = nowSec()
        val now = nowOf(progs, t)
        val next = progs.firstOrNull { it.start >= (now?.stop ?: t) }

        tvNowTitle.text = now?.title ?: (item?.name ?: "Selecione um canal")
        tvNowTime.text = now?.let { "${hm(it.start)} – ${hm(it.stop)}" } ?: ""
        tvNowDesc.text = when {
            now != null && now.desc.isNotBlank() -> now.desc
            item != null && progs.isEmpty() -> "Este canal não tem guia de programação disponível."
            else -> ""
        }
        tvNextTitle.text = next?.let { "A seguir: ${it.title}" } ?: ""
        tvNextTime.text = next?.let { "${hm(it.start)} – ${hm(it.stop)}" } ?: ""
    }

    private fun openFull(p: Int) {
        if (channels.isEmpty()) return
        Store.playList = channels
        Store.playIndex = p
        Store.zap = true
        Store.autoNext = false
        stopPreview()
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun nowOf(progs: List<Prog>, t: Long): Prog? = progs.firstOrNull { t >= it.start && t < it.stop }

    private fun hm(sec: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sec * 1000))

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        if (selected >= 0) schedulePreview(selected)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        preview.player = null
        player?.release()
        player = null
    }
}

class ChannelAdapter(
    private val onFocus: (Int) -> Unit,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var data: List<Item> = emptyList()
    private val nowTitles = HashMap<String, String>()

    fun set(d: List<Item>) {
        data = d
        notifyDataSetChanged()
    }

    fun setNow(id: String, title: String) {
        nowTitles[id] = title
        val i = data.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val item = data[i]
        h.name.text = item.name
        h.now.text = nowTitles[item.id] ?: "Toque para assistir"
        val logo = item.logo
        if (logo.isNullOrBlank()) {
            h.logo.dispose()
            h.logo.setImageResource(R.drawable.ph)
        } else {
            h.logo.load(logo) {
                size(160, 160)
                placeholder(R.drawable.ph)
                error(R.drawable.ph)
            }
        }
        h.itemView.setOnFocusChangeListener { _, focused ->
            val p = h.bindingAdapterPosition
            if (focused && p >= 0) onFocus(p)
        }
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onClick(p)
        }
    }
}
