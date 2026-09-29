package net.shurui.shuruisutilities.guilds.raid.clone.client;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The client-side PUPPET for one guild-raid clone: a fake {@link RemotePlayer} slaved to a server-side driver's
 * position and rotation, existing only so DragonMineZ's player renderer (a mixin keyed on {@link
 * net.minecraft.client.player.AbstractClientPlayer}) draws the clone with the source member's TRUE appearance.
 * It is NEVER added to the {@link ClientLevel} entity system and is never ticked by the game: {@link
 * GuildRaidPuppetManager} positions it and renders it by hand, so it cannot interact with, collide with, or be
 * targeted by anything. It is pure decoration.
 *
 * <h2>Skin resolution without a tab-list entry</h2>
 * {@link net.minecraft.client.player.AbstractClientPlayer#getSkinTextureLocation()} resolves a {@link PlayerInfo}
 * from the client connection's tab-list map, which we cannot inject into without a server-sent player-info packet
 * (and we do not want to pollute the real tab list with fake players). Instead this puppet holds its OWN {@link
 * PlayerInfo}, built from the source member's {@link GameProfile}, and overrides the texture/model/cape lookups to
 * consult it. That {@link PlayerInfo} performs the normal Mojang session-server skin fetch for the member's real
 * name and UUID, so the puppet shows the member's actual skin, falling back to the default skin until it loads.
 *
 * <p>DragonMineZ's model itself reads race, colours, hair and active form off the puppet's {@link
 * com.dragonminez.common.stats.StatsData} capability (attached to every {@code Player}, so the puppet gets one),
 * which {@link GuildRaidPuppetManager} populates from the appearance capsule. The vanilla skin resolved here is
 * the base layer under DragonMineZ's tinted body layers.
 */
@OnlyIn(Dist.CLIENT)
public class GuildRaidPuppet extends RemotePlayer
{
    private final PlayerInfo ownInfo;

    public GuildRaidPuppet(ClientLevel level, GameProfile profile)
    {
        super(level, profile);
        // Build our own PlayerInfo so getSkinTextureLocation/getModelName can resolve the member's real skin without
        // needing the puppet in the client tab-list map. Guarded: if PlayerInfo construction ever throws, ownInfo is
        // left null and the overrides fall back to the default skin, never crashing the render.
        PlayerInfo info = null;
        try
        {
            info = new PlayerInfo(profile, false);
        }
        catch (Throwable ignored)
        {
            info = null;
        }
        this.ownInfo = info;
        this.noPhysics = true;
        this.setInvisible(false);
    }

    @Override
    public ResourceLocation getSkinTextureLocation()
    {
        if (ownInfo != null)
        {
            try
            {
                return ownInfo.getSkinLocation();
            }
            catch (Throwable ignored)
            {
                // fall through to default
            }
        }
        return DefaultPlayerSkin.getDefaultSkin(getUUID());
    }

    @Override
    public String getModelName()
    {
        if (ownInfo != null)
        {
            try
            {
                return ownInfo.getModelName();
            }
            catch (Throwable ignored)
            {
                // fall through to default
            }
        }
        return DefaultPlayerSkin.getSkinModelName(getUUID());
    }

    @Override
    public ResourceLocation getCloakTextureLocation()
    {
        if (ownInfo != null)
        {
            try
            {
                return ownInfo.getCapeLocation();
            }
            catch (Throwable ignored)
            {
                return null;
            }
        }
        return null;
    }

    /** Puppets are hand-rendered from a render event, never by the entity renderer distance test. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq)
    {
        return true;
    }

    /** Never let the fake puppet be considered invisible-to-team or spectator, so the aura/layers always draw. */
    @Override
    public boolean isSpectator()
    {
        return false;
    }

    @Override
    public boolean isCreative()
    {
        return false;
    }
}
