package net.shurui.shuruisutilities.senzu;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Config + passive handlers for the senzu-farming feature. Three things live here:
 *
 * <ul>
 *   <li>the Senzu.toml config (growth pacing, cracked-harvest chance, golden-bean death-totem cooldown), read by
 *       {@link BeanPotBlockEntity} and {@link TypedBeanPotBlock} through the static accessors below;</li>
 *   <li>the golden-bean death-totem: a {@link LivingDeathEvent} handler that spends a golden bean to pull a player
 *       back from a lethal blow (see {@link #onDeath});</li>
 *   <li>bone-meal immunity for the pots (see {@link #onBonemeal}).</li>
 * </ul>
 *
 * <p>It is a standard SU {@link SUModule}: the module launcher instantiates it, auto-registers it on the Forge bus
 * (so the two {@code @SubscribeEvent} handlers below fire), and registers its config through the {@link ConfigLoaderBase}
 * path, exactly like {@link net.shurui.shuruisutilities.space.SpaceTravelModule}. All fields the crop code reads are
 * static, with sensible defaults baked in, so a pot works even before the config is baked at server start.
 */
@SUModule(name = "Senzu", parentMod = ShuruisUtilities.class, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class SenzuModule extends ConfigLoaderBase
{
    private static ForgeConfigSpec SENZU_CONFIG;
    private static final ConfigData data = new ConfigData("Senzu", SENZU_CONFIG, new ForgeConfigSpec.Builder());

    // total ticks for a plant to go from stage 0 to stage 3, split evenly across the three advances by the pot BE.
    // default 287000 ticks is roughly 10x vanilla wheat (wheat is about 28,700 ticks to run its seven advances on
    // hydrated farmland), i.e. about 4 hours of real time. That number is intentional, not a typo.
    private static int growthTicks = 287000;
    // chance a normal type's harvest rolls the WEAKER cracked variant instead of its base bean (BURNT/CRACKED ignore it).
    private static double crackedChance = 0.25;
    // golden-bean death-totem cooldown, in minutes, converted to game ticks when the cooldown is stamped.
    private static int goldenCooldownMinutes = 30;
    // shared reuse cooldown for the click-to-consume beans, in game ticks. Default 200 (10 seconds): long enough that a
    // bean is a deliberate combat cooldown, not a spammed full heal. Doubled from 100, because five seconds turned out
    // to be short enough that a fight was decided by how many beans each side had rather than by the fight. It is ONE
    // shared knob for every bean kind on purpose (see cooldownTicks()).
    private static int cooldownTicks = 200;
    // how many suite senzu beans (bean_senzu) Korin hands a player per claim. Default 4, the owner's ask.
    private static int korinBeanCount = 4;
    // real-life days between Korin handouts, per player. Default 7 (one real week). Measured on the wall clock, so it
    // counts server downtime and cannot be shortened by relogging (see KorinCooldowns).
    private static int korinCooldownDays = 7;

    private static ForgeConfigSpec.IntValue cfgGrowthTicks;
    private static ForgeConfigSpec.DoubleValue cfgCrackedChance;
    private static ForgeConfigSpec.IntValue cfgGoldenCooldownMinutes;
    private static ForgeConfigSpec.IntValue cfgCooldownTicks;
    private static ForgeConfigSpec.IntValue cfgKorinBeanCount;
    private static ForgeConfigSpec.IntValue cfgKorinCooldownDays;

    // per-plant total growth ticks; BeanPotBlockEntity divides this by three for the per-stage threshold.
    public static int growthTicks()
    {
        return growthTicks;
    }

    // 0..1 probability a harvest yields the cracked variant instead of the base bean.
    public static double crackedChance()
    {
        return crackedChance;
    }

    // configured golden-totem cooldown in minutes.
    public static int goldenCooldownMinutes()
    {
        return goldenCooldownMinutes;
    }

    // shared click-to-consume bean cooldown in game ticks, read by BeanItem#use. Deliberately ONE knob shared by all
    // bean kinds: eating any bean locks out every bean, so a player cannot chain one of each kind. If per-kind pacing
    // is ever wanted it should be a separate, explicitly requested change, not a knob invented here.
    public static int cooldownTicks()
    {
        return cooldownTicks;
    }

    // number of bean_senzu Korin grants per weekly claim, read by KorinRewardEvents.
    public static int korinBeanCount()
    {
        return korinBeanCount;
    }

    // real-life days a player must wait between Korin handouts, read by KorinRewardEvents.
    public static int korinCooldownDays()
    {
        return korinCooldownDays;
    }

    // per-player cooldown, stored on the PlayerPersisted sub-tag so it survives BOTH relog and death (Forge copies
    // that sub-compound onto the respawn clone). The value is an ABSOLUTE expiry measured in level game ticks
    // (Level#getGameTime), NOT System.currentTimeMillis(): game time only advances while the server is running, so a
    // server left stopped overnight never silently burns the cooldown while nobody is playing.
    private static final String COOLDOWN_TAG = "su_senzu_golden_until";
    // a cracked golden bean saves only this often; a full golden bean always saves. On a failed cracked roll the bean
    // is still spent and the cooldown still applies, so a cracked totem is a gamble, not a guaranteed life.
    private static final float CRACKED_SAVE_CHANCE = 0.75f;

    // the golden bean is a passive totem: on a lethal blow, if the player holds one anywhere and is off cooldown, the
    // death is cancelled, one bean is consumed, the player is fully restored (reusing SenzuEffects so there is one
    // restore definition), and the cooldown is stamped. HIGHEST priority so this wins BEFORE the grave handler at HIGH:
    // a saved player never dies, so their grave must never be cut.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeath(LivingDeathEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        // already used a totem recently: the bean does not fire at all and the player dies normally.
        if (onCooldown(player))
        {
            return;
        }

        // find a golden bean, preferring a FULL golden (a guaranteed save) over a cracked one (a 75% gamble), so a
        // player carrying both spends the more reliable bean first.
        Item full = SenzuRegistry.GOLDEN_BEAN.get();
        Item cracked = SenzuRegistry.CRACKED_GOLDEN_BEAN.get();
        int slot = findSlot(player, full);
        boolean isCracked = false;
        if (slot < 0)
        {
            slot = findSlot(player, cracked);
            isCracked = true;
        }
        if (slot < 0)
        {
            return; // no golden bean carried: die normally.
        }

        // a cracked golden bean only saves CRACKED_SAVE_CHANCE of the time. Whether it saves or not, the bean is spent
        // and the cooldown is stamped below, so a failed gamble still costs the bean and the cooldown window.
        boolean saved = !isCracked || player.level().getRandom().nextFloat() < CRACKED_SAVE_CHANCE;

        player.getInventory().getItem(slot).shrink(1);
        stampCooldown(player);

        if (!saved)
        {
            return; // cracked bean crumbled without saving: die normally, cooldown already spent.
        }

        // pull the player back: cancel the death and fully restore them (health + every DMZ pool + combat locks),
        // reusing the senzu-grade full restore so nothing about "what a full restore does" is duplicated here.
        event.setCanceled(true);
        SenzuEffects.healFull(player, 1.0f, true);
        celebrate(player);
    }

    // first inventory slot holding `item`, or -1. Scans the whole inventory (main + hotbar + offhand + armour) so the
    // totem fires wherever the bean is carried, matching the "anywhere in the inventory" rule.
    private static int findSlot(ServerPlayer player, Item item)
    {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
        {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item)
            {
                return i;
            }
        }
        return -1;
    }

    // feedback for a successful save: an action-bar line, the totem-use sound, and totem particles, all server-driven.
    // We deliberately do NOT send the vanilla ClientboundEntityEventPacket(35) totem animation: that packet also
    // consumes the client's held totem and plays its own item pop, which would be wrong here (the bean is elsewhere in
    // the inventory, not necessarily in hand).
    private static void celebrate(ServerPlayer player)
    {
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core.senzu_golden_saved"), true);
        if (player.level() instanceof ServerLevel level)
        {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.4, 0.6, 0.4, 0.25);
        }
    }

    private static boolean onCooldown(ServerPlayer player)
    {
        long until = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getLong(COOLDOWN_TAG);
        return until > 0 && player.level().getGameTime() < until;
    }

    private static void stampCooldown(ServerPlayer player)
    {
        long until = player.level().getGameTime() + (long) goldenCooldownMinutes * 60L * 20L;
        persisted(player).putLong(COOLDOWN_TAG, until);
    }

    // the PlayerPersisted compound, created + re-attached if absent (mirrors JailData / ShrinePlayerData).
    private static net.minecraft.nbt.CompoundTag persisted(Player player)
    {
        net.minecraft.nbt.CompoundTag root = player.getPersistentData();
        net.minecraft.nbt.CompoundTag pt = root.getCompound(Player.PERSISTED_NBT_TAG);
        if (!root.contains(Player.PERSISTED_NBT_TAG))
        {
            root.put(Player.PERSISTED_NBT_TAG, pt);
        }
        return pt;
    }

    // the pots grow ONLY on their own server ticker, never on random ticks or bone meal. Not implementing
    // BonemealableBlock already blocks vanilla bone meal, but Forge fires BonemealEvent BEFORE the BonemealableBlock
    // check, so a third-party mod that bone-meals through this event could otherwise force a pot to grow. Denying the
    // event for our pot blocks closes that gap. (AE2 growth accelerators are covered separately: they issue extra
    // vanilla random ticks, and the pots ignore random ticks entirely, so no handler is needed for those.)
    @SubscribeEvent
    public void onBonemeal(BonemealEvent event)
    {
        Block block = event.getBlock().getBlock();
        if (block instanceof BeanPotBlock || block instanceof TypedBeanPotBlock)
        {
            event.setResult(Event.Result.DENY);
        }
    }

    @Override
    public void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.push("Senzu");
        cfgGrowthTicks = BUILDER
                .comment("Total server ticks for a bean plant to grow from stage 0 to fully mature (stage 3), split evenly across the three stage advances. Default 287000 is roughly 10x vanilla wheat, about 4 hours of real time; it is intentionally large, not a typo.")
                .defineInRange("growthTicks", 287000, 20, 20000000);
        cfgCrackedChance = BUILDER
                .comment("Chance (0.0 to 1.0) that harvesting a pot yields the weaker cracked variant of its bean instead of the full-strength bean. Burnt and Cracked pots ignore this (they always drop their single bean).")
                .defineInRange("crackedChance", 0.25, 0.0, 1.0);
        cfgGoldenCooldownMinutes = BUILDER
                .comment("Cooldown in minutes between golden-bean death saves, per player. While on cooldown a carried golden bean does not fire and the player dies normally. 0 means no cooldown.")
                .defineInRange("goldenCooldownMinutes", 30, 0, 1440);
        cfgCooldownTicks = BUILDER
                .comment("Shared reuse cooldown in game ticks (20 ticks = 1 second) for the click-to-consume beans. Eating any bean locks out every bean kind for this long, so a player cannot chain one of each kind back to back. Default 200 is 10 seconds. 0 means no cooldown.")
                .defineInRange("cooldownTicks", 200, 0, 72000);
        cfgKorinBeanCount = BUILDER
                .comment("How many senzu beans (the suite's bean_senzu) Korin hands a player each claim. Default 4.")
                .defineInRange("korinBeanCount", 4, 1, 64);
        cfgKorinCooldownDays = BUILDER
                .comment("Real-life days a player must wait between Korin handouts, per player. Measured on the wall clock, so it counts server downtime. Default 7 (one real week). 0 means no cooldown (a claim every interaction).")
                .defineInRange("korinCooldownDays", 7, 0, 365);
        BUILDER.pop();
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        growthTicks = cfgGrowthTicks.get();
        crackedChance = cfgCrackedChance.get();
        goldenCooldownMinutes = cfgGoldenCooldownMinutes.get();
        cooldownTicks = cfgCooldownTicks.get();
        korinBeanCount = cfgKorinBeanCount.get();
        korinCooldownDays = cfgKorinCooldownDays.get();
    }

    @Override
    public ConfigData returnData()
    {
        return data;
    }
}
