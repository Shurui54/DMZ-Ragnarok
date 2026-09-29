package net.shurui.dev.sdu.shenron;

import java.util.Locale;

// four Shenron-shrine colour variants, each mapping to a registry-name suffix (shenron_shrine_<color>) and a
// config key. closed enum so block registry, config and packets agree on the same ids.
public enum ShrineColor {
    BLUE,
    GOLD,
    GREEN,
    RED;

    // lowercase config/registry key.
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String blockName() {
        return "shenron_shrine_" + key();
    }

    // unknown -> BLUE.
    public static ShrineColor fromKey(String key) {
        if (key != null) {
            for (ShrineColor c : values()) {
                if (c.key().equalsIgnoreCase(key)) {
                    return c;
                }
            }
        }
        return BLUE;
    }
}
