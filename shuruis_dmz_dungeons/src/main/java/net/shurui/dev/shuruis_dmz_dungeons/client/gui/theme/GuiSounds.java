package net.shurui.dev.shuruis_dmz_dungeons.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Single place that plays DragonMineZ's native menu sounds for this mod's own GUIs. Routing all playback through
 * here keeps the interaction-to-sound mapping in one edit; the sound ids live as named constants in
 * {@link GuiTheme}.
 *
 * <p>Defensive resolution: the {@link SoundEvent}s are looked up from the registry by {@link ResourceLocation}
 * (never a hard {@code MainSounds} field reference) and null-guarded, so a missing or renamed sound in a future
 * DMZ degrades to silence instead of throwing, even though DMZ is a mandatory dependency.
 *
 * <p>Client-only: touches {@code net.minecraft.client} types and must never be classloaded on a dedicated server.
 */
public final class GuiSounds {

    private GuiSounds() {
    }

    private static final Interaction NAVIGATE = new Interaction(GuiTheme.SND_UI_MENU_SWITCH);
    private static final Interaction BUTTON = new Interaction(GuiTheme.SND_PIP_MENU);
    private static final Interaction CONFIRM = new Interaction(GuiTheme.SND_CONFIRM_MENU);

    /**
     * Monotonic count of navigation ("open") sounds played, to enforce ONE sound per user action. When a button
     * press opens a fresh screen, that screen's {@code init()} plays {@link #navigate()} synchronously inside the
     * button's action; the button reads this counter before and after (see {@link #navigateCount()}) and, if it
     * advanced, suppresses its own press/confirm sound so the two do not stack (the "two DMZ sounds on some
     * presses" defect). Incremented only on an ACTUAL navigate playback.
     */
    private static int navigateCount;

    /** Read by {@link net.shurui.dev.shuruis_dmz_dungeons.client.gui.DmzTextureButton} to detect whether its
     *  action triggered a screen-open sound, so it can avoid playing a second. */
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
     * A UI interaction bound to a DMZ sound id, resolving the {@link SoundEvent} lazily and caching only a POSITIVE
     * result. A negative lookup is not cached, so a sound that becomes available later (once DMZ finishes
     * registering) is picked up on a subsequent play rather than being permanently silenced.
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
