package com.droiddeck.launcher.runtime;

import android.content.Context;
import android.util.Log;


import com.droiddeck.launcher.R;
import com.droiddeck.launcher.core.ArchivePaths;
import com.droiddeck.launcher.core.Downloader;
import com.droiddeck.launcher.core.Hashes;
import com.droiddeck.launcher.core.FileUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

public final class LinuxRuntimeInstaller {
    private static final String TAG = "LinuxRuntimeInstaller";

    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs.json";

    private static final String VERSION_FILE = ".version";

    public enum Step { DOWNLOADING, VERIFYING, OTHER }

    public interface ProgressListener {
        void onProgress(String stage, int percent);

        default void onProgress(Step step, String stage, int percent) {
            onProgress(stage, percent);
        }
    }

    public static final class Release {
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;

        public Release(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private static final class Job {
        final CopyOnWriteArrayList<ProgressListener> listeners = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String stage;
        volatile Step step = Step.OTHER;
        volatile int percent = -1;
        volatile boolean ok;
        final boolean removal;
        boolean maintenance;
        Job(boolean removal, String stage) { this.removal = removal; this.stage = stage; }
    }

    private static final Object JOB_LOCK = new Object();
    private static Job running;
    private static volatile String removalError;

    private LinuxRuntimeInstaller() {}

    public static String installedVersion(Context context) {
        File marker = new File(LinuxRuntime.rootDir(context), VERSION_FILE);
        if (!marker.isFile() || !LinuxRuntime.isInstalled(context)) return null;
        String v = FileUtils.readString(marker);
        return v == null ? null : v.trim();
    }

    public static Release fetchRelease() {
        String body = Downloader.downloadString(CATALOG_URL);
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            String version = json.optString("version", "");
            String url = json.optString("url", "");
            String sha256 = json.optString("sha256", "");
            if (version.isEmpty() || url.isEmpty() || sha256.isEmpty()) return null;
            return new Release(version, url, sha256, json.optLong("size", 0L));
        } catch (Exception e) {
            Log.w(TAG, "catalog: " + e);
            return null;
        }
    }

    private static final String USER_DATA = "root";

    public static boolean install(Context context, Release release, ProgressListener listener) {
        Job job;
        boolean owner;
        synchronized (JOB_LOCK) {
            if (running != null && (running.removal || running.maintenance)) return false;
            owner = running == null;
            if (owner) { running = new Job(false, context.getString(R.string.user_apps_starting)); removalError = null; }
            job = running;
            if (listener != null) job.listeners.add(listener);
        }
        if (!owner) return join(job, listener);
        try {
            job.ok = installOnce(context.getApplicationContext(), release, new ProgressListener() {
                @Override public void onProgress(String stage, int percent) {
                    onProgress(Step.OTHER, stage, percent);
                }

                @Override public void onProgress(Step step, String stage, int percent) {
                    job.step = step;
                    job.stage = stage;
                    job.percent = percent;
                    for (ProgressListener l : job.listeners) l.onProgress(step, stage, percent);
                }
            });
            return job.ok;
        } finally {
            synchronized (JOB_LOCK) {
                running = null;
            }
            job.done.countDown();
        }
    }

    /**
     * Installs the runtime from an archive the user already has, so a network that cannot reach
     * GitHub is never asked to fetch it. {@code catalogue} is the online catalog row where it could
     * be read: its sha256 is then enforced and its version names the result.
     *
     * <p>Without one - the case this path exists for - no checksum is checked, the archive's own
     * digest names the install, and {@link LinuxRuntime#isInstalled} still has to pass at the end,
     * so a truncated or wrong file leaves the previous runtime untouched rather than a half one that
     * would launch. {@code archive} belongs to the caller and is never deleted, unlike the
     * downloaded copy.
     *
     * <p>DroidDeckCOK: 0.3.2 upstream dropped this entry point and the first-time local install is
     * built on it, so it is kept here, running as the process's one operation like {@link #install}.
     */
    public static boolean installFromFile(Context context, File archive, Release catalogue,
                                         ProgressListener listener) {
        return runInstall(context, listener, (ctx, l) -> installFromFileOnce(ctx, archive, catalogue, l));
    }

    /** A runtime operation's work, run as the process's one operation by {@link #runInstall}. */
    private interface Work {
        boolean run(Context context, ProgressListener listener);
    }

