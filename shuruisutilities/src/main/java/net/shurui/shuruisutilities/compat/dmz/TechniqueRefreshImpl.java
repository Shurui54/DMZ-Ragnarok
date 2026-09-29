package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import com.dragonminez.common.stats.techniques.TechniqueData;
import com.dragonminez.common.stats.techniques.Techniques;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.dragons.DragonMove;
import net.shurui.shuruisutilities.god.RoleMove;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/** The only class naming DMZ types for rewriting a player's stored technique copies. See {@link TechniqueRefresh}. */
final class TechniqueRefreshImpl
{
    private TechniqueRefreshImpl() {}

    /**
     * What one upgrade level is worth, matching {@code UpgradeTechniqueC2S} exactly.
     *
     * <p>DMZ's upgrades bake themselves into the stat AND bump a level counter, so re-applying the definition would
     * silently refund every upgrade the player bought unless the gains are added back on top of the new base. Read
     * from DMZ's own upgrade handler rather than guessed; cooldown and cast time are level-only there (they are
     * applied as multipliers at use time) and so need nothing here.
     */
    private static final float DAMAGE_PER_LEVEL = 0.05f;
    private static final float SIZE_PER_LEVEL = 0.1f;
    private static final float SPEED_PER_LEVEL = 0.05f;
    private static final int ARMOR_PEN_PER_LEVEL = 1;

    static int refreshAll(ServerPlayer player)
    {
        try
        {
            StatsData stats = DmzBridge.stats(player);
            if (stats == null || stats.getTechniques() == null)
                return 0;
            Techniques techniques = stats.getTechniques();

            int changed = 0;
            for (String id : ourTechniqueIds())
                if (refreshOne(techniques, id))
                    changed++;

            if (changed > 0)
            {
                // Without this the client's skill menu keeps showing the old row until the next relog, because
                // rewriting the server-side map sends nothing on its own.
                NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(player), player);
                LoggingHandler.sulog.info("[dragons] refreshed {} stored technique definitions for {}",
                        changed, player.getName().getString());
            }
            return changed;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[dragons] technique refresh failed: {}", t.toString());
            return 0;
        }
    }

    private static List<String> ourTechniqueIds()
    {
        List<String> ids = new ArrayList<>();
        for (DragonMove move : DragonMove.values())
            ids.add(move.id);
        for (RoleMove move : RoleMove.values())
            ids.add(move.id);
        // The mini clone technique: keeping it here means a later definition edit (colours, cooldown, ki type)
        // reaches players who already unlocked it, since DMZ never re-reads the registry for a stored copy.
        ids.add(net.shurui.shuruisutilities.clone.MiniClone.TECHNIQUE_ID);
        return ids;
    }

    /** @return true when the stored copy differed from the current definition and was replaced. */
    private static boolean refreshOne(Techniques techniques, String id)
    {
        TechniqueData stored = techniques.getUnlockedTechniques().get(id);
        if (!(stored instanceof KiAttackData old))
            return false; // not unlocked (or somehow a strike): nothing of ours to correct
        KiAttackData current = PredefinedTechniques.REGISTRY.get(id);
        if (current == null)
            return false;

        // A DEEP COPY, never the registry instance itself. unlockTechnique stores the reference it is handed, so
        // handing it the registry object would let one player's upgrades edit the definition every other player
        // gets.
        KiAttackData fresh = new KiAttackData();
        fresh.load(current.save());

        carryProgression(old, fresh);

        // Nothing to do when the stored copy already says the same thing; skipping keeps the login path free of a
        // pointless progression packet for every player on every join.
        CompoundTag before = old.save();
        CompoundTag after = fresh.save();
        if (before.equals(after))
            return false;

        techniques.unlockTechnique(fresh);
        return true;
    }

    /** Keep everything the PLAYER earned, and re-apply their upgrade gains on top of the new base values. */
    private static void carryProgression(KiAttackData old, KiAttackData fresh)
    {
        fresh.setExperience(old.getExperience());

        fresh.setDamageLevel(old.getDamageLevel());
        fresh.setSizeLevel(old.getSizeLevel());
        fresh.setSpeedLevel(old.getSpeedLevel());
        fresh.setArmorPenLevel(old.getArmorPenLevel());
        fresh.setCooldownLevel(old.getCooldownLevel());
        fresh.setCastTimeLevel(old.getCastTimeLevel());

        fresh.setDamageMultiplier(fresh.getDamageMultiplier() + DAMAGE_PER_LEVEL * old.getDamageLevel());
        fresh.setSize(fresh.getSize() + SIZE_PER_LEVEL * old.getSizeLevel());
        fresh.setSpeed(fresh.getSpeed() + SPEED_PER_LEVEL * old.getSpeedLevel());
        fresh.setArmorPenetration(fresh.getArmorPenetration() + ARMOR_PEN_PER_LEVEL * old.getArmorPenLevel());
    }
}
