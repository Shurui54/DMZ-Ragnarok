package net.shurui.shuruisutilities.client.cosmetics;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * The client-side switches the worn-cosmetic renderer reads: whether to draw OTHER players' cosmetics, and
 * whether to let an enclosing HEAD cosmetic hide DragonMineZ's hair.
 *
 * <p>Deliberately a plain flag pair rather than a Forge config. These are pure display preferences with no
 * gameplay effect and nothing server-authoritative depends on them, so a heavyweight config (which would also
 * sync a SERVER spec or need a client spec registered at the right moment) would be more machinery than the
 * feature is worth. {@link #showOthers} defaults ON, which is what a player expects the first time they see
 * somebody in a hat; {@link #hideHairUnderHelmets} defaults ON so a head that fully encloses the head hides the
 * hair by default, which is the look the art was made for.
 *
 * <p>The two are PERSISTED to a small properties file next to the game directory so a choice survives a restart,
 * unlike the earlier in-memory-only form. Loading is lazy and best-effort: a missing or unreadable file simply
 * leaves both at their defaults, and a write failure is swallowed. Never throws, so it can be touched from a
 * command, a keybind or the render thread without a guard.
 *
 * <p>The LOCAL player's own cosmetics are never gated by {@link #showOthers}: hiding what you are wearing from
 * yourself is not the point of the switch, and the renderer already hides your own in true first person.
 *
 * <p>Client only.</p>
 */
public final class CosmeticRenderOptions
{
    private CosmeticRenderOptions()
    {
    }

    // Default ON: a client that has never touched the switch sees everyone's cosmetics, which is the expected look.
    private static volatile boolean showOthers = true;

    // Default ON: an enclosing head cosmetic hides the DMZ hair, which is what the enclosing art was drawn for. A
    // player who would rather keep their hair flips this off with /cosmetichair off.
    private static volatile boolean hideHairUnderHelmets = true;

    private static volatile boolean loaded = false;

    public static boolean showOthers()
    {
        ensureLoaded();
        return showOthers;
    }

    public static void setShowOthers(boolean value)
    {
        ensureLoaded();
        showOthers = value;
        save();
    }

    /** Whether this client lets a HEAD cosmetic marked {@code hidesHair} suppress DragonMineZ's hair. */
    public static boolean hideHairUnderHelmets()
    {
        ensureLoaded();
        return hideHairUnderHelmets;
    }

    public static void setHideHairUnderHelmets(boolean value)
    {
        ensureLoaded();
        hideHairUnderHelmets = value;
        save();
    }

    // ---- persistence: a tiny properties file, best effort, never throws ----

    private static synchronized void ensureLoaded()
    {
        if (loaded)
            return;
        loaded = true;
        File f = file();
        if (f == null || !f.isFile())
            return;
        try (FileInputStream in = new FileInputStream(f))
        {
            Properties p = new Properties();
            p.load(in);
            showOthers = Boolean.parseBoolean(p.getProperty("showOthers", "true"));
            hideHairUnderHelmets = Boolean.parseBoolean(p.getProperty("hideHairUnderHelmets", "true"));
        }
        catch (Throwable ignored)
        {
            // A corrupt or unreadable file leaves the defaults in place. A preference is not worth a crash.
        }
    }

    private static synchronized void save()
    {
        File f = file();
        if (f == null)
            return;
        try
        {
            File dir = f.getParentFile();
            if (dir != null)
                dir.mkdirs();
            Properties p = new Properties();
            p.setProperty("showOthers", Boolean.toString(showOthers));
            p.setProperty("hideHairUnderHelmets", Boolean.toString(hideHairUnderHelmets));
            try (FileOutputStream out = new FileOutputStream(f))
            {
                p.store(out, "ShuruisUtilities cosmetic client preferences");
            }
        }
        catch (Throwable ignored)
        {
            // A write failure just means the choice does not survive a restart; it still holds this session.
        }
    }

    private static File file()
    {
        try
        {
            // gamedir/config, resolved lazily so this class does not touch the Minecraft instance at load time.
            File gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory;
            return new File(new File(gameDir, "config"), "dmz_ragnarok_cosmetic_client.properties");
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
