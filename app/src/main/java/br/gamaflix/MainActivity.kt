package br.gamaflix

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import coil.load
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var rvCats: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: View
    private lateinit var tabs: List<Pair<TextView, Kind>>
    private lateinit var grid: GridLayoutManager
    private lateinit var itemAdapter: ItemAdapter
    private val catAdapter = CatAdapter { index, byClick -> rvCats.post { onCat(index, byClick) } }

    private var kind = Kind.LIVE
    private var catalog: Catalog? = null
    private var cats: List<Cat> = emptyList()
    private var catIndex = 0
    private var query = ""
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.source == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        rvCats = findViewById(R.id.rvCats)
        rvItems = findViewById(R.id.rvItems)
        tvEmpty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progress)

        grid = GridLayoutManager(this, 4)
        itemAdapter = ItemAdapter { list, pos -> onItem(list, pos) }
        rvItems.layoutManager = grid
        rvItems.adapter = itemAdapter
        rvItems.setHasFixedSize(true)
        rvItems.itemAnimator = null
        rvItems.setItemViewCacheSize(8)

        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

        tabs = listOf(
            findViewById<TextView>(R.id.tabLive) to Kind.LIVE,
            findViewById<TextView>(R.id.tabMovies) to Kind.MOVIE,
            findViewById<TextView>(R.id.tabSeries) to Kind.SERIES
        )
        tabs.forEach { (v, k) -> v.setOnClickListener { select(k) } }
        // VOD e Início sempre abrem a tela certa (VOD tem layout próprio).
        findViewById<TextView>(R.id.tabLive).setOnClickListener { switchTo(LiveActivity::class.java) }
        findViewById<TextView>(R.id.tabHome).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSearch).setOnClickListener { askSearch() }
        findViewById<TextView>(R.id.btnLogout).setOnClickListener { logout() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (query.isNotEmpty()) {
                    query = ""
                    refreshItems()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        select(intent.getStringExtra("kind")?.let { Kind.valueOf(it) } ?: Kind.LIVE)
    }

    private fun select(k: Kind) {
        kind = k
        query = ""
        tabs.forEach { it.first.isSelected = it.second == k }
        grid.spanCount = if (k == Kind.LIVE) 4 else 5
        itemAdapter.kind = k

        job?.cancel()
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        catalog = null
        cats = emptyList()
        catAdapter.setData(cats, 0)
        itemAdapter.set(emptyList())

        job = lifecycleScope.launch {
            try {
                val c = Store.cache[k] ?: Store.source!!.load(k).also { Store.cache[k] = it }
                catalog = c
                cats = listOf(Cat("*", "Todos")) + c.cats.filter { c.byCat.containsKey(it.id) }
                catIndex = if (cats.size > 1) 1 else 0
                catAdapter.setData(cats, catIndex)
                refreshItems()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showEmpty("Falha ao carregar: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (kind == k) progress.visibility = View.GONE
            }
        }
    }

    private fun onCat(index: Int, byClick: Boolean) {
        if (index == catIndex && !byClick) return
        if (!byClick && query.isNotEmpty()) return
        query = ""
        catIndex = index
        catAdapter.setSelected(index)
        refreshItems()
    }

    private fun refreshItems() {
        val c = catalog ?: return
        if (cats.isEmpty()) return
        val base = when {
            query.isNotEmpty() -> c.items
            cats[catIndex].id == "*" -> c.items
            else -> c.byCat[cats[catIndex].id].orEmpty()
        }
        val list = if (query.isEmpty()) base else base.filter { it.name.contains(query, ignoreCase = true) }
        itemAdapter.set(list)
        rvItems.scrollToPosition(0)
        if (list.isEmpty()) {
            showEmpty(if (query.isEmpty()) "Nada por aqui." else "Nada encontrado para \"$query\".")
        } else {
            tvEmpty.visibility = View.GONE
        }
    }

    private fun showEmpty(msg: String) {
        tvEmpty.text = msg
        tvEmpty.visibility = View.VISIBLE
    }

    /** Abre outra seção e fecha esta, para a pilha ficar sempre: Início, seção atual. */
    private fun switchTo(cls: Class<*>) {
        startActivity(Intent(this, cls))
        finish()
    }

    private fun onItem(list: List<Item>, pos: Int) {
        Pick.item = list[pos]
        startActivity(Intent(this, DetailActivity::class.java))
    }

    private fun showEpisodes(item: Item) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val eps = Store.source!!.episodes(item)
                if (eps.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Sem episódios disponíveis", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val labels = eps.map { it.title }.toTypedArray()
                AlertDialog.Builder(this@MainActivity, R.style.NovaDialog)
                    .setTitle(item.name)
                    .setItems(labels) { _, which ->
                        Store.playList = eps.map { Item(it.url, it.title, item.logo, "", it.url, Kind.SERIES) }
                        Store.playIndex = which
                        Store.zap = false
                        Store.autoNext = true
                        startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
                    }
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Falha: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun askSearch() {
        val et = EditText(this)
        et.setSingleLine()
        et.hint = "Digite o nome"
        et.setText(query)
        AlertDialog.Builder(this, R.style.NovaDialog)
            .setTitle("Buscar em ${tabs.first { it.second == kind }.first.text}")
            .setView(et)
            .setPositiveButton("Buscar") { _, _ ->
                query = et.text.toString().trim()
                refreshItems()
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
