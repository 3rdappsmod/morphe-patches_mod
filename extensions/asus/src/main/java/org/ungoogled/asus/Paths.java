package org.ungoogled.asus;

/**
 * Path rules the ASUS apps assume from ASUS's own storage stack.
 *
 * A local file's path is expected to start with /storage/, the FUSE view Android normally hands out. A phone that
 * reports a removable volume through vold's raw mount instead (/mnt/media_rw/&lt;uuid&gt;) is behaving correctly, but
 * ASUS File Manager treats it as an illegal path and stops while drawing the volume list. Both forms name the same
 * file, so both are accepted.
 */
public final class Paths {
    private Paths() {
    }

    /** True when [path] is a local path in either the FUSE view or vold's raw mount. */
    public static boolean isLocalPath(String path) {
        if (path == null) return false;
        return path.startsWith("/storage/") || path.startsWith("/mnt/media_rw/");
    }
}
