package br.gamaflix

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
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

/** Filmes e Séries: menu lateral de categorias (com "Últimos") e grade de capas. */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Categoria virtual: os itens adicionados mais recentemente. */
        const val RECENT = "__recent__"
    }

    private lateinit var rvCats: RecyclerView
    private lateinit var rvItems: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: View
    private lateinit var grid: GridLayoutManager
    private val itemAdapter = ItemAdapter(onClick = { list, pos -> onItem(list, pos) })
    private val catAdapter = CatAdapter { index, byClick -> rvCats.post { onCat(index, byClick) } }

    private var kind = Kind.MOVIE
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
        rvItems.layoutManager = grid
        rvItems.adapter = itemAdapter
        rvItems.setHasFixedSize(true)
        rvItems.itemAnimator = null
        rvItems.setItemViewCacheSize(8)
        itemAdapter.onLeftEdge = { Ui.focusRow(rvCats, catIndex) }
        rvItems.post { applyGrid() }

        rvCats.layoutManager = LinearLayoutManager(this)
        rvCats.adapter = catAdapter
        rvCats.itemAnimator = null

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

        val start = intent.getStringExtra("kind")?.let { Kind.valueOf(it) } ?: Kind.MOVIE
        Ui.bindTopBar(this, if (start == Kind.SERIES) R.id.navSeries else R.id.navMovies)
        select(start)
    }

    /** Colunas conforme a largura: no mínimo 4, para as capas ficarem pequenas. */
    private fun applyGrid() {
        val w = rvItems.width
        if (w <= 0) return
        val d = resources.displayMetrics.density
        val cols = maxOf(4, (w / (150 * d)).toInt())
        grid.spanCount = cols
        itemAdapter.spanCount = cols
        itemAdapter.setCell(w / cols)
    }

    private fun select(k: Kind) {
        kind = k
        query = ""
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
                val recentName = if (k == Kind.SERIES) "Últimas séries" else "Últimos filmes"
                cats = listOf(Cat("*", "Todos"), Cat(RECENT, recentName)) +
                    c.cats.filter { c.byCat.containsKey(it.id) }
                catIndex = 1 // abre em "Últimos"
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

    /** Itens mais recentes primeiro (pela data de adição do servidor). Sem data, usa o fim da lista. */
    private fun recentItems(c: Catalog): List<Item> {
        val dated = c.items.filter { it.added > 0 }.sortedByDescending { it.added }
        val src = if (dated.isNotEmpty()) dated else c.items.asReversed()
        return src.take(300)
    }

    private fun refreshItems() {
        val c = catalog ?: return
        if (cats.isEmpty()) return
        val id = cats[catIndex].id
        val base = when {
            query.isNotEmpty() -> c.items
            id == "*" -> c.items
            id == RECENT -> recentItems(c)
            else -> c.byCat[id].orEmpty()
        }
        val list = if (query.isEmpty()) base else base.filter { it.name.contains(query, ignoreCase = true) }
        itemAdapter.set(list)
        rvItems.scrollToPosition(0)
        rvItems.post { applyGrid() }
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

    private fun onItem(list: List<Item>, pos: Int) {
        Pick.item = list[pos]
        startActivity(Intent(this, DetailActivity::class.java))
    }

}
