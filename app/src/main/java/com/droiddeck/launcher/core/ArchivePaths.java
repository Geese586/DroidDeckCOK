package com.droiddeck.launcher.core;

import java.io.File;
import java.io.IOException;

/**
 * Where an archive entry may land. An entry name ("../x", "/etc/x") or a symlink already written
 * by an earlier entry can point outside the directory being filled; the canonical path follows
 * both, so anything that does not resolve inside it is refused.
 */
public final class ArchivePaths {
    private ArchivePaths() {}

    /**
     * The file for {@code name} under {@code destination}, or null when it would resolve outside
     * it. The destination itself counts as inside (a "./" entry).
     */
    public static File inside(File destination, String name) throws IOException {
        String base = destination.getCanonicalPath();
        File file = new File(destination, name);
        String path = file.getCanonicalPath();
        if (path.equals(base) || path.startsWith(base + File.separator)) return file;
        return null;
    }
}
