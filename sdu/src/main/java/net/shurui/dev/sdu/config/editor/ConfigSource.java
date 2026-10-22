package net.shurui.dev.sdu.config.editor;

import java.util.List;

/**
 * One config file (or one slice of one) the editor can change: its options, and how to write it back and tell its
 * owner. {@link #commit} runs once per save, after every changed option of this source was stored.
 */
public abstract class ConfigSource {

    /** Stable id, the prefix of every option id from this source. */
    public final String id;
    /** The file as an operator would find it, e.g. {@code config/sdu-common.toml}. */
    public final String file;

    protected ConfigSource(String id, String file) {
        this.id = id;
        this.file = file;
    }

    /** The options this source offers right now (empty while its file is not loaded). */
    public abstract List<ConfigEntry> entries();

    /**
     * Run one save's stores and its commit as a unit. Sources whose file can be reloaded by another thread while
     * they are being changed (Forge's config file watcher) hold the file's own lock here.
     */
    public void locked(Runnable work) {
        work.run();
    }

    /** Write the stored values to disk and let the owning feature pick them up. */
    public abstract void commit() throws Exception;
}
