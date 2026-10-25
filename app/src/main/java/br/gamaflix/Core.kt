package br.gamaflix

import android.content.Context
import android.content.SharedPreferences

enum class Kind { LIVE, MOVIE, SERIES }

data class Cat(val id: String, val name: String)

data class Item(
    val id: String,
    val name: String,
    val logo: String?,
    val cat: String,
    val url: String?,
    val kind: Kind,
    val seriesId: String? = null
)

data class Ep(val title: String, val url: String)

class Catalog(val cats: List<Cat>, val items: List<Item>) {
    val byCat: Map<String, List<Item>> by lazy { items.groupBy { it.cat } }
}

interface Source {
    suspend fun load(kind: Kind): Catalog
    suspend fun episodes(item: Item): List<Ep>
    suspend fun epg(item: Item): List<Prog> = emptyList()
    suspend fun info(item: Item): MediaInfo = MediaInfo()
}

/** Estado em memória compartilhado entre as telas. */
object Store {
    var source: Source? = null
    val cache = HashMap<Kind, Catalog>()
    var playList: List<Item> = emptyList()
    var playIndex = 0
    var zap = false
    var autoNext = false

    fun reset() {
        source = null
        cache.clear()
        playList = emptyList()
    }
}

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun save(c: Context, mode: String, server: String, user: String, pass: String) {
        sp(c).edit()
            .putString("mode", mode)
            .putString("server", server)
            .putString("user", user)
            .putString("pass", pass)
            .apply()
    }

    fun clear(c: Context) {
        sp(c).edit().clear().apply()
    }

    fun buildSource(c: Context): Source? {
        val s = sp(c)
        val server = s.getString("server", "").orEmpty()
        if (server.isBlank()) return null
        val mode = s.getString("mode", "xtream")
        return if (mode == "m3u") M3uSource(server)
        else XtreamSource(server, s.getString("user", "").orEmpty(), s.getString("pass", "").orEmpty())
    }
}

/** Um item do histórico de assistidos (filmes e séries). */
data class Hist(
    val id: String,
    val kind: Kind,
    val name: String,
    val logo: String?,
    val url: String,
    val pos: Long,
    val at: Long
)

object History {
    private const val KEY = "history"
    private const val MAX = 60

    private fun sp(c: android.content.Context) =
        c.getSharedPreferences("gamaflix_history", android.content.Context.MODE_PRIVATE)

    fun all(c: android.content.Context): List<Hist> = try {
        val arr = org.json.JSONArray(sp(c).getString(KEY, "[]") ?: "[]")
        val out = ArrayList<Hist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = try { Kind.valueOf(o.optString("kind")) } catch (e: Exception) { Kind.MOVIE }
            out.add(
                Hist(
                    o.optString("id"), kind, o.optString("name"),
                    o.optString("logo").ifBlank { null }, o.optString("url"),
                    o.optLong("pos"), o.optLong("at")
                )
            )
        }
        out.sortedByDescending { it.at }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(c: android.content.Context, h: Hist) {
        val list = all(c).filter { it.url != h.url }.toMutableList()
        list.add(0, h)
        write(c, list.take(MAX))
    }

    fun clear(c: android.content.Context) {
        sp(c).edit().remove(KEY).apply()
    }

    private fun write(c: android.content.Context, list: List<Hist>) {
        val arr = org.json.JSONArray()
        list.forEach { h ->
            arr.put(
                org.json.JSONObject()
                    .put("id", h.id).put("kind", h.kind.name).put("name", h.name)
                    .put("logo", h.logo ?: "").put("url", h.url)
                    .put("pos", h.pos).put("at", h.at)
            )
        }
        sp(c).edit().putString(KEY, arr.toString()).apply()
    }
}

/** Posição a retomar no próximo player aberto. */
object Resume {
    var pos = 0L
}

/** Um programa da guia (EPG) de um canal. Horários em segundos (epoch). */
data class Prog(val title: String, val desc: String, val start: Long, val stop: Long)

/** Informações de um filme ou série (sinopse, elenco etc.). Campos vazios quando o servidor não informa. */
data class MediaInfo(
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val year: String = "",
    val rating: String = "",
    val duration: String = "",
    val poster: String? = null,
    val backdrop: String? = null
)

/** Item escolhido na lista, levado para a tela de detalhes. */
object Pick {
    var item: Item? = null
}