    /**
     * Runs {@code work} as the process's single runtime operation, or joins the one already running:
     * the launcher and RuntimeInstallService both call in, and a launcher rebuilt mid-install must
     * not start a second operation over the same files.
     */
    private static boolean runInstall(Context context, ProgressListener listener, Work work) {
        Job job;
        boolean owner;
        synchronized (JOB_LOCK) {
            if (running != null && running.removal) return false;
            owner = running == null;
            if (owner) { running = new Job(false, context.getString(R.string.user_apps_starting)); removalError = null; }
            job = running;
            if (listener != null) job.listeners.add(listener);
        }
        if (!owner) return join(job, listener);
        try {
            job.ok = work.run(context.getApplicationContext(), new ProgressListener() {
                @Override public void onProgress(String stage, int percent) {
                    onProgress(Step.OTHER, stage, percent);
                }

                @Override public void onProgress(Step step, String stage, int percent) {
                    job.step = step;
                    job.stage = stage;
                    job.percent = percent;
                    for (ProgressListener l : job.listeners) l.onProgress(step, stage, percent);
                }
            });
            return job.ok;
        } finally {
            synchronized (JOB_LOCK) {
                running = null;
            }
            job.done.countDown();
        }
    }

    /** True while an install is running in this process, whoever started it. */
    public static boolean isInstalling() {
        synchronized (JOB_LOCK) {
            return running != null && !running.removal && !running.maintenance;
        }
    }

    public static boolean isBusy() {
        synchronized (JOB_LOCK) { return running != null; }
    }

    public static boolean isRemoving() {
        synchronized (JOB_LOCK) { return running != null && running.removal; }
    }

    public static boolean isMaintaining() {
        synchronized (JOB_LOCK) { return running != null && running.maintenance; }
    }

    public static Maintenance beginMaintenance(Context context, String stage) {
        if (com.droiddeck.launcher.session.SessionState.INSTANCE.getRunning()) return null;
        com.droiddeck.launcher.session.SessionPhase phase = com.droiddeck.launcher.session.SessionState.INSTANCE.getPhase();
        if (phase != com.droiddeck.launcher.session.SessionPhase.IDLE && phase != com.droiddeck.launcher.session.SessionPhase.FAILED) return null;
        synchronized (JOB_LOCK) {
            if (running != null) return null;
            removalError = null;
            running = new Job(false, stage);
            running.maintenance = true;
            return new Maintenance(running);
        }
    }

    public interface MaintenanceWork { String run(ProgressListener listener); }

    public static final class Maintenance {
        private final Job job;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        private Maintenance(Job job) { this.job = job; }
        public boolean run(ProgressListener listener, MaintenanceWork work) {
            if (!started.compareAndSet(false, true)) return join(job, listener);
            if (listener != null) job.listeners.add(listener);
            try {
                removalError = work.run((stage, percent) -> {
                    job.stage = stage;
                    job.percent = percent;
                    for (ProgressListener l : job.listeners) l.onProgress(stage, percent);
                });
                job.ok = removalError == null;
                return job.ok;
            } catch (Exception e) {
                removalError = e.getMessage();
                Log.e(TAG, "maintenance", e);
                return false;
            } finally {
                synchronized (JOB_LOCK) { running = null; }
                job.done.countDown();
                if (listener != null) job.listeners.remove(listener);
            }
        }
    }

    public static String removalError() { return removalError; }

    public static boolean hasRemovalPending(Context context) {
        return removalDirectory(LinuxRuntime.rootDir(context)).exists();
    }

    private static File removalDirectory(File root) {
        return new File(root.getParentFile(), root.getName() + ".removing");
    }

    public static Boolean attach(ProgressListener listener) {
        Job job;
        synchronized (JOB_LOCK) {
            job = running;
            if (job == null) return null;
            if (listener != null) job.listeners.add(listener);
        }
        return join(job, listener);
    }

    private static boolean join(Job job, ProgressListener listener) {
        try {
            if (listener != null) listener.onProgress(job.step, job.stage, job.percent);
            job.done.await();
            return job.ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (listener != null) job.listeners.remove(listener);
        }
    }

    /**
     * The download is named after the release, so a partial file is only ever resumed for the same
     * version and checksum, never for a different runtime that happens to share a fixed name.
     */
    static String archiveName(Release release) {
        String version = release.version.replaceAll("[^A-Za-z0-9._-]", "_");
        String sum = release.sha256.replaceAll("[^A-Za-z0-9]", "");
        if (sum.length() > 12) sum = sum.substring(0, 12);
        return "linuxfs-" + version + "-" + sum + ".tar.zst";
    }

