package net.shurui.dev.shuruis_dmz_tournaments.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Single place that plays DragonMineZ's native menu sounds for sdu's own GUIs, so every screen and button feels
 * like part of DMZ. Sound ids live as named constants in {@link GuiTheme}.
 *
 * <p>Defensive resolution: the {@link SoundEvent}s are looked up from the sound registry by
 * {@link ResourceLocation} (never a hard {@code MainSounds} field) and null-guarded, so a missing or renamed
 * sound in a future DMZ version degrades to silence instead of throwing.
 *
 * <p>Client-only: this touches {@code net.minecraft.client} types and must never be classloaded on a dedicated
 * server. Playback goes through the local {@link Minecraft} sound manager only.
 */
public final class GuiSounds {

    private GuiSounds() {
    }

    /**
     * Whether this mod's menus play DMZ's native UI sounds. sdu gates this on a per-player client config; this
     * addon has none, so the theme keeps them on (the same default sdu ships). One named constant so a future
     * client config could flip it in one place.
     */
    private static final boolean SOUNDS_ENABLED = true;

    private static final Interaction NAVIGATE = new Interaction(GuiTheme.SND_UI_MENU_SWITCH);
    private static final Interaction BUTTON = new Interaction(GuiTheme.SND_PIP_MENU);
    private static final Interaction CONFIRM = new Interaction(GuiTheme.SND_CONFIRM_MENU);

    /**
     * Monotonic count of navigation ("open") sounds played, to enforce ONE sound per action. When a press opens
     * a fresh screen, that screen's {@code init()} plays {@link #navigate()} synchronously inside the action; the
     * button reads this counter before and after and, if it advanced, suppresses its own press/confirm sound so
     * the two do not stack (the "two DMZ sounds on some presses" defect). Bumped only on an ACTUAL navigate below.
     */
    private static int navigateCount;

    /** Read by {@link net.shurui.dev.shuruis_dmz_tournaments.client.gui.DmzTextureButton} to tell whether its
     *  action opened a screen, so it can skip a second sound. */
    public static int navigateCount() {
        return navigateCount;
    }

    /** Navigation: a screen opening, a tab switch, a dropdown opening or a row being selected. */
    public static void navigate() {
        navigateCount++;
        play(NAVIGATE);
    }

    /** An ordinary (non-committing) button press. */
    public static void button() {
        play(BUTTON);
    }

    /** A commit: Save, Add, Select, or a Back that applies pending edits. */
    public static void confirm() {
        play(CONFIRM);
    }

    private static void play(Interaction interaction) {
        if (!SOUNDS_ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) {
            return;
        }
        SoundEvent sound = interaction.resolve();
        if (sound == null) {
            return; // DMZ sound absent/renamed: degrade to silence.
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(
                sound, GuiTheme.UI_SOUND_PITCH, GuiTheme.UI_SOUND_VOLUME));
    }

    /**
     * A UI interaction bound to a DMZ sound id, resolving the {@link SoundEvent} lazily and caching only a
     * POSITIVE result. A negative lookup is not cached, so a sound registered later (once DMZ finishes) is picked
     * up on a subsequent play rather than being permanently silenced.
     */
    private static final class Interaction {
        private final ResourceLocation id;
        private SoundEvent resolved;

        Interaction(ResourceLocation id) {
            this.id = id;
        }

        SoundEvent resolve() {
            if (resolved == null) {
                resolved = BuiltInRegistries.SOUND_EVENT.get(id);
            }
            return resolved;
        }
    }
}
