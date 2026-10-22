package net.shurui.dev.ragnarok.visualtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.dragonminez.common.combat.logic.weapon.WeaponRegistry;
import com.dragonminez.common.combat.weapon.WeaponAttributes;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceStatsConfig;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.swordsman.Swordsman;

import org.slf4j.Logger;

/**
 * Server-side checks for the konatsian scenario: the bundled race on disk and in DMZ, which items count as swords,
 * and the swordsman passive measured through DMZ's own melee damage event and attributes (same character, same STR, only the class
 * and the held item change between hits). Every method runs on the server thread and returns one index line.
 */
final class KonatsianProbe
{
    private static final Logger LOGGER = LogUtils.getLogger();

    static final String RACE = "tapion";
    private static final String DUMMY_TAG = "dmzr_vt_konats_dummy";

    private KonatsianProbe() {}

    private static StatsData stats(ServerPlayer sp)
    {
        return sp.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
    }

    /** The race folder SU extracted, and what DMZ loaded from it. */
    static String raceReport()
    {
        Path folder = FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve("races").resolve(RACE);
        StringBuilder sb = new StringBuilder();
        sb.append("folder=").append(folder).append(" exists=").append(Files.isDirectory(folder));
        for (String f : new String[] { "character.json", "stats.json", "forms/superforms.json", ".su_bundle", ".su_swordsman" })
            sb.append(' ').append(f).append('=').append(Files.exists(folder.resolve(f)));
        sb.append(" loaded=").append(ConfigManager.isRaceLoaded(RACE));
        RaceStatsConfig cfg = ConfigManager.getRaceStats(RACE);
        sb.append(" classes=").append(cfg == null ? "null" : new java.util.TreeSet<>(cfg.getAllClasses()));
        RaceStatsConfig human = ConfigManager.getRaceStats("human");
        sb.append(" humanHasSwordsman=").append(human != null && human.hasClass(Swordsman.CLASS_ID));
        var ch = ConfigManager.getRaceCharacter(RACE);
        if (ch != null)
            sb.append(" model=").append(ch.getCustomModel()).append(" racial=").append(ch.getRacialSkill())
                    .append(" skin=").append(ch.getDefaultBodyColor()).append(" hair=").append(ch.getDefaultHairColor());
        sb.append(" forms=").append(ConfigManager.getAllFormsForRace(RACE) == null ? "null" : ConfigManager.getAllFormsForRace(RACE).keySet());
        return sb.toString();
    }

