package br.gamaflix

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Busca em canais, filmes e séries. Os resultados aparecem enquanto a pessoa digita. */
class SearchActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var tvHint: TextView
    private lateinit var rvResults: RecyclerView
    private val rowAdapter = RowAdapter { }
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val runSearch = Runnable { search(etSearch.text.toString()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.source == null) Store.source = Prefs.buildSource(this)
        if (Store.source == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_search)
        etSearch = findViewById(R.id.etSearch)
        tvHint = findViewById(R.id.tvSearchHint)
        rvResults = findViewById(R.id.rvResults)
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = rowAdapter
        rvResults.itemAnimator = null

        Ui.bindTopBar(this, R.id.navSearch)

        // Resultado aparece a cada letra (com um instante de espera); Enter busca na hora.
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                handler.removeCallbacks(runSearch)
                handler.postDelayed(runSearch, 250)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                handler.removeCallbacks(runSearch)
                search(etSearch.text.toString())
                true
            } else false
        }
        etSearch.requestFocus()
    }

    private fun search(raw: String) {
        val q = raw.trim()
        job?.cancel()
        if (q.isEmpty()) {
            rowAdapter.set(emptyList())
            tvHint.text = "Comece a digitar para ver os resultados."
            tvHint.visibility = View.VISIBLE
            return
        }
        val source = Store.source ?: return
        job = lifecycleScope.launch {
            try {
                val live = Store.cache[Kind.LIVE] ?: source.load(Kind.LIVE).also { Store.cache[Kind.LIVE] = it }
                val movies = Store.cache[Kind.MOVIE] ?: source.load(Kind.MOVIE).also { Store.cache[Kind.MOVIE] = it }
                val series = Store.cache[Kind.SERIES] ?: source.load(Kind.SERIES).also { Store.cache[Kind.SERIES] = it }

                val ch = live.items.filter { it.name.contains(q, ignoreCase = true) }.take(40)
                val mv = movies.items.filter { it.name.contains(q, ignoreCase = true) }.take(40)
                val sr = series.items.filter { it.name.contains(q, ignoreCase = true) }.take(40)

                val rows = ArrayList<RowData>()
                if (ch.isNotEmpty()) rows.add(RowData("Canais", ch.mapIndexed { i, it ->
                    RowCard(it.name, it.logo, it, null, null) { playChannels(ch, i) }
                }, wide = true, onTitle = null))
                if (mv.isNotEmpty()) rows.add(RowData("Filmes", mv.map { it ->
                    RowCard(it.name, it.logo, it, null, null) { openDetail(it) }
                }, wide = false, onTitle = null))
                if (sr.isNotEmpty()) rows.add(RowData("Séries", sr.map { it ->
                    RowCard(it.name, it.logo, it, null, null) { openDetail(it) }
                }, wide = false, onTitle = null))

                rowAdapter.set(rows)
                tvHint.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                tvHint.text = "Nenhum resultado para \"$q\"."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvHint.text = "Falha na busca: ${e.message ?: e.javaClass.simpleName}"
                tvHint.visibility = View.VISIBLE
            }
        }
    }

    private fun openDetail(item: Item) {
        Pick.item = item
        startActivity(Intent(this, DetailActivity::class.java))
    }

    private fun playChannels(list: List<Item>, index: Int) {
        Store.playList = list
        Store.playIndex = index
        Store.zap = true
        Store.autoNext = false
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