    /** Removes archives left by other releases and by builds that used the fixed name. */
    private static void deleteOtherArchives(File dir, String keep) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("linuxfs") && name.endsWith(".tar.zst") && !name.equals(keep));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    private static boolean installOnce(Context context, Release release, ProgressListener listener) {
        File archive = new File(context.getCacheDir(), archiveName(release));
        // A failed download keeps what it fetched so the next attempt resumes it; anything that
        // reached verification and failed is not trusted and is removed.
        boolean keepPartial = false;
        try {
            deleteOtherArchives(context.getCacheDir(), archive.getName());
            File pendingRemoval = removalDirectory(LinuxRuntime.rootDir(context));
            if (pendingRemoval.exists() && listener != null) listener.onProgress(context.getString(R.string.rtinst_removing_leftovers), -1);
            if (!discard(pendingRemoval, null)) throw new IOException("Cannot clear the previous runtime: " + pendingRemoval);
            emptyTrash(LinuxRuntime.rootDir(context).getParentFile());
            String downloading = context.getString(R.string.rtinst_downloading);
            if (listener != null) listener.onProgress(Step.DOWNLOADING, downloading, 0);
            // Downloader reports a 0..1 fraction, or -1 while the total size is unknown; the
            // source argument is the host the bytes are coming from, named in the stage so a
            // download that sits still says which mirror is stalling rather than just "0%".
            boolean ok = Downloader.downloadFileFrom(release.url, archive, true, (source, fraction) -> {
                if (listener != null) {
                    String stage = source == null ? downloading : downloading + " · " + source;
                    listener.onProgress(Step.DOWNLOADING, stage,
                            fraction < 0 ? -1 : Math.round(fraction * 100f));
                }
            });
            if (!ok) {
                Log.w(TAG, "download failed");
                keepPartial = true;
                return false;
            }

            if (listener != null) listener.onProgress(Step.VERIFYING, context.getString(R.string.rtinst_verifying), -1);
            String actual = Hashes.sha256(archive);
            if (!release.sha256.equalsIgnoreCase(actual)) {
                Log.w(TAG, "checksum mismatch: wanted " + release.sha256 + ", got " + actual);
                return false;
            }

            File root = LinuxRuntime.rootDir(context);
            File staging = new File(root.getParentFile(), LinuxRuntime.DIR + ".new");
            File old = new File(root.getParentFile(), LinuxRuntime.DIR + ".old");
            recoverInterruptedSwap(root, staging, old);
            if (!discard(staging, null) || !staging.mkdirs()) return false;
            if (listener != null) listener.onProgress(context.getString(R.string.rtinst_extracting), -1);
            if (!extract(context, archive, staging, listener)) {
                RuntimeFileTree.delete(staging, null);
                return false;
            }
            FileUtils.writeString(new File(staging, VERSION_FILE), release.version);

            if (!discard(old, null)) {
                discard(staging, null);
                return false;
            }
            if (root.isDirectory() && !root.renameTo(old)) {
                RuntimeFileTree.delete(staging, null);
                return false;
            }

            try {
                RuntimeFileTree.carryHome(old, staging);
            } catch (IOException e) {
                Log.w(TAG, "could not carry root across the update; rolling back", e);
                old.renameTo(root);
                try { RuntimeFileTree.delete(staging, null); }
                catch (IOException cleanup) { Log.w(TAG, "could not clear the failed staging tree", cleanup); }
                return false;
            }

            if (!staging.renameTo(root)) {
                File keptTo = new File(staging, USER_DATA);
                if (keptTo.isDirectory() && old.isDirectory()) keptTo.renameTo(new File(old, USER_DATA));
                if (old.isDirectory()) old.renameTo(root);
                return false;
            }
            discard(old, null);
            return LinuxRuntime.isInstalled(context);
        } catch (Exception e) {
            Log.e(TAG, "install", e);
            return false;
        } finally {
            if (!keepPartial) archive.delete();
        }
    }

