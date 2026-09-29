package net.shurui.shuruisutilities.client.radial;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.dragonminez.client.gui.radial.nodes.KiWeaponNode;
import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.client.hud.EnergyClientCache;
import net.shurui.shuruisutilities.energy.EnergyKind;
import net.shurui.shuruisutilities.god.RagnarokKiWeapon;

/**
 * One of our ki weapons in DragonMineZ's own weapon wheel.
 *
 * <p>EXTENDS DMZ's {@link KiWeaponNode} rather than {@code AbstractRadialNode}, which is the whole point: everything
 * that makes a weapon entry behave like a weapon entry is inherited instead of reimplemented. That is where the red
 * text that turns green comes from ({@code labelColor} asks {@code active}, which compares this entry's type against
 * the type the player currently has summoned), and it is where selecting, deselecting and the toggle click come from
 * too. Reimplementing any of it here is how the two drift apart the first time DMZ retunes theirs.
 *
 * <p>Only three things differ, and they are the three that have to: the name, the icon, and whether the entry is
 * offered at all.
 */
public final class RagnarokKiWeaponNode extends KiWeaponNode
{
    private final RagnarokKiWeapon weapon;

    public RagnarokKiWeaponNode(RagnarokKiWeapon weapon)
    {
        super(weapon.type);
        this.weapon = weapon;
    }

    /**
     * Our own name, and DELIBERATELY unstyled.
     *
     * <p>It used to be given an explicit aqua, which is exactly what stopped the red and green being visible: an
     * explicit colour on the component wins over the colour the wheel is trying to draw the label in. Leaving the
     * component plain is what lets {@code labelColor} do its job.
     */
    @Override
    public Component label(StatsData stats)
    {
        return Component.literal(weapon.displayName);
    }

    @Override
    public ResourceLocation icon(StatsData stats)
    {
        return RagnarokRadialIcons.ANGEL_STAFF;
    }

    /** The staff is the Angel's alone, so nobody else is offered the entry. */
    @Override
    public boolean visible(StatsData stats)
    {
        // The Angel is a Ragnarok Key role (feature roles); the synced bar already says so, this is belt and braces.
        return net.shurui.dev.sdu.api.ClientGate.feature("roles") && EnergyClientCache.kind() == EnergyKind.ANGELIC;
    }
}
