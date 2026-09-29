package net.shurui.dev.ragnarok.visualtest;

import java.util.List;

import com.dragonminez.common.config.CombatConfig;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.stats.character.Status;
import com.dragonminez.common.stats.skills.Skills;
import com.dragonminez.common.util.TransformationsHelper;
import com.dragonminez.server.util.MutantManager;
import com.mojang.logging.LogUtils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;

/**
 * The only class in the harness that names DragonMineZ types. It runs on the SERVER thread (invoked from the
 * runner's {@code server.execute} blocks) and reproduces DMZ's own character-creation sequence exactly as
 * {@code CreateCharacterC2S} does it, read from the 2.1.3 bytecode: {@code initializeWithRaceAndClass}, then the four
 * {@code TransformationsHelper} form selections, then a health reset and a {@code StatsSyncS2C} to self and trackers,
 * then {@code MutantManager.rollForPlayer}. Completing creation is what turns DMZ's custom player renderer on, which
 * is the layer through which the wardrobe cosmetics draw.
 *
 * <p>DragonMineZ is a mandatory dependency, so naming its classes directly here is safe (no {@code ModList} guard is
 * needed for a hard dep). This whole package is dev-and-client-only anyway.
 */
final class DmzCharacterSetup
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The skill DMZ checks to decide a ki weapon is drawn (PlayerAttackHelper.isKiWeaponActive). */
    private static final String KI_MANIPULATION = "kimanipulation";

    private DmzCharacterSetup() {}

    private static StatsData stats(ServerPlayer sp)
    {
        return sp.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
    }

    /** Complete DMZ character creation if the player has none, mirroring DMZ's own CreateCharacterC2S handler. */
    static void ensureCharacter(ServerPlayer sp)
    {
        StatsData data = stats(sp);
        if (data == null) return;
        Status status = data.getStatus();
        if (status.isHasCreatedCharacter())
            return;
        try
        {
            CustomHair hair = data.getCharacter().emptyHair();
            data.initializeWithRaceAndClass(
                    "human",                   // race
                    Character.CLASS_WARRIOR,   // class
                    Character.GENDER_MALE,     // gender
                    0,                          // hairId
                    hair,                       // customHair
                    0,                          // bodyType
                    0,                          // eyesType
                    0,                          // noseType
                    0,                          // mouthType
                    0,                          // tattooType
                    0.0F,                       // boobScale
                    "",                        // activeHeadBone
                    "#000000",                 // hairColor
                    "#FFFFFF",                 // bodyColor
                    "#FFFFFF",                 // bodyColor2
                    "#FFFFFF",                 // bodyColor3
                    "#000000",                 // eye1Color
                    "#000000",                 // eye2Color
                    "#00A2FF");                // auraColor
            Character ch = data.getCharacter();
            ch.setSelectedFormGroup(TransformationsHelper.getGroupWithFirstAvailableForm(data));
            ch.setSelectedForm(TransformationsHelper.getFirstAvailableForm(data));
            ch.setSelectedStackFormGroup(TransformationsHelper.getGroupWithFirstAvailableStackForm(data));
            ch.setSelectedStackForm(TransformationsHelper.getFirstAvailableStackForm(data));
            sp.refreshDimensions();
            sp.setHealth(sp.getMaxHealth());
            sync(sp);
            MutantManager.rollForPlayer(sp, data);
            LOGGER.info("[VisualTest] Completed DMZ character creation for the test player.");
        }
        catch (Throwable t)
        {
            LOGGER.error("[VisualTest] DMZ character creation failed; DMZ's renderer may be inactive.", t);
        }
    }

    /**
     * Draw or sheathe a DMZ ki weapon, matching the four conditions PlayerAttackHelper.isKiWeaponActive tests: an
     * empty main hand, the {@value #KI_MANIPULATION} skill active, a selected ki weapon type that is not "none", and
     * a combat-config entry for that type. This is DMZ's own gate, so a hand accessory follows exactly the same
     * signal DMZ's own weapon does.
     */
    static void setKiWeapon(ServerPlayer sp, boolean active)
    {
        StatsData data = stats(sp);
        if (data == null) return;
        try
        {
            Skills skills = data.getSkills();
            if (active)
            {
                try { skills.setSkillLevel(KI_MANIPULATION, 1); } catch (Throwable ignored) { }
                skills.setSkillActive(KI_MANIPULATION, true);
                String type = firstKiWeaponType();
                data.getStatus().setKiWeaponType(type);
                sp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            else
            {
                skills.setSkillActive(KI_MANIPULATION, false);
                data.getStatus().setKiWeaponType("none");
            }
            sync(sp);
        }
        catch (Throwable t)
        {
            LOGGER.warn("[VisualTest] Ki weapon toggle failed.", t);
        }
    }

    private static String firstKiWeaponType()
    {
        try
        {
            CombatConfig cfg = ConfigManager.getCombatConfig();
            List<String> types = cfg == null ? null : cfg.getKiWeaponTypes();
            if (types != null)
                for (String t : types)
                    if (t != null && !t.isBlank() && !t.equalsIgnoreCase("none"))
                        return t;
        }
        catch (Throwable ignored) { }
        return "scythe";
    }

    private static void sync(ServerPlayer sp)
    {
        try
        {
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(sp), sp);
        }
        catch (Throwable t)
        {
            LOGGER.debug("[VisualTest] StatsSyncS2C failed.", t);
        }
    }
}