    /**
     * Installs from an archive the user supplied. Its removal quarantine is finished first, for the
     * reason {@link #installOnce} gives, and its download step is the one thing missing: there is
     * nothing to fetch, only a file already on the device.
     */
    private static boolean installFromFileOnce(Context context, File archive, Release catalogue,
                                               ProgressListener listener) {
        try {
            if (!archive.isFile() || archive.length() == 0) {
                Log.w(TAG, "no archive at " + archive);
                return false;
            }
            File pendingRemoval = removalDirectory(LinuxRuntime.rootDir(context));
            if (pendingRemoval.exists() && listener != null) listener.onProgress(context.getString(R.string.rtinst_removing_leftovers), -1);
            RuntimeFileTree.delete(pendingRemoval, null);

            String version = catalogue != null ? catalogue.version : null;
            String expected = catalogue != null ? catalogue.sha256 : null;
            if (expected != null && !expected.isEmpty()) {
                if (listener != null) listener.onProgress(context.getString(R.string.rtinst_verifying), -1);
                String actual = Hashes.sha256(archive);
                if (!expected.equalsIgnoreCase(actual)) {
                    Log.w(TAG, "local archive checksum mismatch: wanted " + expected + ", got " + actual);
                    return false;
                }
            } else {
                // No catalog to check against. The archive's own digest is what tells one local
                // build from another, so it is what the version marker records.
                if (version == null || version.isEmpty()) version = localVersion(archive);
                Log.w(TAG, "installing " + archive.getName() + " without a catalog checksum");
            }
            return unpack(context, archive, version, listener);
        } catch (Exception e) {
            Log.e(TAG, "installFromFile", e);
            return false;
        }
    }

    /** What an install with no catalog is recorded under: the archive's own digest, shortened. */
    private static String localVersion(File archive) {
        String digest = Hashes.sha256(archive);
        return digest.length() > 12 ? "local-" + digest.substring(0, 12) : "local";
    }

    /**
     * Unpacks {@code archive} beside the live rootfs and swaps it in, carrying the user's home
     * across. Nothing touches the live runtime until the whole tree is unpacked, so a failure here
     * leaves the previous one in place rather than a half runtime isInstalled() would launch. The
     * tarball's own empty /root is dropped first so the rename has somewhere to land.
     */
    private static boolean unpack(Context context, File archive, String version, ProgressListener listener) throws IOException {
        File root = LinuxRuntime.rootDir(context);
        File staging = new File(root.getParentFile(), LinuxRuntime.DIR + ".new");
        File old = new File(root.getParentFile(), LinuxRuntime.DIR + ".old");
        recoverInterruptedSwap(root, staging, old);
        RuntimeFileTree.delete(staging, null);
        if (!staging.mkdirs()) return false;
        if (listener != null) listener.onProgress(context.getString(R.string.rtinst_extracting), -1);
        if (!extract(context, archive, staging, listener)) {
            RuntimeFileTree.delete(staging, null);
            return false;
        }
        FileUtils.writeString(new File(staging, VERSION_FILE), version);

        RuntimeFileTree.delete(old, null);
        if (root.isDirectory() && !root.renameTo(old)) {
            RuntimeFileTree.delete(staging, null);
            return false;
        }

        // A rename inside the same filesystem, so a 30 GB library costs nothing and cannot
        // half-copy. If this fails the update is abandoned and the previous runtime is put back
        // untouched - shipping a working system with the user's games gone is the worse outcome.
        try {
            RuntimeFileTree.carryHome(old, staging);
        } catch (IOException e) {
            Log.w(TAG, "could not carry root across the update; rolling back", e);
            // Restore the live name even if cleaning the failed staging tree is denied.
            old.renameTo(root);
            try { RuntimeFileTree.delete(staging, null); }
            catch (IOException cleanup) { Log.w(TAG, "could not clear the failed staging tree", cleanup); }
            return false;
        }

        if (!staging.renameTo(root)) {
            File keptTo = new File(staging, USER_DATA);
            if (keptTo.isDirectory() && old.isDirectory()) keptTo.renameTo(new File(old, USER_DATA));
            if (old.isDirectory()) old.renameTo(root);
            return false;
        }
        RuntimeFileTree.delete(old, null);
        return LinuxRuntime.isInstalled(context);
    }

    static void recoverInterruptedSwap(File root, File staging, File old) throws IOException {
        File target = root.isDirectory() ? root : old;
        File stagedHome = new File(staging, USER_DATA);
        File home = new File(target, USER_DATA);
        if (target.isDirectory() && stagedHome.isDirectory() && (!home.exists() || isEmptyDir(home))) {
            RuntimeFileTree.carryHome(staging, target);
            Log.w(TAG, "recovered " + USER_DATA + " from an interrupted update");
        }
        if (!root.isDirectory() && old.isDirectory() && old.renameTo(root)) {
            Log.w(TAG, "restored the previous runtime after an interrupted update");
        }
    }

