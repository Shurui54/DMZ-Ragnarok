package net.shurui.shuruisutilities.client.radial;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.dragonminez.client.gui.radial.AbstractRadialNode;
import com.dragonminez.common.stats.StatsData;

import net.shurui.shuruisutilities.client.hud.EnergyClientCache;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.energy.EnergyKind;
import net.shurui.shuruisutilities.god.PacketAngelStaffToggle;

/**
 * The Angel's staff, as an entry in DMZ's KI WEAPON quick menu rather than on a keybind of its own.
 *
 * <p>It belongs there because that is where a player already goes to bring out a ki weapon; a separate key would be
 * one more binding to learn for something the game already has a place for.
 *
 * <h2>How the client knows to show it</h2>
 * {@link #visible} reads {@link EnergyClientCache}, which the server already pushes for the HUD bar. The client is
 * therefore never told "you are the Angel" as a separate fact, and cannot be made to show the entry by editing
 * anything local that the server does not agree with.
 *
 * <h2>The click is a request, not a command</h2>
 * Selecting sends an EMPTY packet. The server decides whether to summon, re-checking the live title, so a client
 * that shows this entry when it should not still gets nothing.
 */
public final class AngelStaffRadialNode extends AbstractRadialNode
{
    /** DMZ's own placeholder icon: the staff has no radial icon art, and a missing texture reads worse. */
    private static final ResourceLocation ICON = PLACEHOLDER;

    @Override
    public Component label(StatsData stats)
    {
        return Component.literal("Angel's Staff").withStyle(ChatFormatting.AQUA);
    }

    @Override
    public ResourceLocation icon(StatsData stats)
    {
        return ICON;
    }

    /** Only the current Angel sees it, decided from the bar the server already syncs. */
    @Override
    public boolean visible(StatsData stats)
    {
        // The Angel is a Ragnarok Key role (feature roles); the synced bar already says so, this is belt and braces.
        return net.shurui.dev.sdu.api.ClientGate.feature("roles") && EnergyClientCache.kind() == EnergyKind.ANGELIC;
    }

    @Override
    public boolean active(StatsData stats)
    {
        return visible(stats);
    }

    @Override
    public void onSelect(StatsData stats)
    {
        playClick();
        NetworkUtils.INSTANCE.sendToServer(new PacketAngelStaffToggle());
    }
}
