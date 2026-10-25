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
import kotlinx.coroutines.launch

class RowCard(val title: String, val logo: String?, val onClick: () -> Unit)

class RowData(
    val title: String,
    val cards: List<RowCard>,
    val wide: Boolean,
    val onTitle: (() -> Unit)?
)

class HomeActivity : AppCompatActivity() {

    private lateinit var rvRows: RecyclerView
    private lateinit var progress: View
    private lateinit var heroPanel: View
    private lateinit var ivHero: ImageView
    private lateinit var tvHeroTitle: TextView
    private lateinit var btnHero: TextView
    private val rowAdapter = RowAdapter()
    private var job: Job? = null

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
        heroPanel = findViewById(R.id.heroPanel)
        ivHero = findViewById(R.id.ivHero)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        btnHero = findViewById(R.id.btnHero)
        rvRows.layoutManager = LinearLayoutManager(this)
        rvRows.adapter = rowAdapter
        rvRows.itemAnimator = null

        findViewById<TextView>(R.id.navHome).apply {
            isSelected = true
            setOnClickListener { rvRows.scrollToPosition(0) }
        }
        findViewById<TextView>(R.id.navLive).setOnClickListener { openSection(Kind.LIVE) }
        findViewById<TextView>(R.id.navMovies).setOnClickListener { openSection(Kind.MOVIE) }
        findViewById<TextView>(R.id.navSeries).setOnClickListener { openSection(Kind.SERIES) }
        findViewById<TextView>(R.id.navSettings).setOnClickListener { openSettings() }
    }

    override fun onResume() {
        super.onResume()
        loadRows()
    }

    private fun openSection(k: Kind) {
        if (k == Kind.LIVE) {
            startActivity(Intent(this, LiveActivity::class.java))
        } else {
            startActivity(Intent(this, MainActivity::class.java).putExtra("kind", k.name))
        }
    }

    /** Destaque no topo: um filme da lista, com o pôster como fundo. */
    private fun bindHero(item: Item?) {
        if (item == null) {
            heroPanel.visibility = View.GONE
            return
        }
        heroPanel.visibility = View.VISIBLE
        tvHeroTitle.text = item.name
        item.logo?.takeIf { it.isNotBlank() }?.let { ivHero.load(it) }
        btnHero.setOnClickListener { openDetail(item) }
    }

    private fun openDetail(item: Item) {
        Pick.item = item
        startActivity(Intent(this, DetailActivity::class.java))
    }

    private fun loadRows() {
        val source = Store.source ?: return
        job?.cancel()
        progress.visibility = View.VISIBLE
        job = lifecycleScope.launch {
            val rows = ArrayList<RowData>()
            try {
                val hist = History.all(this@HomeActivity)
                if (hist.isNotEmpty()) {
                    rows.add(
                        RowData(
                            "Assistidos recentemente",
                            hist.take(20).map { h -> RowCard(h.name, h.logo) { resume(h) } },
                            wide = true,
                            onTitle = null
                        )
                    )
                }
                for (k in listOf(Kind.MOVIE, Kind.SERIES, Kind.LIVE)) {
                    val cat = Store.cache[k] ?: source.load(k).also { Store.cache[k] = it }
                    val title = when (k) {
                        Kind.MOVIE -> "Filmes"
                        Kind.SERIES -> "Séries"
                        Kind.LIVE -> "VOD"
                    }
                    rows.add(
                        RowData(
                            title,
                            cat.items.take(25).map { item -> RowCard(item.name, item.logo) { if (k == Kind.LIVE) openSection(k) else openDetail(item) } },
                            wide = k == Kind.LIVE,
                            onTitle = { openSection(k) }
                        )
                    )
                }
            bindHero(Store.cache[Kind.MOVIE]?.items?.firstOrNull { !it.logo.isNullOrBlank() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Falha ao carregar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = View.GONE
            }
            rowAdapter.set(rows)
        }
    }

    /** Retoma um assistido a partir da posição salva. */
    private fun resume(h: Hist) {
        Store.playList = listOf(Item(h.id, h.name, h.logo, "", h.url, h.kind))
        Store.playIndex = 0
        Store.zap = false
        Store.autoNext = false
        Resume.pos = h.pos
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Se o app fechou sozinho na última vez, mostra o erro gravado para copiar e enviar. */
    private fun showCrashIfAny() {
        val f = java.io.File(filesDir, App.CRASH_FILE)
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

    private fun openSettings() {
        val options = arrayOf("Apagar assistidos recentemente", "Sair da conta")
        AlertDialog.Builder(this, R.style.NovaDialog)
            .setTitle("Configurações")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmClearHistory()
                    1 -> logout()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this, R.style.NovaDialog)
            .setTitle("Apagar assistidos recentemente?")
            .setMessage("O histórico e as posições para retomar serão removidos.")
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
}

class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

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
        h.cards.adapter = CardAdapter(row.cards, row.wide)
    }
}

class CardAdapter(private val cards: List<RowCard>, private val wide: Boolean) :
    RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val title: TextView = v.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

    override fun getItemCount() = cards.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val card = cards[i]
        val d = h.itemView.resources.displayMetrics.density
        val lp = RecyclerView.LayoutParams(
            ((if (wide) 240 else 140) * d).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (12 * d).toInt()
        h.itemView.layoutParams = lp
        h.img.layoutParams.height = ((if (wide) 135 else 200) * d).toInt()
        h.img.scaleType = ImageView.ScaleType.CENTER_CROP
        h.img.setPadding(0, 0, 0, 0)
        h.title.text = card.title

        val logo = card.logo
        if (logo.isNullOrBlank()) {
            h.img.dispose()
            h.img.setImageResource(R.drawable.ph)
        } else {
            h.img.load(logo) {
                size(400, 400)
                placeholder(R.drawable.ph)
                error(R.drawable.ph)
            }
        }
        h.itemView.setOnClickListener { card.onClick() }
    }
}