    static final String TRASH = ".runtime-trash";

    static boolean discard(File tree, java.util.function.LongConsumer progress) {
        if (RuntimeFileTree.deleteWhatCan(tree, progress)) return true;
        File trash = new File(tree.getParentFile(), TRASH);
        trash.mkdirs();
        File aside = new File(trash, tree.getName() + "-" + System.currentTimeMillis());
        if (tree.renameTo(aside)) {
            Log.w(TAG, "could not delete all of " + tree + "; set the rest aside in " + aside);
            return true;
        }
        Log.w(TAG, "could not delete or set aside " + tree);
        return !tree.exists();
    }

    static void emptyTrash(File parent) {
        File trash = new File(parent, TRASH);
        if (trash.exists() && !RuntimeFileTree.deleteWhatCan(trash, null))
            Log.i(TAG, "some runtime leftovers still cannot be deleted: " + trash);
    }

    private static boolean isEmptyDir(File dir) {
        String[] names = dir.list();
        return dir.isDirectory() && names != null && names.length == 0;
    }

    public static Removal beginUninstall(Context context) {
        if (sessionInUse()) return null;
        return beginUninstall(context.getApplicationContext(), LinuxRuntime.rootDir(context));
    }

    private static boolean sessionInUse() {
        com.droiddeck.launcher.session.SessionState session = com.droiddeck.launcher.session.SessionState.INSTANCE;
        com.droiddeck.launcher.session.SessionPhase phase = session.getPhase();
        return session.getRunning() ||
                (phase != com.droiddeck.launcher.session.SessionPhase.IDLE && phase != com.droiddeck.launcher.session.SessionPhase.FAILED);
    }

    static Removal beginUninstall(Context app, File root) {
        synchronized (JOB_LOCK) {
            if (running != null) return null;
            removalError = null;
            running = new Job(true, text(app, R.string.rtinst_removing));
            return new Removal(app, root, running);
        }
    }

    private static String text(Context app, int id) {
        return app == null ? "" : app.getString(id);
    }

    public static final class Removal {
        private final Context app;
        private final File root;
        private final Job job;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        private Removal(Context app, File root, Job job) { this.app = app; this.root = root; this.job = job; }

        public boolean run(ProgressListener listener) {
            if (!started.compareAndSet(false, true)) return join(job, listener);
            if (listener != null) job.listeners.add(listener);
            try {
                requireStopped();
                report(text(app, R.string.rtinst_removing), -1);
                final long[] removed = {0};
                final long[] lastShown = {0};
                java.util.function.LongConsumer progress = ignored -> {
                    removed[0]++;
                    long now = System.nanoTime();
                    if (removed[0] == 1 || now - lastShown[0] >= 200_000_000L) {
                        lastShown[0] = now;
                        report(app == null ? "" : app.getResources().getQuantityString(
                                R.plurals.rtinst_removing_entries, (int) Math.min(removed[0], Integer.MAX_VALUE), removed[0]), -1);
                    }
                };
                File pending = removalDirectory(root);
                if (root.exists()) {
                    if (!discard(pending, progress) || !root.renameTo(pending))
                        throw new IOException("Cannot prepare the runtime for removal");
                } else if (!pending.isDirectory() && !pending.mkdirs()) {
                    throw new IOException("Cannot prepare the runtime for removal");
                }
                for (File leftover : new File[]{
                        new File(root.getParentFile(), root.getName() + ".new"),
                        new File(root.getParentFile(), root.getName() + ".old"),
                        pending}) {
                    if (!discard(leftover, progress)) throw new IOException("Cannot remove " + leftover);
                }
                emptyTrash(root.getParentFile());
                requireStopped();
                if (app != null) RuntimeFileTree.delete(new File(app.getNoBackupFilesDir(), "guest-runtime"), null);
                job.ok = true;
                report(text(app, R.string.rtinst_removed), 100);
                return true;
            } catch (IOException e) {
                removalError = text(app, R.string.rtinst_removal_failed);
                Log.w(TAG, "runtime removal incomplete", e);
                report(removalError, -1);
                return false;
            } finally {
                synchronized (JOB_LOCK) { running = null; }
                job.done.countDown();
            }
        }

