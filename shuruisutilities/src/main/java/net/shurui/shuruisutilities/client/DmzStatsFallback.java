package net.shurui.shuruisutilities.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.world.entity.player.Player;

/**
 * One reusable fallback StatsData per player for DragonMineZ's renderers.
 *
 * <p>DMZ's player renderer and its layers read stats as {@code cap.orElse(new StatsData(player))}. Java builds the
 * argument before orElse runs, so every call constructed a full StatsData (character, every hair style, stats), even
 * though the player has the capability and the new object is thrown away. Those calls run per bone, per layer, per
 * frame, which showed up as a steady render-thread cost and garbage in profiles. The fallback is only ever used when the
 * capability is missing, and then it is the same default data each time, so handing back one cached instance per player
 * is equivalent. Render thread only.
 */
public final class DmzStatsFallback
{
    private DmzStatsFallback() {}

    private static final Map<Player, StatsData> FALLBACKS = new WeakHashMap<>();

    public static StatsData forPlayer(Player player)
    {
        if (player == null)
        {
            return new StatsData(null);
        }
        return FALLBACKS.computeIfAbsent(player, StatsData::new);
    }
}
