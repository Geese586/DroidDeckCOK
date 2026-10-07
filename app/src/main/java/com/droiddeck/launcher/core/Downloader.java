package com.droiddeck.launcher.core;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The fetches this app makes: the runtime catalog (a few hundred bytes of JSON) and the runtime
 * tarball itself (~790 MB), plus every other GitHub-hosted thing it installs. Plain
 * HttpURLConnection - nothing here needs a client library, and one fewer dependency is one fewer
 * thing that can fail a release build.
 *
 * <p>Each URL is resolved through {@link DownloadSource} first, so a network that cannot reach
 * GitHub gets the accelerators ahead of it. The direct URL is always the last candidate, so an
 * accelerator outage falls back to GitHub rather than failing the install.
 */
public final class Downloader {
    private static final String TAG = "Downloader";
    /** A stalled transfer is worth waiting out; a host that never answers is not. */
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private Downloader() {}

    public static String downloadString(String url) {
        for (String candidate : DownloadSource.candidates(url)) {
            String body = downloadStringOnce(candidate);
            if (body != null) return body;
        }
        return null;
    }

    private static String downloadStringOnce(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            if (connection.getResponseCode() / 100 != 2) {
                Log.w(TAG, url + " -> HTTP " + connection.getResponseCode());
                return null;
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                FileUtils.copy(in, out);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "GET " + url, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Downloads to {@code destination}, reporting a 0..1 fraction (or -1 when the server sends no
     * length). {@code resume} continues a partial file with a Range request, which is what makes a
     * 790 MB download survive a dropped connection instead of starting over.
     *
     * <p>Every candidate from {@link DownloadSource} is tried in turn. What one that fails part-way
     * leaves behind is picked up by the next: where its server honours the Range request the
     * download carries on from where it stopped, and where it ignores it - which the accelerators
     * do - the 200 below is caught and the file is started again rather than appended to with the
     * wrong bytes.
     */
    public static boolean downloadFile(String url, File destination, boolean resume,
                                       java.util.function.Consumer<Float> progress) {
        List<String> candidates = DownloadSource.candidates(url);
        for (int i = 0; i < candidates.size(); i++) {
            if (downloadFileOnce(candidates.get(i), destination, resume, progress)) return true;
        }
        return false;
    }

    private static boolean downloadFileOnce(String url, File destination, boolean resume,
                                            java.util.function.Consumer<Float> progress) {
        HttpURLConnection connection = null;
        long have = resume && destination.isFile() ? destination.length() : 0;
        try {
            connection = open(url);
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
            int code = connection.getResponseCode();
            boolean appending = code == HttpURLConnection.HTTP_PARTIAL;
            if (code / 100 != 2) {
                // The server ignored or refused the range: start the file again.
                if (have > 0) {
                    connection.disconnect();
                    //noinspection ResultOfMethodCallIgnored
                    destination.delete();
                    return downloadFileOnce(url, destination, false, progress);
                }
                Log.w(TAG, url + " -> HTTP " + code);
                return false;
            }
            if (!appending) have = 0;
            long length = connection.getContentLengthLong();
            long total = length < 0 ? -1 : length + have;
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            long written = have;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 1 << 16);
                 OutputStream out = new FileOutputStream(destination, appending)) {
                byte[] buffer = new byte[1 << 16];
                long lastReport = 0;
                for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                    out.write(buffer, 0, read);
                    written += read;
                    // A callback per 64 KB would be tens of thousands of UI hops; once per MB is
                    // enough to move a progress bar smoothly.
                    if (progress != null && written - lastReport > (1 << 20)) {
                        lastReport = written;
                        progress.accept(total > 0 ? written / (float) total : -1f);
                    }
                }
            }
            if (progress != null) progress.accept(1f);
            return total <= 0 || written >= total;
        } catch (Exception e) {
            Log.w(TAG, "download " + url, e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck-Android");
        return connection;
    }
}
