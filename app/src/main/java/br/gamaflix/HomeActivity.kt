package br.gamaflix

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Um cartão de faixa. `item` alimenta o destaque; `rank` mostra #1, #2... */
class RowCard(
    val title: String,
    val logo: String?,
    val item: Item?,
    val sub: String?,
    val rank: Int?,
    val onClick: () -> Unit
)

class RowData(
    val title: String,
    val cards: List<RowCard>,
    val wide: Boolean,
    val onTitle: (() -> Unit)?
)

class HomeActivity : AppCompatActivity() {

    private lateinit var rvRows: RecyclerView
    private lateinit var progress: View
    private lateinit var heroBg: ImageView
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroMeta: TextView
    private lateinit var tvHeroPlot: TextView
    private lateinit var tvHeroNext: TextView
    private val rowAdapter = RowAdapter { card -> showHero(card) }
    private var job: Job? = null
    private var heroJob: Job? = null
    private var heroKey: String? = null
    private val heroInfo = HashMap<String, MediaInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.source == null) Store.source = Prefs.buildSource(this)
        if (Store.source == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_home)
        showCrashIfAny()
        rvRows = findViewById(R.id.rvRows)
        progress = findViewById(R.id.progress)
        heroBg = findViewById(R.id.heroBg)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroMeta = findViewById(R.id.tvHeroMeta)
        tvHeroPlot = findViewById(R.id.tvHeroPlot)
        tvHeroNext = findViewById(R.id.tvHeroNext)
        rvRows.layoutManager = LinearLayoutManager(this)
        rvRows.adapter = rowAdapter
        rvRows.itemAnimator = null

        Ui.bindTopBar(this, R.id.navHome)
        tvHeroTitle.text = "GamaFlix"
        tvHeroPlot.text = "Escolha um título para ver a sinopse e o EPG."
    }

    override fun onResume() {
        super.onResume()
        loadRows()
        if (Store.openSettings) {
            Store.openSettings = false
            openSettings()
        }
    }

    /** Faixas da Início: assistidos, canais, destaques numerados de filmes e séries. */
    private fun loadRows() {
        val source = Store.source ?: return
        job?.cancel()
        progress.visibility = View.VISIBLE
        job = lifecycleScope.launch {
            val rows = ArrayList<RowData>()
            try {
                val recent = History.all(this@HomeActivity)

                val vod = recent.filter { it.kind != Kind.LIVE }.take(20)
                if (vod.isNotEmpty()) {
                    rows.add(
                        RowData(
                            "Filmes e séries assistidos recentemente",
                            vod.map { h ->
                                val item = Item(h.id, h.name, h.logo, "", h.url, h.kind)
                                RowCard(h.name, h.logo, item, "Parou em ${fmt(h.pos)}", null) {
                                    if (h.kind == Kind.SERIES) playResume(item, h.pos) else openDetail(item)
                                }
                            },
                            wide = true,
                            onTitle = null
                        )
                    )
                }

                val live = recent.filter { it.kind == Kind.LIVE }.take(20)
                val channels: List<Item>
                val channelTitle: String
                if (live.isNotEmpty()) {
                    channels = live.map { h -> Item(h.id, h.name, h.logo, "", h.url, Kind.LIVE) }
                    channelTitle = "Canais assistidos recentemente"
                } else {
                    val all = Store.cache[Kind.LIVE] ?: source.load(Kind.LIVE).also { Store.cache[Kind.LIVE] = it }
                    val telecine = all.cats.filter { it.name.contains("telecine", ignoreCase = true) }.map { it.id }.toSet()
                    channels = all.items.filter { it.cat in telecine }.take(20)
                    channelTitle = "Canais Telecine"
                }
                if (channels.isNotEmpty()) {
                    rows.add(
                        RowData(
                            channelTitle,
                            channels.mapIndexed { i, ch -> RowCard(ch.name, ch.logo, ch, null, null) { playChannels(channels, i) } },
                            wide = true,
                            onTitle = { openSection(Kind.LIVE) }
                        )
                    )
                }

                val movies = Store.cache[Kind.MOVIE] ?: source.load(Kind.MOVIE).also { Store.cache[Kind.MOVIE] = it }
                rows.add(
                    RowData(
                        "Filmes em destaque hoje",
                        movies.items.take(10).mapIndexed { i, m ->
                            RowCard(m.name, m.logo, m, null, i + 1) { openDetail(m) }
                        },
                        wide = false,
                        onTitle = { openSection(Kind.MOVIE) }
                    )
                )

                val series = Store.cache[Kind.SERIES] ?: source.load(Kind.SERIES).also { Store.cache[Kind.SERIES] = it }
                rows.add(
                    RowData(
                        "Séries em destaque hoje",
                        series.items.take(10).mapIndexed { i, s ->
                            RowCard(s.name, s.logo, s, null, i + 1) { openDetail(s) }
                        },
                        wide = false,
                        onTitle = { openSection(Kind.SERIES) }
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Falha ao carregar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = View.GONE
            }
            rowAdapter.set(rows)
            if (heroKey == null) rows.firstOrNull()?.cards?.firstOrNull()?.let { showHero(it) }
        }
    }

    /** Atualiza o destaque com o item focado (com pequeno atraso, para não carregar a cada tecla). */
    private fun showHero(card: RowCard) {
        val item = card.item ?: return
        val key = "${item.kind}:${item.id}"
        if (heroKey == key) return
        heroKey = key
        tvHeroTitle.text = item.name
        tvHeroMeta.text = when (item.kind) {
            Kind.LIVE -> "Canal ao vivo"
            Kind.MOVIE -> "Filme"
            Kind.SERIES -> "Série"
        }
        tvHeroNext.text = ""
        tvHeroPlot.text = "Carregando..."
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(350)
            val hero = try {
                loadHero(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Hero("Não foi possível carregar a sinopse.", "", "", null)
            }
            if (heroKey != key) return@launch
            tvHeroPlot.text = hero.plot.ifBlank { "Sinopse não disponível." }
            if (hero.meta.isNotBlank()) tvHeroMeta.text = hero.meta
            tvHeroNext.text = hero.next
            hero.image?.takeIf { it.isNotBlank() }?.let { setHeroImage(it, key) }
        }
    }

    private class Hero(val plot: String, val meta: String, val next: String, val image: String?)

    /** Canais: programa atual e próximo do EPG. Filmes e séries: sinopse e ficha técnica. */
    private suspend fun loadHero(item: Item): Hero {
        if (item.kind == Kind.LIVE) {
            val progs = Store.source?.epg(item) ?: emptyList()
            val t = System.currentTimeMillis() / 1000
            val now = progs.firstOrNull { t >= it.start && t < it.stop }
            val next = progs.firstOrNull { it.start >= (now?.stop ?: t) }
            val plot = when {
                now == null -> "Sem programação no momento."
                now.desc.isNotBlank() -> now.desc
                else -> now.title
            }
            val meta = if (now == null) "Canal ao vivo"
            else "AGORA  ·  ${now.title}  ·  ${hm(now.start)} – ${hm(now.stop)}"
            val nextText = if (next == null) "" else "A SEGUIR  ·  ${next.title}  ·  ${hm(next.start)}"
            return Hero(plot, meta, nextText, item.logo)
        }
        val key = "${item.kind}:${item.id}"
        val info = heroInfo[key] ?: Store.source!!.info(item).also { heroInfo[key] = it }
        val meta = listOf(
            info.year,
            info.duration,
            if (info.rating.isNotBlank()) "★ ${info.rating}" else "",
            info.genre
        ).filter { it.isNotBlank() }.joinToString("  •  ")
        return Hero(info.plot, meta, "", info.backdrop ?: info.poster ?: item.logo)
    }

    private fun hm(sec: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))

    /** Troca a imagem de fundo com transição suave. */
    private fun setHeroImage(url: String, key: String) {
        heroBg.animate().alpha(0f).setDuration(150).withEndAction {
            if (heroKey == key) heroBg.load(url) { crossfade(false) }
            heroBg.animate().alpha(0.55f).setDuration(250).start()
        }.start()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun openSection(k: Kind) {
        if (k == Kind.LIVE) {
            startActivity(Intent(this, LiveActivity::class.java))
        } else {
            startActivity(Intent(this, MainActivity::class.java).putExtra("kind", k.name))
        }
    }

    private fun openDetail(item: Item) {
        Pick.item = item
        startActivity(Intent(this, DetailActivity::class.java))
    }

    /** Continua um episódio ou filme de onde parou (sem abrir a tela de detalhes). */
    private fun playResume(item: Item, pos: Long) {
        Store.playList = listOf(item)
        Store.playIndex = 0
        Store.zap = false
        Store.autoNext = false
        Resume.pos = pos
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Assiste um canal em tela cheia; OK/setas trocam entre os canais da lista. */
    private fun playChannels(list: List<Item>, index: Int) {
        Store.playList = list
        Store.playIndex = index
        Store.zap = true
        Store.autoNext = false
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    fun openSettings() {
        val options = arrayOf(
            "Atualizar lista",
            "Alterar dados da lista",
            "Apagar assistidos recentemente",
            "Sair da conta"
        )
        AlertDialog.Builder(this, R.style.NovaDialog)
            .setTitle("Configurações")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> refreshList()
                    1 -> editList()
                    2 -> confirmClearHistory()
                    3 -> logout()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Descarta o cache e baixa a lista de novo (quando o provedor atualizou o conteúdo). */
    private fun refreshList() {
        Store.cache.clear()
        heroInfo.clear()
        Toast.makeText(this, "Atualizando lista...", Toast.LENGTH_SHORT).show()
        loadRows()
    }

    /** Abre o login já preenchido para trocar servidor, usuário ou senha. */
    private fun editList() {
        startActivity(Intent(this, LoginActivity::class.java).putExtra("edit", true))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this, R.style.NovaDialog)
            .setTitle("Apagar assistidos recentemente?")
            .setMessage("Os filmes, séries e canais vistos recentemente serão removidos.")
            .setPositiveButton("Apagar") { _, _ ->
                History.clear(this)
                Toast.makeText(this, "Histórico apagado", Toast.LENGTH_SHORT).show()
                loadRows()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun logout() {
        Prefs.clear(this)
        Store.reset()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    /** Se o app fechou sozinho na última vez, mostra o erro gravado para copiar e enviar. */
    private fun showCrashIfAny() {
        val f = File(filesDir, App.CRASH_FILE)
        if (!f.exists()) return
        val text = try { f.readText() } catch (e: Exception) { return }
        AlertDialog.Builder(this, R.style.NovaDialog)
            .setTitle("O app fechou inesperadamente")
            .setMessage(text.take(3500))
            .setPositiveButton("Copiar erro") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("erro", text))
                f.delete()
                Toast.makeText(this, "Erro copiado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fechar") { _, _ -> f.delete() }
            .setCancelable(false)
            .show()
    }
}

class RowAdapter(private val onFocusCard: (RowCard) -> Unit) : RecyclerView.Adapter<RowAdapter.VH>() {

    private var rows: List<RowData> = emptyList()

    fun set(r: List<RowData>) {
        rows = r
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.rowTitle)
        val cards: RecyclerView = v.findViewById(R.id.rvCards)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val row = rows[i]
        val onTitle = row.onTitle
        h.title.text = if (onTitle != null) row.title + "  ›" else row.title
        h.title.isFocusable = onTitle != null
        h.title.setOnClickListener { onTitle?.invoke() }
        h.cards.layoutManager = LinearLayoutManager(h.itemView.context, LinearLayoutManager.HORIZONTAL, false)
        h.cards.adapter = CardAdapter(row.cards, row.wide, onFocusCard)
    }
}

class CardAdapter(
    private val cards: List<RowCard>,
    private val wide: Boolean,
    private val onFocusCard: (RowCard) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.sub)
        val rank: TextView = v.findViewById(R.id.rank)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

    override fun getItemCount() = cards.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val card = cards[i]
        val d = h.itemView.resources.displayMetrics.density
        val lp = RecyclerView.LayoutParams(
            ((if (wide) 200 else 120) * d).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (12 * d).toInt()
        h.itemView.layoutParams = lp
        h.img.layoutParams.height = ((if (wide) 112 else 170) * d).toInt()
        h.img.scaleType = ImageView.ScaleType.CENTER_CROP
        h.title.text = card.title
        h.sub.text = card.sub.orEmpty()
        h.sub.visibility = if (card.sub.isNullOrBlank()) View.GONE else View.VISIBLE
        h.rank.text = card.rank?.let { "#$it" }.orEmpty()
        h.rank.visibility = if (card.rank == null) View.GONE else View.VISIBLE

        val logo = card.logo
        if (logo.isNullOrBlank()) {
            h.img.dispose()
            h.img.setImageResource(R.drawable.ph)
        } else {
            h.img.load(logo) {
                size(480, 300)
                placeholder(R.drawable.ph)
                error(R.drawable.ph)
            }
        }
        h.itemView.setOnClickListener { card.onClick() }
        h.itemView.setOnFocusChangeListener { v, f ->
            Ui.focusScale(v, f)
            if (f) onFocusCard(card)
        }
    }
}
