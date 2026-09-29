package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.core.BlockPos;

/**
 * Reaches the three private position collections inside DMZ's radar sync packet so SU can add grave totems to a
 * packet DMZ has already built.
 *
 * <p>All three are {@code private final} with no getters, but the collections themselves are the mutable
 * {@code ArrayList}s and {@code HashMap} {@code buildRadarPacket} created, so reading the reference is enough: no
 * field is reassigned and no {@code @Mutable} is needed.
 *
 * <p>Both list fields matter, and they are NOT the same object. {@code buildRadarPacket} ends with
 * {@code new ArrayList(positionsBySet.get("earth"))}, a COPY, and hands both the copy and the original map to the
 * packet. DMZ's own Earth and Namek radars take a hardcoded branch that reads the dedicated lists, while custom
 * radars read the map, so an earth totem added to only one of them would show on one radar and not the other.
 */
@Mixin(targets = "com.dragonminez.common.network.S2C.RadarSyncS2C", remap = false)
public interface AccessorRadarSyncS2C
{
    @Accessor(value = "positionsBySet", remap = false)
    Map<String, List<BlockPos>> su$positionsBySet();

    @Accessor(value = "earthPositions", remap = false)
    List<BlockPos> su$earthPositions();

    @Accessor(value = "namekPositions", remap = false)
    List<BlockPos> su$namekPositions();
}
