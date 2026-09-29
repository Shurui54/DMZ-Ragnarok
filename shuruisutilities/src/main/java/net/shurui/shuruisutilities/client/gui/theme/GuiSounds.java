package net.shurui.shuruisutilities.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Single place that plays DragonMineZ's native menu sounds for SU's own GUIs, so every screen and button feels
 * like part of DMZ. Routing all playback through here keeps the interaction-to-sound mapping changeable in one
 * edit, and the sound ids themselves live as named constants in {@link GuiTheme}.
 *
 * <p>Defensive resolution: the {@link SoundEvent}s are looked up from the sound registry by
 * {@link ResourceLocation} (never by a hard {@code MainSounds} field reference) and null-guarded, so a missing or
 * renamed sound in a future DMZ version degrades to silence instead of throwing. That matches the codebase's
 * convention that DMZ integration degrades rather than crashes, even though DMZ is a mandatory dependency.
 *
 * <p>Client-only: this class touches {@code net.minecraft.client} types and must never be classloaded on a
 * dedicated server. Playback goes through the local {@link Minecraft} sound manager only.
 *
 * <p>Volume is held modest ({@link GuiTheme#UI_SOUND_VOLUME}) so a click never overpowers gameplay audio.
 */
public final class GuiSounds {

    private GuiSounds() {
    }

    private static final Interaction NAVIGATE = new Interaction(GuiTheme.SND_UI_MENU_SWITCH);
    private static final Interaction BUTTON = new Interaction(GuiTheme.SND_PIP_MENU);
    private static final Interaction CONFIRM = new Interaction(GuiTheme.SND_CONFIRM_MENU);

    /**
     * Monotonic count of navigation ("open") sounds played, used to enforce ONE sound per user action across a
     * navigation. When a button press opens a fresh screen, that screen's {@code init()} plays {@link #navigate()}
     * synchronously inside the button's action; the button reads this counter before and after running its action
     * (see {@link #navigateCount()}) and, if it advanced, suppresses its own press/confirm sound so the two do not
     * stack (the "two DMZ sounds on some presses" defect). Incremented only on an ACTUAL navigate playback below.
     */
    private static int navigateCount;

    /** Reads of the navigation-sound counter used by {@link net.shurui.shuruisutilities.client.gui.DmzTextureButton}
     *  to detect whether its action triggered a screen-open sound, so it can avoid playing a second sound. */
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
     * A UI interaction bound to a DMZ sound id, resolving the {@link SoundEvent} lazily on first play and caching
     * only a POSITIVE result. A negative lookup is not cached, so a sound that becomes available later (e.g. once
     * DMZ finishes registering) is picked up on a subsequent play rather than being permanently silenced.
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
