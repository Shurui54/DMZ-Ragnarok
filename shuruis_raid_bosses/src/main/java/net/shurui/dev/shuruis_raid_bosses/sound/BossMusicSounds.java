package net.shurui.dev.shuruis_raid_bosses.sound;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.dev.shuruis_raid_bosses.Shuruis_raid_bosses;

/**
 * Sound events for the boss music system.
 *
 * <p>Registering the event makes it resolvable at runtime and puts it in the editor's sound dropdown so an
 * operator can pick it (or clear it) for any encounter. The encounter carries the CHOSEN id as a string; the
 * playback path never maps a boss to a track, it plays whatever the encounter's {@code bossMusic} field holds.
 *
 * <p>The backing file ({@code assets/dmz_ragnarok/sounds/music/boss_snipperjack.ogg}) is streamed
 * ({@code stream = true} in sounds.json) because a music loop is long. A missing file degrades to silence on
 * the client, it never fails registration: this is only the event, not the audio.
 */
public final class BossMusicSounds {
    private BossMusicSounds() {}

    public static final DeferredRegister<SoundEvent> REGISTER =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Shuruis_raid_bosses.MODID);

    /** Duke Snipperjack's spooky boss loop. The event id is {@code dmz_ragnarok:music.boss_snipperjack}. */
    public static final RegistryObject<SoundEvent> BOSS_SNIPPERJACK = REGISTER.register("music.boss_snipperjack",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(Shuruis_raid_bosses.MODID, "music.boss_snipperjack")));
}
