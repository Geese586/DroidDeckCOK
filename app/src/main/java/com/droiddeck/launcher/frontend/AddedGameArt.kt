package com.droiddeck.launcher.frontend

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.droiddeck.launcher.files.PeIconExtractor
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Artwork for an added game: what the user put in the game's folder first, else what Steam's
 * store has for the same title, fetched once into the app's cache. Four pieces, named the way
 * the client names them in its grid folder: the portrait capsule, the wide header, the hero
 * background and the logo; plus an icon pulled out of the game's own .exe. The session hands
 * the paths to the runtime's shortcuts writer, which copies them into the client's grid.
 */
object AddedGameArt {
    private const val TAG = "AddedGameArt"
    private const val STORE_SEARCH = "https://store.steampowered.com/api/storesearch/?l=english&cc=US&term="
    private const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/"
    private const val RETRY_AFTER_MS = 7L * 24 * 3600 * 1000

    class Art(val portrait: File?, val header: File?, val hero: File?, val logo: File?, val icon: File?) {
        val any get() = portrait != null || header != null || hero != null || logo != null
    }

    private val IMAGE = listOf("png", "jpg", "jpeg")
    private val PORTRAIT = listOf("cover", "poster", "grid", "boxart", "capsule", "folder", "library_600x900", "portrait")
    private val HEADER = listOf("header", "banner", "library_header", "wide")
    private val HERO = listOf("hero", "background", "library_hero")
    private val LOGO = listOf("logo")

    private fun cacheDir(context: Context, game: AddedGames.Game) = File(context.filesDir, "added-art/${game.appId}")

    /** An image in the folder (or its `art` subfolder) under one of the given names, any image extension. */
    private fun find(folder: File, names: List<String>): File? {
        val dirs = listOf(folder, File(folder, "art"), File(folder, ".art"))
        for (dir in dirs) for (name in names) for (ext in IMAGE) {
            val f = File(dir, "$name.$ext")
            if (f.isFile) return f
        }
        return null
    }

    /** What the folder itself offers; the file named after the folder counts as the portrait. */
    private fun local(game: AddedGames.Game): Art {
        val folder = game.folder
        return Art(
            portrait = find(folder, PORTRAIT + listOf(folder.name)),
            header = find(folder, HEADER),
            hero = find(folder, HERO),
            logo = find(folder, LOGO),
            icon = listOf("icon.png", "icon.ico").map { File(folder, it) }.firstOrNull { it.isFile },
        )
    }

    /** The folder's art, else the cached Steam art, without touching the network. */
    fun resolve(context: Context, game: AddedGames.Game): Art {
        val own = local(game)
        val cache = cacheDir(context, game)
        fun cached(name: String) = File(cache, name).takeIf { it.isFile }
        return Art(
            portrait = own.portrait ?: cached("p.jpg"),
            header = own.header ?: cached("header.jpg"),
            hero = own.hero ?: cached("hero.jpg"),
            logo = own.logo ?: cached("logo.png"),
            icon = own.icon ?: exeIcon(context, game),
        )
    }

    /** The first icon inside the game's .exe, kept as a PNG beside the cached art. */
    private fun exeIcon(context: Context, game: AddedGames.Game): File? {
        val cache = cacheDir(context, game)
        val png = File(cache, "icon.png")
        if (png.isFile) return png
        val none = File(cache, "icon.none")
        if (none.isFile) return null
        cache.mkdirs()
        val bitmap = runCatching { PeIconExtractor.extract(game.exe) }.getOrNull()
        if (bitmap == null) { none.writeText(""); return null }
        val tmp = File(cache, "icon.png.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return if (tmp.renameTo(png)) png else null
    }

    /**
     * Looks the games up on Steam's store and fetches the art that is missing, once per game
     * (a miss is retried after a week). Blocking: run it off the main thread. Returns true when
     * anything new arrived.
     */
    fun fetchMissing(context: Context, games: List<AddedGames.Game>): Boolean {
        if (!SessionPrefs.addedGamesArt(context)) return false
        var changed = false
        for (game in games) {
            if (local(game).portrait != null) continue
            val cache = cacheDir(context, game)
            val lookup = File(cache, "steam-appid")
            if (File(cache, "p.jpg").isFile) continue
            if (lookup.isFile && (lookup.readText().trim() != "none" || System.currentTimeMillis() - lookup.lastModified() < RETRY_AFTER_MS)) {
                val id = lookup.readText().trim()
                if (id == "none") continue
                if (download(id, cache)) changed = true
                continue
            }
            cache.mkdirs()
            val id = runCatching { search(game.name) }.onFailure { Log.w(TAG, "${game.name}: store search failed (${it.message})") }.getOrNull()
            if (id == null) { lookup.writeText("none"); continue }
            lookup.writeText(id)
            Log.i(TAG, "${game.name}: Steam app $id")
            if (download(id, cache)) changed = true
        }
        return changed
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** The Steam appid whose store name matches the folder name best, or null for no match. */
    private fun search(name: String): String? {
        val term = name.replace(Regex("(?i)\\b(complete|definitive|legendary|goty|game of the year|deluxe|ultimate|remastered)( edition)?\\b"), " ").trim().ifEmpty { name }
        val json = get(STORE_SEARCH + URLEncoder.encode(term, "UTF-8"))?.toString(Charsets.UTF_8) ?: return null
        val items = JSONObject(json).optJSONArray("items") ?: return null
        val want = normalize(name)
        val wantShort = normalize(term)
        var best: String? = null
        var bestScore = 0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val got = normalize(item.optString("name"))
            val score = when {
                got == want -> 4
                got == wantShort -> 3
                got.startsWith(wantShort) || wantShort.startsWith(got) -> 2
                i == 0 -> 1
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = item.optString("id") }
        }
        return best?.takeIf { it.isNotEmpty() && bestScore >= 1 }
    }

    private fun download(id: String, cache: File): Boolean {
        var got = false
        for ((remote, localName) in listOf(
            "library_600x900.jpg" to "p.jpg", "header.jpg" to "header.jpg", "library_hero.jpg" to "hero.jpg", "logo.png" to "logo.png",
        )) {
            val dst = File(cache, localName)
            if (dst.isFile) continue
            val bytes = get("$CDN$id/$remote") ?: continue
            val tmp = File(cache, "$localName.tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(dst)) got = true
        }
        return got
    }

    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "DroidDeck-launcher")
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "$url: ${e.message}"); null
        } finally {
            c.disconnect()
        }
    }
}