        private void requireStopped() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Runtime removal was interrupted");
            if (app != null && sessionInUse()) throw new IOException("A session is still using the runtime");
        }
        private void report(String stage, int percent) {
            job.stage = stage;
            job.percent = percent;
            for (ProgressListener listener : job.listeners) listener.onProgress(stage, percent);
        }
    }

    /**
     * A whole-rootfs tar, which is not the shape the shared extractor handles: a distribution
     * rootfs is full of hard links (one binary under several names), and an entry written as an
     * empty file instead of its link target is a rootfs that boots to nothing. Symlinks, hard
     * links and the executable bit are all carried over here.
     *
     * <p>Progress is a percentage of the archive actually read, counted in compressed bytes rather
     * than in entries. A rootfs is a few hundred thousand files, and a screen that only hears about
     * every 2000th one of them - with no number on any of them - shows one frozen line for minutes
     * while the unpack is working exactly as intended. Which of the two that is cannot be told
     * apart from the outside, and it was reported as a hang (DroidDeckCOK). Bytes move while the
     * stream is consumed, so the number creeps the whole way through, including inside one large
     * file; a percentage that stops moving is then a real stall rather than a reporting gap.
     */
    public static boolean extract(Context context, File archive, File destination, ProgressListener listener) {
        String extracting = context.getString(R.string.rtinst_extracting);
        long total = Math.max(1L, archive.length());
        // What has been reported so far, and when: written by reportRead, kept in arrays because the
        // copy loop below ticks from inside a lambda-free inner block.
        long[] shown = {-1L};
        long[] at = {0L};
        try (Counting counting = new Counting(new FileInputStream(archive));
             TarArchiveInputStream tar = new TarArchiveInputStream(
                     new ZstdCompressorInputStream(new BufferedInputStream(counting, 1 << 16)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                File file = ArchivePaths.inside(destination, entry.getName());
                if (file == null) {
                    Log.w(TAG, "skipping entry outside the rootfs: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();

                if (entry.isSymbolicLink()) {
                    file.delete();
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                } else if (entry.isLink()) {
                    File target = ArchivePaths.inside(destination, entry.getLinkName());
                    if (target == null) {
                        Log.w(TAG, "skipping hard link outside the rootfs: " + entry.getLinkName());
                        continue;
                    }
                    file.delete();
                    try {
                        Files.createLink(file.toPath(), target.toPath());
                    } catch (IOException | UnsupportedOperationException e) {
                        if (target.isFile()) {
                            Files.copy(target.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } else if (entry.isFile()) {
                    try (OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[1 << 16];
                        int read;
                        int chunks = 0;
                        while ((read = tar.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            // Roughly every four megabytes, so one large file moves the number too:
                            // the per-entry tick below is silent for the whole of a 500 MB one.
                            if ((++chunks & 63) == 0) {
                                reportRead(listener, extracting, counting.count, total, shown, at);
                            }
                        }
                    }
                    if ((entry.getMode() & 0111) != 0) file.setExecutable(true, false);
                } else {
                    continue;
                }
                reportRead(listener, extracting, counting.count, total, shown, at);
            }
            // The archive's tail padding can leave the count just short of its length; the unpack
            // is over, so the bar is finished rather than left a per cent from the end.
            reportRead(listener, extracting, total, total, shown, at);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extract", e);
            return false;
        }
    }

    /** How often an unpack may re-report while its percentage creeps: five times a second. */
    private static final long PROGRESS_NS = 200_000_000L;

    /**
     * Reports how far into {@code total} bytes the read has got, forwards only and at most
     * {@link #PROGRESS_NS} apart.
     *
     * <p>Forward-only on purpose: an extractor that goes quiet is what a stall looks like from the
     * screen, and the run's own watchdog reads these reports as its heartbeat - so repeats would
     * keep a wedged unpack looking alive.
     */
    private static void reportRead(ProgressListener listener, String stage, long read, long total,
                                   long[] shown, long[] at) {
        if (listener == null || total <= 0) return;
        long now = System.nanoTime();
        int percent = (int) Math.min(100L, read * 100L / total);
        if (percent <= shown[0]) return;
        if (percent < 100 && now - at[0] < PROGRESS_NS) return;
        shown[0] = percent;
        at[0] = now;
        listener.onProgress(stage, percent);
    }

    /**
     * The bytes the decompressor has taken off the archive, which is what {@link #extract}'s
     * percentage is measured in: the uncompressed size is not known before the tar ends.
     */
    private static final class Counting extends FilterInputStream {
        long count;

        Counting(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            if (skipped > 0) count += skipped;
            return skipped;
        }
    }
}
