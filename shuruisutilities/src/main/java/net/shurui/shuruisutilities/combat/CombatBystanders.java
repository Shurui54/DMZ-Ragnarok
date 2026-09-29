package net.shurui.shuruisutilities.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.sdu.api.SpaceDefenderNpc;
import net.shurui.dev.sdu.api.SpaceTownNpc;
import net.shurui.dev.sdu.entity.ShenronDisplayEntity;
import net.shurui.shuruisutilities.compat.customnpcs.CnpcCombat;
import net.shurui.shuruisutilities.npcregion.NpcRegionManager;
import net.shurui.shuruisutilities.prestige.PrestigeNpcs;
import net.shurui.shuruisutilities.ragnarok.RgNpcEntity;

/**
 * Who a dash or a sonic crash must hit but must NOT shove.
 *
 * <p>The combat layer's dash and sonic crash both end their run with a knockback, which is exactly right for a
 * fighter and exactly wrong for a standing quest giver or a shopkeeper: knocked once, a non-combat NPC has no way
 * back to where it stood, so a player can walk it off a platform, out of a shop, or into a hole and strand it there.
 * DragonMineZ's Old Kai is the worst case, since a trapped master can lock a whole progression path.
 *
 * <p>This is a deliberately narrow allow-of-immunity: a real enemy or combat mob is never in here, so dashing into
 * something you are meant to fight still throws it. It only names the entities that exist to stand still.
 */
public final class CombatBystanders
{
    private CombatBystanders() {}

    /**
     * {@code true} when this entity is a non-combat NPC that a dash or crash may hit but must not displace.
     *
     * <p>Covered, precisely and nothing more:
     * <ul>
     *   <li>DragonMineZ masters and quest givers ({@code MastersEntity}, which {@code QuestNPCEntity} extends), the
     *       Old Kai / Popo / master population. These already refuse a player attack, so a dash never knocked them,
     *       but a sonic crash shoves unconditionally, which would fling one anyway.</li>
     *   <li>SU region NPCs stamped non-hostile: behaviour 0 Passive, 1 Wander, 2 Dialogue, 3 Follower, 4 Guard. The
     *       ordinal is read from persistent data, so this is class agnostic and covers sdu's {@code dmz_fighter}
     *       editor NPCs and anything else SU spawns. 5 Hostile and 6 DMZ Fighter are real combatants and stay
     *       throwable.</li>
     *   <li>Any no-AI mob: it is a stationary display or statue piece by definition and could never path back.</li>
     *   <li>Any invulnerable entity: it refuses the hit, so it must refuse the shove too, which also keeps a sonic
     *       crash consistent with the dash (whose knockback was already gated on the hit landing).</li>
     *   <li>Custom NPCs configured not to fight back (shopkeepers, dialogue, quest givers), via the compat guard.
     *       A Custom NPC set up as an enemy still retaliates and still gets thrown.</li>
     *   <li>The suite's own standing NPCs that exist to be talked to, not fought: the ragnarok display NPC
     *       ({@code RgNpcEntity}), the two Saiyan town service NPCs ({@code SaiyanTraderEntity} and
     *       {@code PlanetSaiyanCitizenEntity}), sdu's shenron display ({@code ShenronDisplayEntity}), and the raid and
     *       tournament host NPCs matched by entity id below. Their combatant cousins are deliberately absent:
     *       {@code RgNpcFighterEntity} and {@code SduDmzFighter} both extend DragonMineZ's saga chassis, not these
     *       display classes, so a plain {@code instanceof} leaves them throwable.</li>
     *   <li>The planet garrison defenders ({@code PlanetDefenderEntity} and {@code PlanetGarrisonDefenderEntity}).
     *       They fight, but they are anchored to a planet and knocking one off it strands it, so the shield's shove is
     *       wrong for them even though a real attack is not.</li>
     *   <li>Prestige NPCs, by their {@code SuPrestige} persistent tag via {@code PrestigeNpcs.isPrestigeNpc}.</li>
     * </ul>
     */
    public static boolean isProtected(LivingEntity entity)
    {
        if (entity == null)
            return false;

        // DragonMineZ is mandatory, so naming its class directly is allowed. QuestNPCEntity extends MastersEntity,
        // so this one test covers both the masters and the quest givers.
        if (entity instanceof com.dragonminez.common.init.entities.MastersEntity)
            return true;

        // The suite's own standing NPCs, matched by class. These are the cheap tests, so they run before any
        // persistent-data or registry read. RgNpcFighterEntity and SduDmzFighter extend DBSagasEntity, not these
        // display classes, so a real fighter never lands here.
        if (entity instanceof RgNpcEntity
                || entity instanceof SpaceTownNpc
                || entity instanceof ShenronDisplayEntity)
            return true;

        // The planet garrison defenders are combatants, but they are pinned to a planet: a shove that carries one off
        // it strands it, so the shield must not move them even though an attack may still hit them. Matched through the
        // core SpaceDefenderNpc marker, so this never names the Space module's entity classes; when Space is absent no
        // entity implements it and this simply never matches.
        if (entity instanceof SpaceDefenderNpc)
            return true;

        // Raid and tournament host NPCs live in the peer addon trees, so match them by registered entity id rather
        // than classloading their classes, the way GuildProtectionHandler and NpcAnchors deliberately do. Since the
        // five addons merged into one jar these types register under the "dmz_ragnarok" namespace, not their old
        // per-addon ones. The tournament type covers both the signup host (SdtTournament) and the browse host
        // (SdtBrowse), which are the same entity type stamped with different tags.
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (typeId != null && typeId.getNamespace().equals("dmz_ragnarok"))
        {
            String path = typeId.getPath();
            if (path.equals("raid_npc") || path.equals("tournament_npc"))
                return true;
        }

        // Prestige NPCs, stamped with the SuPrestige persistent tag. Class agnostic, so this reads persistent data.
        if (PrestigeNpcs.isPrestigeNpc(entity))
            return true;

        if (entity instanceof Mob mob)
        {
            CompoundTag pd = mob.getPersistentData();
            if (pd.contains(NpcRegionManager.TAG_BEHAVIOR))
            {
                int behavior = pd.getInt(NpcRegionManager.TAG_BEHAVIOR);
                // 0..4 are the non-hostile behaviours; 5 Hostile and 6 DMZ Fighter are meant to be fought.
                if (behavior >= 0 && behavior <= 4)
                    return true;
            }
            // A no-AI mob cannot walk back to where it was, so a shove is always permanent for it.
            if (mob.isNoAi())
                return true;
        }

        // Refuses the hit, so it must refuse the shove. Cheap and covers display/quest pieces that are neither a
        // master nor SU-spawned.
        if (entity.isInvulnerable())
            return true;

        // Custom NPCs that will not fight back. retaliates() returns null when CustomNPCs is absent or the entity is
        // not one of its NPCs, so a non-NPC or a keyless server simply falls through to "not protected here".
        if (Boolean.FALSE.equals(CnpcCombat.retaliates(entity)))
            return true;

        return false;
    }
}