    /** Which items the passive treats as a sword, with DMZ's weapon category for each. */
    static String swordReport()
    {
        StringBuilder sb = new StringBuilder();
        for (String id : new String[] { "minecraft:diamond_sword", "minecraft:wooden_sword", "dragonminez:brave_sword",
                "dragonminez:z_sword", "dragonminez:katana_yajirobe", "dragonminez:dimensional_sword",
                "dragonminez:tamagami_sword", "dragonminez:power_pole", "dragonminez:tamagami_trident",
                "dragonminez:tamagami_hammer", "minecraft:diamond_axe", "minecraft:stick" })
        {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id));
            if (item == null || item == net.minecraft.world.item.Items.AIR)
            {
                sb.append(id).append("=missing ");
                continue;
            }
            ItemStack s = new ItemStack(item);
            WeaponAttributes a = WeaponRegistry.getAttributes(s);
            sb.append(id).append('[').append(a == null ? "noweapon" : a.category()).append("]=")
                    .append(Swordsman.isSword(s)).append(' ');
        }
        return sb.toString().trim();
    }

    /** The created character's race and class, as the server holds it. */
    static String characterReport(ServerPlayer sp)
    {
        StatsData d = stats(sp);
        if (d == null)
            return "no stats";
        return "created=" + d.getStatus().isHasCreatedCharacter() + " race=" + d.getCharacter().getRaceName()
                + " class=" + d.getCharacter().getCharacterClass() + " gender=" + d.getCharacter().getGender()
                + " skin=" + d.getCharacter().getBodyColor() + " hairColor=" + d.getCharacter().getHairColor()
                + " swordsmanPassive=" + Swordsman.isSwordsman(d);
    }

    /** Clear the created flag so the client's creation screens can commit a new character through DMZ's packet. */
    static void uncreate(ServerPlayer sp)
    {
        StatsData d = stats(sp);
        if (d == null)
            return;
        d.getStatus().setHasCreatedCharacter(false);
        sync(sp);
    }

    /** A pig with 1024 max health and no AI, two blocks in front of the player, for the damage measurements. */
    static void spawnDummy(ServerPlayer sp)
    {
        removeDummy(sp);
        Mob pig = EntityType.PIG.create(sp.serverLevel());
        if (pig == null)
            return;
        pig.setNoAi(true);
        pig.setSilent(true);
        pig.setNoGravity(true);
        pig.addTag(DUMMY_TAG);
        var maxHp = pig.getAttribute(Attributes.MAX_HEALTH);
        if (maxHp != null)
            maxHp.setBaseValue(1024.0);
        pig.setHealth(pig.getMaxHealth());
        float yaw = sp.getYRot();
        double rad = Math.toRadians(yaw);
        pig.moveTo(sp.getX() - Math.sin(rad) * 2.0, sp.getY(), sp.getZ() + Math.cos(rad) * 2.0, yaw + 180F, 0F);
        sp.serverLevel().addFreshEntity(pig);
    }

    static void removeDummy(ServerPlayer sp)
    {
        for (LivingEntity e : sp.serverLevel().getEntitiesOfClass(LivingEntity.class, sp.getBoundingBox().inflate(32.0),
                e -> e.getTags().contains(DUMMY_TAG)))
            e.discard();
    }

    private static LivingEntity dummy(ServerPlayer sp)
    {
        var list = sp.serverLevel().getEntitiesOfClass(LivingEntity.class, sp.getBoundingBox().inflate(32.0),
                e -> e.getTags().contains(DUMMY_TAG));
        return list.isEmpty() ? null : list.get(0);
    }

    /** Set the class and held item for the next hit, with full stamina, then let the attack recharge. */
    static void prepare(ServerPlayer sp, String characterClass, String itemId)
    {
        StatsData d = stats(sp);
        if (d == null)
            return;
        d.getCharacter().setCharacterClass(characterClass);
        Item item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
        sp.setItemInHand(InteractionHand.MAIN_HAND, item == null ? ItemStack.EMPTY : new ItemStack(item));
        d.getResources().setCurrentStamina(d.getMaxStamina());
        sp.getPersistentData().putBoolean("dmz_first_hit", true);
        sync(sp);
    }

    /**
     * The swordsman numbers for the next hit, as DMZ computes them. A scripted Player.attack never lands here: DMZ's
     * ForgeCommonEvents.onPlayerAttack cancels any melee on an approved target unless its own combo state
     * (PlayerAttackHelper.getCurrentAttack) was set by a real swing. So the STR share is measured by posting the same
     * DMZEvent.DamageModifyEvent(MELEE) that CombatEvent posts with getMeleeDamage(), which is where DMZ applies the
     * class passive's outgoingDamageMultiplier; the weapon share and the swing speed are the vanilla attributes.
     */
    static String hit(ServerPlayer sp, String label)
    {
        StatsData d = stats(sp);
        LivingEntity target = dummy(sp);
        if (d == null || target == null)
            return label + ": no " + (d == null ? "stats" : "dummy");
        d.getResources().setCurrentStamina(d.getMaxStamina());
        sp.getPersistentData().putBoolean("dmz_first_hit", true);
        target.setHealth(target.getMaxHealth());
        target.invulnerableTime = 0;
        double atkSpeed = sp.getAttributeValue(Attributes.ATTACK_SPEED);
        double atkDamage = sp.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float charge = sp.getAttackStrengthScale(0.5F);
        double base = d.getMeleeDamage();
        var ev = new com.dragonminez.common.events.DMZEvent.DamageModifyEvent(sp, target, base, 0.0,
                com.dragonminez.common.events.DMZEvent.DamageSourceType.MELEE);
        boolean cancelled = net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(ev);
        double strPart = cancelled ? 0.0 : Math.max(0.0, ev.getAmount());
        String line = String.format(Locale.ROOT,
                "%s: class=%s held=%s sword=%s attackSpeed=%.3f attackDamageAttr=%.3f charge=%.2f dmzMelee=%.3f strPartAfterPassives=%.3f (x%.3f)",
                label, d.getCharacter().getCharacterClass(),
                ForgeRegistries.ITEMS.getKey(sp.getMainHandItem().getItem()), Swordsman.wieldingSword(sp),
                atkSpeed, atkDamage, charge, base, strPart, base > 0 ? strPart / base : 0.0);
        LOGGER.info("[VisualTest] konatsian {}", line);
        return line;
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
