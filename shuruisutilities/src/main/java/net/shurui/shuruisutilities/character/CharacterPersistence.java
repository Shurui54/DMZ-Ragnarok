package net.shurui.shuruisutilities.character;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.commands.util.VirtualChests;

/**
 * Forge only carries the {@code PlayerPersisted} subtag of {@code getPersistentData()} across a death respawn.
 * Character slots (and player vaults) live at the persistent-data root, so without this they'd be wiped when a
 * player dies. On clone we copy those tags from the old player onto the new one so they always survive.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class CharacterPersistence
{
    private CharacterPersistence() {}

    private static final String[] KEYS = { "su_characters", VirtualChests.VIRTUALCHEST_TAG };

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event)
    {
        CompoundTag from = event.getOriginal().getPersistentData();
        CompoundTag to = event.getEntity().getPersistentData();
        for (String key : KEYS)
        {
            Tag t = from.get(key);
            if (t != null)
                to.put(key, t.copy());
        }
    }
}
