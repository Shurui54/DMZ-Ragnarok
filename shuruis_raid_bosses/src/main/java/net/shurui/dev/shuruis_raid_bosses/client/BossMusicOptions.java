package net.shurui.dev.shuruis_raid_bosses.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The player's own on/off preference for boss music, a purely client side choice toggled with
 * {@code /bossmusic on|off}.
 *
 * <p>Persisted to a tiny file in the config directory rather than a Forge config, because it is read and
 * written from a client command that wants the change to stick immediately, not at the next world save, and it
 * must never travel to the server (a SERVER config would sync, a COMMON config is the operator's, neither is a
 * per-client toggle). Default ON: a fresh install hears the music until the player turns it off.
 */
public final class BossMusicOptions {
    private BossMusicOptions() {}

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final String FILE = "dmz_ragnarok-bossmusic.properties";
    private static final String KEY = "enabled";

    private static Boolean cached;

    public static boolean enabled() {
        if (cached == null) {
            cached = read();
        }
        return cached;
    }

    public static void setEnabled(boolean value) {
        cached = value;
        write(value);
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static boolean read() {
        Path path = file();
        if (!Files.exists(path)) {
            return true;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[Boss Music] Could not read {}, defaulting to on.", FILE, e);
            return true;
        }
        return !"false".equalsIgnoreCase(props.getProperty(KEY, "true"));
    }

    private static void write(boolean value) {
        Properties props = new Properties();
        props.setProperty(KEY, Boolean.toString(value));
        try (OutputStream out = Files.newOutputStream(file())) {
            props.store(out, "DMZ Ragnarok boss music preference");
        } catch (IOException e) {
            LOGGER.warn("[Boss Music] Could not save {}; the setting holds for this session only.", FILE, e);
        }
    }
}
