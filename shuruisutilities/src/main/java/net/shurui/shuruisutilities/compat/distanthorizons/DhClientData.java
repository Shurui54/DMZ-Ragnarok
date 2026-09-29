package net.shurui.shuruisutilities.compat.distanthorizons;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Pure filesystem helpers for Distant Horizons' per-server client data. Deliberately references NO Distant Horizons
 * type and NO Minecraft client type: deleting a folder needs no DH API, and a folder delete cannot break when DH
 * changes its internals. The caller (client command / startup hook) supplies the server name; this class only ever
 * resolves and measures and deletes.
 *
 * <p>Layout as it sits on disk (verified against a live install):</p>
 * <pre>
 * &lt;gamedir&gt;/Distant_Horizons_server_data/&lt;URL-encoded server name&gt;/&lt;seedhash&gt;@&lt;namespace&gt;@@&lt;dim&gt;/DistantHorizons.sqlite
 * </pre>
 *
 * <p>DH names the per-server folder with {@code URLEncoder.encode(ServerData.name, UTF-8)} (a space becomes {@code +},
 * a section sign becomes {@code %C2%A7}). Rather than re-encode and hope DH's escaping matches byte for byte, we DECODE
 * each folder name back and compare it to the current server name. That tolerates trivial encoder differences (hex
 * case, for instance) and, more importantly, matches nothing at all when DH's scheme is something we do not recognise,
 * which is the safe failure: delete nothing rather than the wrong thing.</p>
 */
public final class DhClientData
{
    private DhClientData() {}

    /** The one folder name DH keeps its per-server client data under. Anything we touch must live inside this. */
    public static final String DH_ROOT_FOLDER = "Distant_Horizons_server_data";

    /** The SQLite file DH drops in each per-level subfolder. Used only as a sanity marker, never opened. */
    public static final String DH_DB_FILE = "DistantHorizons.sqlite";

    /** Immutable result of {@link #measure}. */
    public static final class Stats
    {
        public final long files;
        public final long bytes;

        Stats(long files, long bytes)
        {
            this.files = files;
            this.bytes = bytes;
        }
    }

    /** {@code <gamedir>/Distant_Horizons_server_data}, absolute and normalised. Never null; may not exist yet. */
    public static Path rootDir()
    {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().resolve(DH_ROOT_FOLDER).normalize();
    }

    /**
     * Resolve the DH data folder for a given server name by decoding the on-disk folder names and matching. Returns
     * null when there is no root folder, or no folder decodes back to this exact server name. Only ever returns a
     * DIRECT child of {@link #rootDir()}.
     */
    public static Path resolveServerDir(String serverName)
    {
        if (serverName == null || serverName.isEmpty())
            return null;
        Path root = rootDir();
        if (!Files.isDirectory(root))
            return null;
        try (Stream<Path> children = Files.list(root))
        {
            return children
                    .filter(Files::isDirectory)
                    .filter(child -> serverName.equals(decodeName(child.getFileName().toString())))
                    .findFirst()
                    .orElse(null);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private static String decodeName(String encoded)
    {
        try
        {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
        catch (RuntimeException e)
        {
            // a folder name that is not valid URL-encoding is not one of ours; never match it
            return null;
        }
    }

    /**
     * The single safety gate for any delete. A path passes only when it is a DIRECT child of
     * {@code <gamedir>/Distant_Horizons_server_data}, that root sits under the game directory, the path is an existing
     * directory, and it is not the root itself. Everything is compared absolute and normalised, so a {@code ..} in the
     * server name (or a symlink-style trick in the stored path) cannot walk out. If this returns false, do not delete.
     */
    public static boolean isSafeTarget(Path target)
    {
        if (target == null)
            return false;
        Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path root = rootDir();
        Path t = target.toAbsolutePath().normalize();

        if (!root.startsWith(gameDir))
            return false;                    // root must be inside the game directory
        if (root.equals(gameDir))
            return false;                    // paranoia: root must be a real subfolder
        if (t.equals(root))
            return false;                    // never the whole DH data folder, only one server
        if (!t.startsWith(root))
            return false;                    // must be inside the DH data folder
        if (t.getParent() == null || !t.getParent().equals(root))
            return false;                    // must be a DIRECT child (a server folder), nothing deeper or sideways
        return Files.isDirectory(t);
    }

    /** Count files and total bytes under a directory. Best effort: unreadable entries are skipped, never thrown. */
    public static Stats measure(Path dir)
    {
        if (dir == null || !Files.isDirectory(dir))
            return new Stats(0L, 0L);
        long[] acc = new long[2];
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.filter(Files::isRegularFile).forEach(p ->
            {
                acc[0]++;
                try
                {
                    acc[1] += Files.size(p);
                }
                catch (IOException ignored)
                {
                    // a file we cannot stat still counts, just not its bytes
                }
            });
        }
        catch (IOException ignored)
        {
            // partial measurement is fine; this figure is for a chat line, not an invariant
        }
        return new Stats(acc[0], acc[1]);
    }

    /**
     * Delete a directory tree, but ONLY after {@link #isSafeTarget} passes. Returns the number of files removed, or -1
     * when the target failed the safety gate (in which case nothing was touched). Deletion is depth-first so directories
     * empty before they are removed.
     */
    public static long deleteGuarded(Path target) throws IOException
    {
        if (!isSafeTarget(target))
            return -1L;
        Path t = target.toAbsolutePath().normalize();
        long[] removed = new long[1];
        try (Stream<Path> walk = Files.walk(t))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    boolean wasFile = Files.isRegularFile(p);
                    Files.deleteIfExists(p);
                    if (wasFile)
                        removed[0]++;
                }
                catch (IOException ignored)
                {
                    // one locked or vanished entry should not abort the sweep
                }
            });
        }
        return removed[0];
    }

    /** Bytes to a short human string (KB / MB / GB, binary units), for chat and logs. */
    public static String humanBytes(long bytes)
    {
        if (bytes < 1024L)
            return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0)
            return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0)
            return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}
