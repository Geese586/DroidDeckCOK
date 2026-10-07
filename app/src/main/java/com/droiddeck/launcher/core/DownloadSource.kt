package com.droiddeck.launcher.core

import android.content.Context

/**
 * Where the app fetches the things that live on GitHub: the runtime catalog and the ~790 MB
 * rootfs beside it, the desktop catalog, Steam's seed, Proton builds, components and the app's
 * own updates. Every one of them is a github.com, raw.githubusercontent.com or api.github.com
 * URL, and from mainland China none of those answer - the fetch either times out or is reset.
 *
 * <p>The academic mirrors do not help: TUNA, USTC and Aliyun carry Arch Linux ARM's *package*
 * repository (the runtime's base), and TUNA/USTC run a github-release mirror, but that one syncs
 * only the repositories on its own list - this project's release assets are not among them. What
 * does work there is a GitHub accelerator: a public HTTPS endpoint that fetches the original URL
 * on its own side and streams the bytes back through it, which carries both raw.githubusercontent
 * and the release assets. Several are tried in order ahead of the direct URL, so one being down
 * or slow is not a failed install.
 *
 * <p>One honest limitation: the accelerators ignore a Range request and answer 200 with the whole
 * file, so a download through one cannot resume where it stopped - [Downloader] detects the 200
 * and starts the file again rather than corrupting it, and the sha256 still has to match before
 * anything is unpacked.
 */
object DownloadSource {
    /** Straight to GitHub, the default. */
    const val OFFICIAL = "official"

    /** Through a GitHub accelerator, for a network that cannot reach GitHub at all. */
    const val CHINA = "china"

    /** The accelerators, tried in the order listed; all take the original URL as a suffix. */
    private val ACCELERATORS = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
    )

    /**
     * The hosts an accelerator carries. Anything else - Flathub, a game's own CDN, a driver
     * vendor - is fetched directly whatever the source is set to.
     */
    private val GITHUB_HOSTS = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "codeload.github.com",
        "gist.githubusercontent.com",
    )

    private const val PREFS = "session"
    private const val KEY = "downloadSource"

    /** Set once from App.onCreate; without it every URL resolves to its direct form. */
    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** [OFFICIAL] or [CHINA]; a value outside those two reads as [OFFICIAL]. */
    @JvmStatic
    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, OFFICIAL)
            ?.takeIf { it == CHINA } ?: OFFICIAL

    @JvmStatic
    fun set(context: Context, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (source == CHINA) CHINA else OFFICIAL).apply()
    }

    /**
     * The URLs to try for [url], in order: every accelerator ahead of the original one, or just
     * the original when the source is [OFFICIAL], the URL is not one an accelerator carries, or
     * [init] has not run. The original comes last so an accelerator outage still installs.
     */
    @JvmStatic
    fun candidates(url: String): List<String> {
        if (url.isEmpty()) return listOf(url)
        val context = appContext ?: return listOf(url)
        if (current(context) != CHINA) return listOf(url)
        if (host(url)?.let { it in GITHUB_HOSTS } != true) return listOf(url)
        return ACCELERATORS.map { it + url } + url
    }

    private fun host(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
}
