package net.shurui.shuruisutilities.prestige;

import com.dragonminez.common.events.DMZEvent;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

// Forge-bus handlers for the PUBLIC half of prestige (what a player has already earned), registered by core on every
// server, keyed or keyless:
//  - TPGainEvent: multiplies TP as it's earned by the player's combined prestige + su.tpgain bonus, so
//    every DMZ training-point source is boosted uniformly.
//  - login / respawn / dimension change: re-push the TP multiplier, the widened stat cap, the race locks and the
//    hard-difficulty gate to the client.
//  - the prestige name colour on the nameplate and in the tab list.
// The private half (S19b, in the Ragnarok Key: PrestigeFeature) adds the login reward ledger seed, the server-wide
// prestige floor and the level-kit backfill, and the prestige NPC right click that opens the prestige screens.
public class PrestigeEvents
{
    @SubscribeEvent
    public void onTpGain(DMZEvent.TPGainEvent event)
    {
        if (!(event.getPlayer() instanceof ServerPlayer sp))
            return;
        int pct = PrestigeManager.totalTpPct(sp);
        if (pct <= 0)
            return;
        int base = event.getTpGain();
        if (base <= 0)
            return;
        // Route through TpMath.scaleGain so this multiply joins the wide quest-reward accumulator when one is
        // active (a big saga reward otherwise saturates at Integer.MAX_VALUE here too, bug #970/#978); off that
        // path it is the same clamped int math as before (base * (1 + pct/100)).
        event.setTpGain(net.shurui.dev.sdu.util.TpMath.scaleGain(base, 1.0 + pct / 100.0));
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        // Seed the client's SU TP-gain multiplier so DMZ's TP Multiplier tooltip is correct from the start.
        if (event.getEntity() instanceof ServerPlayer sp)
            PrestigeManager.sendTpMult(sp);
    }

    /**
     * Prestige name colour on the nameplate and everywhere a display name is used.
     *
     * <p>Forge's own hook rather than anything of ours, because {@code getDisplayName} is asked for from a dozen
     * places we do not own. Left alone when no colour is configured, so a server that never sets one sees no
     * change at all.
     */
    @SubscribeEvent
    public void onNameFormat(PlayerEvent.NameFormat event)
    {
        if (!(event.getEntity() instanceof ServerPlayer sp))
            return;
        net.minecraft.ChatFormatting colour = PrestigeManager.colourOf(sp);
        if (colour != null)
            event.setDisplayname(event.getDisplayname().copy().withStyle(colour));
    }

    /** The same colour in the tab list, which asks for its name through a separate hook. */
    @SubscribeEvent
    public void onTabListName(PlayerEvent.TabListNameFormat event)
    {
        if (!(event.getEntity() instanceof ServerPlayer sp))
            return;
        net.minecraft.ChatFormatting colour = PrestigeManager.colourOf(sp);
        if (colour == null)
            return;
        // Team formatted, not the bare name: setting a tab display name opts the row out of vanilla's own
        // PlayerTeam.formatNameForTeam call, so starting from getName() would drop the scoreboard team prefix
        // and suffix that carry a title like [G.O.D.]. Same trap as ShardTabName.
        net.minecraft.network.chat.Component current = event.getDisplayName() != null
                ? event.getDisplayName()
                : net.minecraft.world.scores.PlayerTeam.formatNameForTeam(sp.getTeam(), sp.getName());
        event.setDisplayName(current.copy().withStyle(colour));
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        // Respawn rebuilds the client player (and can drop the SU cap sync); re-push so the widened prestige
        // cap survives death and the +stat buttons keep working.
        if (event.getEntity() instanceof ServerPlayer sp)
            PrestigeManager.sendTpMult(sp);
    }

    @SubscribeEvent
    public void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event)
    {
        // Dimension changes swap the client-side level and can lose the SU cap sync; re-push it.
        if (event.getEntity() instanceof ServerPlayer sp)
            PrestigeManager.sendTpMult(sp);
    }
}
