package br.gamaflix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.EnumMap

object Net {
    const val UA = "VLC/3.0.20 LibVLC/3.0.20"

    /** Abre a conexão seguindo redirecionamentos (inclusive http -> https). */
    fun open(start: String): HttpURLConnection {
        var url = start
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("Redirecionamento inválido")
                url = URL(URL(url), loc).toString()
                c.disconnect()
                return@repeat
            }
            if (code >= 400) {
                c.disconnect()
                error("Servidor respondeu HTTP $code")
            }
            return c
        }
        error("Muitos redirecionamentos")
    }

    /** Lê a resposta em fluxo (sem montar o texto inteiro na memória). */
    fun <T> stream(url: String, block: (java.io.InputStream) -> T): T {
        val c = open(url)
        try {
            return block(c.inputStream)
        } finally {
            c.disconnect()
        }
    }

    fun text(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Categorias com nome de canal não devem aparecer em Filmes nem em Séries. */
private val channelName = Regex("canal|canais|channel|ao vivo", RegexOption.IGNORE_CASE)

private fun JSONObject.str(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

class XtreamSource(server: String, private val user: String, private val pass: String) : Source {

    private val base: String = server.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }
    private val u = URLEncoder.encode(user, "UTF-8")
    private val p = URLEncoder.encode(pass, "UTF-8")

    private fun api(action: String, extra: String = "") =
        "$base/player_api.php?username=$u&password=$p&action=$action$extra"

    /**
     * Lê uma lista JSON em fluxo: cada objeto vira um mapa só com as chaves pedidas.
     * Evita carregar o catálogo inteiro como texto + JSON na memória (estourava o TV).
     */
    private fun array(url: String, keys: Set<String>, each: (Map<String, String>) -> Unit) {
        Net.stream(url) { input ->
            val reader = JsonReader(input.bufferedReader())
            reader.use {
                if (it.peek() == JsonToken.END_DOCUMENT) return@stream
                if (it.peek() != JsonToken.BEGIN_ARRAY)
                    error("O servidor não devolveu uma lista (confira servidor, usuário e senha)")
                it.beginArray()
                while (it.hasNext()) {
                    val map = HashMap<String, String>(keys.size)
                    it.beginObject()
                    while (it.hasNext()) {
                        val k = it.nextName()
                        val t = it.peek()
                        if (k in keys && t != JsonToken.BEGIN_OBJECT && t != JsonToken.BEGIN_ARRAY) {
                            map[k] = if (t == JsonToken.NULL) { it.nextNull(); "" } else it.nextString()
                        } else {
                            it.skipValue()
                        }
                    }
                    it.endObject()
                    each(map)
                }
                it.endArray()
            }
        }
    }

    override suspend fun load(kind: Kind): Catalog = withContext(Dispatchers.IO) {
        val (catAction, itemAction) = when (kind) {
            Kind.LIVE -> "get_live_categories" to "get_live_streams"
            Kind.MOVIE -> "get_vod_categories" to "get_vod_streams"
            Kind.SERIES -> "get_series_categories" to "get_series"
        }
        val cats = ArrayList<Cat>()
        array(api(catAction), setOf("category_id", "category_name")) { o ->
            cats.add(Cat(o["category_id"].orEmpty(), o["category_name"].orEmpty()))
        }
        val hidden = if (kind == Kind.LIVE) emptySet() else cats.filter { channelName.containsMatchIn(it.name) }.map { it.id }.toSet()
        cats.removeAll { it.id in hidden }
        val items = ArrayList<Item>()
        val wanted = setOf(
            "name", "category_id", "stream_id", "stream_icon", "container_extension",
            "added", "series_id", "cover", "last_modified"
        )
        array(api(itemAction), wanted) { o ->
            val name = o["name"].orEmpty().trim()
            val cat = o["category_id"].orEmpty()
            if (cat !in hidden) when (kind) {
                Kind.LIVE -> {
                    val id = o["stream_id"].orEmpty()
                    items.add(
                        Item(id, name, o["stream_icon"].orEmpty().ifBlank { null }, cat,
                            "$base/live/$u/$p/$id.m3u8", kind)
                    )
                }
                Kind.MOVIE -> {
                    val id = o["stream_id"].orEmpty()
                    val ext = o["container_extension"].orEmpty().ifBlank { "mp4" }
                    items.add(
                        Item(id, name, o["stream_icon"].orEmpty().ifBlank { null }, cat,
                            "$base/movie/$u/$p/$id.$ext", kind, null, o["added"]?.toLongOrNull() ?: 0L)
                    )
                }
                Kind.SERIES -> {
                    val id = o["series_id"].orEmpty()
                    items.add(
                        Item(id, name, o["cover"].orEmpty().ifBlank { null }, cat, null, kind, id,
                            o["last_modified"]?.toLongOrNull() ?: 0L)
                    )
                }
            }
        }
        Catalog(cats, items)
    }

    override suspend fun episodes(item: Item): List<Ep> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.text(api("get_series_info", "&series_id=${item.seriesId}")))
        val raw = root.opt("episodes")
        val seasons = ArrayList<Pair<String, JSONArray>>()
        when (raw) {
            is JSONObject -> raw.keys().forEach { k -> raw.optJSONArray(k)?.let { seasons.add(k to it) } }
            is JSONArray -> for (i in 0 until raw.length()) {
                raw.optJSONArray(i)?.let { seasons.add((i + 1).toString() to it) }
            }
        }
        class Row(val season: Int, val num: Int, val ep: Ep)
        val rows = ArrayList<Row>()
        for ((season, arr) in seasons) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("id")
                if (id.isBlank()) continue
                val ext = o.str("container_extension").ifBlank { "mp4" }
                val num = o.str("episode_num").toIntOrNull() ?: (i + 1)
                val title = o.str("title").trim()
                val label = "T$season E$num" + if (title.isNotBlank()) "  ·  $title" else ""
                rows.add(Row(season.toIntOrNull() ?: 0, num, Ep(label, "$base/series/$u/$p/$id.$ext")))
            }
        }
        rows.sortedWith(compareBy({ it.season }, { it.num })).map { it.ep }
    }
    override suspend fun epg(item: Item): List<Prog> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(Net.text(api("get_short_epg", "&stream_id=${item.id}&limit=30")))
            val arr = root.optJSONArray("epg_listings") ?: return@withContext emptyList()
            val out = ArrayList<Prog>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val start = o.optString("start_timestamp").toLongOrNull() ?: continue
                val stop = o.optString("stop_timestamp").toLongOrNull() ?: continue
                out.add(Prog(epgText(o.str("title")), epgText(o.str("description")), start, stop))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
    override suspend fun info(item: Item): MediaInfo = withContext(Dispatchers.IO) {
        // Sem try/catch: erro de rede aparece na tela de detalhes em vez de sumir.
        val url = if (item.kind == Kind.SERIES) {
            api("get_series_info", "&series_id=${item.seriesId}")
        } else {
            api("get_vod_info", "&vod_id=${item.id}")
        }
        val root = JSONObject(Net.text(url))
        val info = root.optJSONObject("info") ?: JSONObject()
        MediaInfo(
            plot = info.str("plot").ifBlank { info.str("description") },
            cast = info.str("cast").ifBlank { info.str("actors") },
            director = info.str("director"),
            genre = info.str("genre"),
            year = info.str("releasedate").ifBlank { info.str("releaseDate") }.take(4),
            rating = info.str("rating"),
            duration = info.str("duration").ifBlank { info.str("episode_run_time") },
            poster = info.str("movie_image").ifBlank { info.str("cover") }.ifBlank { null },
            backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.ifBlank { null }
        )
    }
}

