package net.shurui.dev.sdu.client.gui.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.client.ClientConfig;

/**
 * Single place that plays DragonMineZ's native menu sounds for sdu's own GUIs, so every screen and button feels
 * like part of DMZ. Routing all playback through here keeps the interaction-to-sound mapping changeable in one
 * edit, and the sound ids themselves live as named constants in {@link GuiTheme}.
 *
 * <p>Defensive resolution: the {@link SoundEvent}s are looked up from the sound registry by
 * {@link ResourceLocation} (never by a hard {@code MainSounds} field reference) and null-guarded, so a missing or
 * renamed sound in a future DMZ version degrades to silence instead of throwing. That matches the codebase's
 * convention that DMZ integration degrades rather than crashes, even though DMZ is a mandatory dependency.
 *
 * <h2>One sound per user action (the "two DMZ sounds on some presses" defect)</h2>
 * A single press could end up asking for two UI sounds. The common case is SYNCHRONOUS: a button's action opens a
 * fresh screen, whose {@code init()} plays {@link #navigate()} DURING the button's {@code onPress}, and then the
 * button plays its own press/confirm sound after {@code onPress} returns. Both land in the SAME client tick, so we
 * coalesce: {@link #play} keeps only the FIRST UI sound requested in a given client tick and drops any later ones
 * that same tick. Because the opened screen's {@code navigate()} runs first (inside the action) and the button's
 * own sound second, the navigate is the one that survives, which is the correct single sound for "I opened a
 * screen". This replaces the earlier before/after navigate-counter guard in the button, which was fragile and only
 * covered the button path; the coalescer covers every path (nested screens, a commit that also rebuilds, a tab
 * switch that rebuilds) with one rule and no per-call bookkeeping.
 *
 * <p>The ASYNCHRONOUS open (a press that fires an open-request PACKET; the server replies a tick or more later and
 * that reply opens the editor screen) cannot be a same-tick pair, so the coalescer does not merge it. Those
 * initiators are instead made silent at the call site (they open a screen, so the sound is the arriving screen's
 * own {@code navigate()}); see the hub / options editor entries.
 *
 * <p>Client-only: this class touches {@code net.minecraft.client} types and must never be classloaded on a
 * dedicated server. Playback goes through the local {@link Minecraft} sound manager only, and the tick counter
 * is bumped from the client tick on the Forge bus (registered {@code Dist.CLIENT}).
 *
 * <p>Volume is held modest ({@link GuiTheme#UI_SOUND_VOLUME}) so a click never overpowers gameplay audio.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class GuiSounds {

    private GuiSounds() {
    }

    private static final Interaction NAVIGATE = new Interaction(GuiTheme.SND_UI_MENU_SWITCH);
    private static final Interaction BUTTON = new Interaction(GuiTheme.SND_PIP_MENU);
    private static final Interaction CONFIRM = new Interaction(GuiTheme.SND_CONFIRM_MENU);

    /**
     * Monotonic count of client ticks, bumped once per {@link TickEvent.ClientTickEvent} END phase. Used only to
     * detect "same tick": {@link #play} records the tick a UI sound played on and drops any further UI sound
     * requested on that same tick, so a single user action makes at most one sound even when several paths ask
     * (screen open + button press, nested screens, a rebuild that re-plays). Not a wall-clock timer, so the rule
     * is deterministic and framerate independent, never a timing race.
     */
    private static long clientTick;
    /**
     * The tick number the most recent UI sound played on, or {@link Long#MIN_VALUE} if none yet. When a new
     * request arrives on the same {@link #clientTick}, it is coalesced away (dropped). {@code MIN_VALUE} can never
     * equal a real tick, so the very first sound always plays.
     */
    private static long lastSoundTick = Long.MIN_VALUE;

    /** Advance the same-tick coalescing window once per client tick. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            clientTick++;
        }
    }

    /** Navigation: a screen opening, a tab switch, a dropdown opening or a row being selected. */
    public static void navigate() {
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
        if (!ClientConfig.guiSounds) {
            return;
        }
        // Same-tick coalescing: at most ONE UI sound per client tick. The first request in a tick plays and
        // claims the tick; every later request in that same tick is dropped. This makes a synchronous
        // "open a screen from a button" pair (the opened screen's navigate() plus the button's own press
        // sound, both in one tick) collapse to a single sound, with no per-call counter bookkeeping.
        if (lastSoundTick == clientTick) {
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
        // Claim the tick only once we are actually going to play (a muted/absent sound must not consume the
        // tick's single slot, or a real sound later in the same tick would be wrongly suppressed).
        lastSoundTick = clientTick;
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
