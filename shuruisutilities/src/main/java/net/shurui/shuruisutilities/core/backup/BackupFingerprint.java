package net.shurui.shuruisutilities.core.backup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * "Has any of this actually changed since the last snapshot?"
 *
 * <h2>The problem it exists to answer</h2>
 * The permission and store backups were taken on EVERY server start, unconditionally: a full recursive copy of the
 * SUData tree and of every group B store into a last-good directory, and a second full copy into a fresh timestamped
 * one. A server that restarts often did that every time and had nothing new to show for it, because a restart is not
 * an edit. The result was a pile of identical snapshots, ten directories of duplicated data churned on every boot,
 * and a real cost on a large permission tree at exactly the moment the server is trying to start.
 *
 * <p>So a snapshot now has to earn its place: the live tree is fingerprinted, the fingerprint of the last snapshot is
 * kept beside it, and when they match nothing is copied at all. Restart a server twenty times without touching a
 * permission and it writes zero backups.
 *
 * <h2>Why the file CONTENTS, and not the cheaper size plus timestamp</h2>
 * This was written the cheap way first, on the reasoning that hashing every byte during server start would replace a
 * wasteful copy with a wasteful read. A launch test proved that wrong on the first restart. SU rewrites its
 * permission and store files on shutdown whether or not anything changed, so every single boot saw fresh
 * last-modified times, every fingerprint differed, and the skip never once fired: two snapshots taken on
 * consecutive boots were verified byte for byte identical while the check insisted they were not. A modification
 * TIME is exactly the field that a rewrite of unchanged content changes, which makes it the wrong thing to ask.
 *
 * <p>Reading the bytes is also the cheaper half of the bargain, which is what the first version got backwards. The
 * work being avoided is TWO full recursive copies of the same tree; the work being spent is one sequential read of
 * it. When the data is unchanged that trade wins outright, and when it has changed the read is a rounding error
 * beside the copies that follow it.
 *
 * <h2>The marker lives outside the backup</h2>
 * Deliberately not inside the snapshot directory. A restore copies a snapshot back over the live tree, so a marker
 * kept inside would be copied into the live data, become part of the next fingerprint, and quietly make every
 * post-restore boot look changed. Kept beside it, the snapshot stays a byte-for-byte copy of what was there.
 */
public final class BackupFingerprint
{
    private BackupFingerprint() {}

    /** Sentinel for "could not be computed". Never equal to a real fingerprint, so it always forces a snapshot. */
    public static final String UNKNOWN = "";

    /**
     * Fingerprint of one or more trees, taken together and in the order given.
     *
     * <p>A missing root contributes a fixed marker rather than being skipped, so a store that DISAPPEARS changes the
     * fingerprint instead of being invisible. Returns {@link #UNKNOWN} on any failure, which callers must treat as
     * "assume changed": failing to back up because a fingerprint could not be read is the one outcome worth avoiding.
     */
    public static String of(List<File> roots)
    {
        if (roots == null || roots.isEmpty())
            return UNKNOWN;
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (File root : roots)
            {
                md.update(String.valueOf(root == null ? "null" : root.getName()).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                if (root == null || !root.exists())
                {
                    md.update("<absent>".getBytes(StandardCharsets.UTF_8));
                    md.update((byte) 0);
                    continue;
                }
                if (root.isFile())
                {
                    feed(md, root.getName(), root.toPath());
                    continue;
                }
                Path base = root.toPath();
                List<Path> files = new ArrayList<>();
                try (java.util.stream.Stream<Path> walk = Files.walk(base))
                {
                    for (Path p : (Iterable<Path>) walk::iterator)
                        if (Files.isRegularFile(p))
                            files.add(p);
                }
                // Sorted so the fingerprint does not depend on the order the filesystem happens to hand paths back,
                // which is not stable across machines or even across boots on some filesystems.
                files.sort(Comparator.comparing(Path::toString));
                for (Path p : files)
                    feed(md, base.relativize(p).toString(), p);
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[SUData] Could not fingerprint the live data; taking a snapshot anyway: {}",
                    t.toString());
            return UNKNOWN;
        }
    }

    // Path, then length, then the bytes. The path is in there so moving a file to a new name registers as a change
    // even when nothing about its contents did, and the length so a read that is cut short cannot collide with a
    // genuinely shorter file.
    private static void feed(MessageDigest md, String relativePath, Path file) throws java.io.IOException
    {
        md.update(relativePath.replace('\\', '/').getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        // Streamed in fixed blocks rather than readAllBytes: these trees are usually small, but a single oversized
        // store must not be able to pull its whole self into memory during server start.
        try (java.io.InputStream in = Files.newInputStream(file))
        {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0)
                md.update(buffer, 0, read);
        }
        md.update((byte) 0);
    }

    /** The fingerprint recorded next to a snapshot, or {@link #UNKNOWN} if there is none or it cannot be read. */
    public static String read(File marker)
    {
        try
        {
            if (marker == null || !marker.isFile())
                return UNKNOWN;
            return Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim();
        }
        catch (Throwable t)
        {
            return UNKNOWN;
        }
    }

    /** Record a fingerprint. A failure here is harmless: the next boot simply takes one more snapshot than needed. */
    public static void write(File marker, String fingerprint)
    {
        try
        {
            if (marker == null || fingerprint == null || fingerprint.isEmpty())
                return;
            File parent = marker.getParentFile();
            if (parent != null)
                parent.mkdirs();
            Files.writeString(marker.toPath(), fingerprint, StandardCharsets.UTF_8);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[SUData] Could not record the backup fingerprint: {}", t.toString());
        }
    }

    /**
     * Do the live data and the last snapshot match?
     *
     * <p>False whenever either side is {@link #UNKNOWN}, so an unreadable marker or an unfingerprintable tree always
     * results in a snapshot being taken. Also false when the snapshot directory itself is gone or empty, because a
     * marker saying "unchanged" beside a backup somebody deleted by hand would otherwise skip forever.
     */
    public static boolean matches(String live, String recorded, File snapshotDir)
    {
        if (live == null || recorded == null || live.isEmpty() || recorded.isEmpty())
            return false;
        if (!live.equals(recorded))
            return false;
        if (snapshotDir == null || !snapshotDir.isDirectory())
            return false;
        String[] children = snapshotDir.list();
        return children != null && children.length > 0;
    }
}
