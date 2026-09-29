package net.shurui.shuruisutilities.guilds;

import net.shurui.shuruisutilities.api.key.GuildHooks;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.model.GuildPermission;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

/**
 * Guild territory rules inside claimed chunks: build/destroy/container/interaction gating by role permission, PvP
 * safety, explosion and hostile-mob suppression per the territory's flags.
 *
 * <p>A FACADE since S15: the event handlers moved into the Ragnarok Key (its territory handler, registered by the
 * key's Guilds module) and these statics answer through {@link GuildHooks}, so the block-break guard and the
 * build-dimension protection keep calling them unchanged. Keyless nothing is claimed: {@link #protecting()} is false,
 * nobody holds the bypass and {@link #canAffect} allows everything. {@link #isProtectedNpc} is a pure entity rule and
 * stays here.
 */
public class GuildProtectionHandler
{
    private GuildProtectionHandler() {}

    public static boolean protecting()
    {
        return GuildHooks.get().protecting();
    }

    public static boolean hasBypass(Player player)
    {
        return GuildHooks.get().hasBypass(player);
    }

    /** Whether {@code player} may perform an action of category {@code perm} at a chunk owned by {@code owner}. */
    public static boolean canAffect(Player player, Guild owner, GuildPermission perm)
    {
        return GuildHooks.get().canAffect(player, owner, perm);
    }

    /**
     * What counts as an NPC here.
     *
     * <p>Villagers and traders, plus the NPCs the suite and CustomNPCs add, which are recognised by their
     * REGISTRY NAMESPACE rather than by their class. That is deliberate: naming
     * {@code noppes.npcs.entity.EntityNPCInterface} here would classload an optional mod outside a
     * {@code ModList} guard, which is exactly what the compat rule forbids, and a namespace check needs no
     * class at all.
     *
     * <p>Anything hostile is excluded whatever its namespace, so a saga enemy or an aggressive custom npc
     * standing on claimed land can still be fought.
     *
     * <p>Public so any caller that needs the same yes/no shares this one namespace rule rather than keeping a
     * parallel copy that could drift from it (the build-dimension protection and the key's territory handler).
     */
    public static boolean isProtectedNpc(LivingEntity entity)
    {
        if (entity instanceof Player || entity instanceof Enemy)
            return false;
        if (entity instanceof AbstractVillager)
            return true;
        net.minecraft.resources.ResourceLocation id =
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id == null)
            return false;
        String ns = id.getNamespace();
        return ns.equals("customnpcs") || ns.equals("cnpcgeckoaddon")
                || ((ns.equals("dmz_ragnarok") || ns.equals("dragonminez")) && id.getPath().contains("npc"));
    }
}
