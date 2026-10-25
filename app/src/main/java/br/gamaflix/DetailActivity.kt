package br.gamaflix

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    private lateinit var item: Item
    private lateinit var ivBackdrop: ImageView
    private lateinit var ivPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvDirector: TextView
    private lateinit var tvCast: TextView
    private lateinit var tvPlot: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnContinue: TextView
    private lateinit var btnExternal: TextView
    private lateinit var epColumn: View
    private lateinit var tvEpHeader: TextView
    private lateinit var rvEps: RecyclerView
    private lateinit var progress: View

    private var episodes: List<Ep> = emptyList()
    private var selectedEp = 0
    private var continuePos = 0L
    private val epAdapter = EpAdapter { p ->
        selectedEp = p
        playEpisode(p)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picked = Pick.item
        if (picked == null || Store.source == null) {
            finish()
            return
        }
        item = picked
        setContentView(R.layout.activity_detail)

        ivBackdrop = findViewById(R.id.ivBackdrop)
        ivPoster = findViewById(R.id.ivPoster)
        tvTitle = findViewById(R.id.tvTitle)
        tvMeta = findViewById(R.id.tvMeta)
        tvDirector = findViewById(R.id.tvDirector)
        tvCast = findViewById(R.id.tvCast)
        tvPlot = findViewById(R.id.tvPlot)
        btnPlay = findViewById(R.id.btnPlay)
        btnContinue = findViewById(R.id.btnContinue)
        btnExternal = findViewById(R.id.btnExternal)
        epColumn = findViewById(R.id.epColumn)
        tvEpHeader = findViewById(R.id.tvEpHeader)
        rvEps = findViewById(R.id.rvEps)
        progress = findViewById(R.id.progress)

        tvTitle.text = item.name
        item.logo?.takeIf { it.isNotBlank() }?.let { ivPoster.load(it) }
        rvEps.layoutManager = LinearLayoutManager(this)
        rvEps.adapter = epAdapter

        btnExternal.setOnClickListener { openExternal() }

        if (item.kind == Kind.SERIES) {
            epColumn.visibility = View.VISIBLE
            btnPlay.setOnClickListener {
                if (episodes.isNotEmpty()) playEpisode(0)
                else Toast.makeText(this, "Carregando episódios...", Toast.LENGTH_SHORT).show()
            }
            loadEpisodes()
        } else {
            btnPlay.setOnClickListener { playMovie(0L) }
            History.all(this).firstOrNull { it.url == item.url }?.takeIf { it.pos >= 5_000 }?.let { saved ->
                continuePos = saved.pos
                btnContinue.text = "↺  Continuar de ${fmt(saved.pos)}"
                btnContinue.visibility = View.VISIBLE
                btnContinue.setOnClickListener { playMovie(continuePos) }
            }
        }
        loadInfo()
    }

    private fun loadInfo() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val info = try {
                Store.source!!.info(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MediaInfo()
            }
            progress.visibility = View.GONE

            val backdrop = info.backdrop ?: info.poster ?: item.logo
            if (!backdrop.isNullOrBlank()) ivBackdrop.load(backdrop)
            val poster = info.poster ?: item.logo
            if (!poster.isNullOrBlank()) ivPoster.load(poster)

            val rating = if (info.rating.isNotBlank()) "★ ${info.rating}" else ""
            tvMeta.text = listOf(info.year, info.duration, rating, info.genre)
                .filter { it.isNotBlank() }
                .joinToString("  •  ")
            tvDirector.text = if (info.director.isNotBlank()) "Direção: ${info.director}" else ""
            tvCast.text = if (info.cast.isNotBlank()) "Elenco: ${info.cast}" else ""
            tvPlot.text = info.plot.ifBlank { "Sinopse não disponível para este título." }
        }
    }

    private fun loadEpisodes() {
        lifecycleScope.launch {
            try {
                episodes = Store.source!!.episodes(item)
                epAdapter.set(episodes)
                tvEpHeader.text = if (episodes.isEmpty()) "Sem episódios disponíveis" else "Episódios (${episodes.size})"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvEpHeader.text = "Falha ao carregar episódios"
            }
        }
    }

    private fun playMovie(startMs: Long) {
        Store.playList = listOf(item)
        Store.playIndex = 0
        Store.zap = false
        Store.autoNext = false
        Resume.pos = startMs
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Toca o episódio escolhido e segue para o próximo sozinho. Retoma se já foi assistido. */
    private fun playEpisode(index: Int) {
        val ep = episodes.getOrNull(index) ?: return
        val items = episodes.map { Item(it.url, it.title, item.logo, "", it.url, Kind.SERIES) }
        Store.playList = items
        Store.playIndex = index
        Store.zap = false
        Store.autoNext = true
        Resume.pos = History.all(this).firstOrNull { it.url == ep.url }?.pos ?: 0L
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Abre o filme (ou o episódio selecionado) num app de mídia instalado no aparelho. */
    private fun openExternal() {
        val url = if (item.kind == Kind.SERIES) episodes.getOrNull(selectedEp)?.url else item.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Aguarde os episódios carregarem", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
        try {
            startActivity(Intent.createChooser(intent, "Abrir com"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app de mídia encontrado", Toast.LENGTH_LONG).show()
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}

class EpAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<EpAdapter.VH>() {

    private var data: List<Ep> = emptyList()

    fun set(d: List<Ep>) {
        data = d
        notifyDataSetChanged()
    }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cat, parent, false) as TextView)

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        h.tv.text = data[i].title
        h.tv.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onClick(p)
        }
    }
}
