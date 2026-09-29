package net.shurui.shuruisutilities.guilds.client;

import net.minecraft.client.gui.screens.Screen;

import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * "Claim chunk" entry added to Xaero's World Map right-click menu (see the GuiMap mixin). Selecting it
 * claims the chunk that was right-clicked for the player's guild, via the {@code /guild claimat} command
 * (which runs the normal power/limit/overclaim checks server-side). Only loaded when Xaero's World Map is
 * present. Uses the player's current dimension.
 */
public class GuildClaimRightClickOption extends RightClickOption
{
    private final int chunkX;
    private final int chunkZ;

    public GuildClaimRightClickOption(int chunkX, int chunkZ, IRightClickableElement target)
    {
        super("Claim chunk", 9100, target);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        setActive(true);
    }

    @Override
    public void onAction(Screen screen)
    {
        GuildGuiClient.run("guild claimat " + chunkX + " " + chunkZ);
    }
}
