package net.shurui.dev.shuruis_raid_bosses.client.gui.theme;


import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * Single place that plays DMZ's native menu sounds for this addon's GUIs, so the interaction-to-sound mapping
 * is one edit and the sound ids live in {@link GuiTheme}.
 *
 * <p>Defensive: the {@link SoundEvent}s are looked up by {@link ResourceLocation} (never a hard field ref)
 * and null-guarded, so a missing or renamed sound in a future DMZ degrades to silence instead of throwing.
 *
 * <p>Client-only: touches {@code net.minecraft.client} types, never classloaded on a dedicated server.
 */
public final class GuiSounds {

    private GuiSounds() {
    }

    private static final Interaction NAVIGATE = new Interaction(GuiTheme.SND_UI_MENU_SWITCH);
    private static final Interaction BUTTON = new Interaction(GuiTheme.SND_PIP_MENU);
    private static final Interaction CONFIRM = new Interaction(GuiTheme.SND_CONFIRM_MENU);

    /**
     * Monotonic count of navigation sounds, to enforce ONE sound per user action. A button that opens a screen
     * plays {@link #navigate()} inside its action; the button reads this counter before/after and, if it
     * advanced, suppresses its own press/confirm sound so the two do not stack.
     */
    private static int navigateCount;

    /** Read by {@code DmzTextureButton} to detect whether its action opened a screen, so it avoids a second sound. */
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
     * A UI interaction bound to a DMZ sound id, resolving the {@link SoundEvent} lazily and caching only a
     * POSITIVE result. A negative lookup is not cached, so a sound that arrives later is picked up on a later play.
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