class M3uSource(private val url: String) : Source {

    private var parsed: Map<Kind, Catalog>? = null
    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val movieGroup = Regex("filme|movie|vod|cinema")
    private val seriesGroup = Regex("s[eé]rie|series|novela|temporada")
    /** Marca de episódio no nome: "Nome S04E07". Tudo antes dela é o nome da série. */
    private val seasonEp = Regex("(?i)\\s*[-–|:]?\\s*\\bS(\\d{1,2})\\s*[ .]?E(\\d{1,3})\\b")
    private var episodeMap: Map<String, List<Ep>> = emptyMap()

    override suspend fun load(kind: Kind): Catalog = withContext(Dispatchers.IO) {
        val m = parsed ?: parse().also { parsed = it }
        m.getValue(kind)
    }

    override suspend fun episodes(item: Item): List<Ep> = episodeMap[item.seriesId ?: item.id].orEmpty()

    private fun classify(link: String, group: String): Kind {
        val l = link.lowercase()
        val g = group.lowercase()
        return when {
            "/live/" in l -> Kind.LIVE
            "/movie/" in l -> Kind.MOVIE
            "/series/" in l -> Kind.SERIES
            // HLS (.m3u8) é canal ao vivo, mesmo que o grupo tenha a palavra "filme".
            l.endsWith(".m3u8") -> Kind.LIVE
            channelName.containsMatchIn(g) -> Kind.LIVE
            l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".avi") -> Kind.MOVIE
            movieGroup.containsMatchIn(g) -> Kind.MOVIE
            seriesGroup.containsMatchIn(g) -> Kind.SERIES
            else -> Kind.LIVE
        }
    }

    private fun parse(): Map<Kind, Catalog> {
        val items = EnumMap<Kind, ArrayList<Item>>(Kind::class.java)
        val cats = EnumMap<Kind, LinkedHashSet<String>>(Kind::class.java)
        Kind.values().forEach { items[it] = ArrayList(); cats[it] = LinkedHashSet() }
        val seriesRaw = ArrayList<Item>()

        val c = Net.open(url)
        try {
            c.inputStream.bufferedReader().use { r ->
                var name = ""
                var logo: String? = null
                var group = "Sem categoria"
                var pending = false
                var n = 0
                r.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.startsWith("#EXTINF")) {
                        val map = HashMap<String, String>()
                        attr.findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
                        name = line.substringAfterLast(',').trim()
                        logo = map["tvg-logo"]?.ifBlank { null }
                        group = map["group-title"]?.ifBlank { null } ?: "Sem categoria"
                        pending = true
                    } else if (pending && line.isNotEmpty() && !line.startsWith("#")) {
                        val k = classify(line, group)
                        n++
                        val built = Item(n.toString(), name.ifBlank { "Canal $n" }, logo, group, line, k)
                        if (k == Kind.SERIES) seriesRaw.add(built) else items.getValue(k).add(built)
                        cats.getValue(k).add(group)
                        pending = false
                    }
                }
            }
        } finally {
            c.disconnect()
        }

        // Episódios ("Nome S04E07") viram uma única série, com a lista de capítulos.
        val eps = HashMap<String, MutableList<Triple<Int, Int, Ep>>>()
        val seriesItems = LinkedHashMap<String, Item>()
        for (e0 in seriesRaw) {
            val u = e0.url ?: continue
            val m = seasonEp.find(e0.name)
            if (m == null) {
                items.getValue(Kind.SERIES).add(e0)
                continue
            }
            val base = e0.name.substring(0, m.range.first).trim().ifBlank { e0.name }
            val key = base.lowercase()
            val id = "s:$key"
            seriesItems.getOrPut(key) { Item(id, base, e0.logo, e0.cat, null, Kind.SERIES, id) }
            eps.getOrPut(key) { mutableListOf() }
                .add(Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), Ep(e0.name, u)))
        }
        items.getValue(Kind.SERIES).addAll(seriesItems.values)
        episodeMap = eps.entries.associate { (key, list) ->
            "s:$key" to list.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
        }

        return Kind.values().associateWith { k ->
            Catalog(cats.getValue(k).map { Cat(it, it) }, items.getValue(k))
        }
    }
}


/** Textos do EPG do Xtream vêm em base64; se não for base64, devolve o texto puro. */
private fun epgText(s: String): String {
    if (s.isBlank() || s.length % 4 != 0 || !Regex("^[A-Za-z0-9+/=]+$").matches(s)) return s.trim()
    return try {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: Exception) {
        s.trim()
    }
}
