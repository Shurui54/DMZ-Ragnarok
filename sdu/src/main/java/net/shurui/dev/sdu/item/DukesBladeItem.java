package net.shurui.dev.sdu.item;

import java.util.List;

import com.dragonminez.common.init.item.tools.ToolTiers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The signature drop from Duke Snipperjack (the pack's {@code DS_DukesBlade}). Given DragonMineZ's own sword
 * treatment so it swings and hits like a DMZ weapon rather than leaving the player throwing punches.
 *
 * <h2>Why a SwordItem</h2>
 * DMZ decides how a held item is gripped and animated in {@code com.dragonminez.client.render.util.WeaponGripProfile}:
 * an {@code item instanceof SwordItem} resolves to the SWORD grip (the swing/attack animation), anything else falls
 * to DEFAULT (the bare-hand punch). DMZ's own swords (DimensionalSwordItem, ZSwordItem, ...) all extend
 * {@code com.dragonminez.common.init.item.weapons.WeaponItem}, which is itself just a {@link SwordItem} built on
 * {@link ToolTiers#BLANK_WEAPON_TIER}. We reproduce that behaviour here (rather than extend {@code WeaponItem}) so we
 * keep our own SDU {@link net.minecraft.world.item.Item.Properties} (stack size, EPIC rarity, fire resistance) and
 * our own name and lore, while sitting on the EXACT tier DMZ's swords use, so it behaves and animates identically.
 *
 * <h2>Damage</h2>
 * Blank tier contributes no positive attack-damage bonus, so the hit comes from the {@code attackDamage} modifier,
 * the same way DMZ scales its swords (dimensional_sword uses 250, brave_sword 75, yajirobe_katana 12). As a boss drop
 * the blade sits between the utility and endgame DMZ swords, retunable in one place via {@link #DEFAULT_ATTACK_DAMAGE}.
 * All hits are ordinary player melee, so they pass through DMZ's damage pipeline (ki, defence, the lot) exactly as
 * DMZ's own swords do. Blank tier ships zero durability, so like the pack's Unbreakable blade it never wears out.
 *
 * <h2>Sounds</h2>
 * The pack's {@code DS_Items.yml} gives the blade a distinctive {@code item.trident.throw} on both swing and hit; we
 * prefer that over the silent DMZ default so it reads as this specific weapon.
 */
public class DukesBladeItem extends SwordItem {

    /** Boss-drop attack damage, between DMZ's utility swords and its endgame ones. The one place to retune it. */
    public static final int DEFAULT_ATTACK_DAMAGE = 120;
    /** Matches DimensionalSwordItem's swing speed (-2.4), a heavy, deliberate blade. */
    public static final float ATTACK_SPEED = -2.4f;

    public DukesBladeItem(Properties properties) {
        super(ToolTiers.BLANK_WEAPON_TIER, DEFAULT_ATTACK_DAMAGE, ATTACK_SPEED, properties);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        // The pack's onAttack sound. hurtEnemy is the melee-connect hook, so the damage itself is dealt by the normal
        // player attack (through DMZ's pipeline); we only add the weapon's voice here.
        Level level = attacker.level();
        if (!level.isClientSide()) {
            level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                    SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0f, 1.3f);
        }
        return super.hurtEnemy(stack, target, attacker);
    }

    @Override
    public boolean onEntitySwing(ItemStack stack, LivingEntity entity) {
        // The pack's onSwing sound: play on any swing, server-side so it is heard once by everyone nearby.
        Level level = entity.level();
        if (!level.isClientSide()) {
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                    SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 0.8f, 1.3f);
        }
        return super.onEntitySwing(stack, entity);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("The blade wielded by Duke Snipperjack.").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("An ominous aura still clings to its weathered edge.").withStyle(ChatFormatting.AQUA));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
