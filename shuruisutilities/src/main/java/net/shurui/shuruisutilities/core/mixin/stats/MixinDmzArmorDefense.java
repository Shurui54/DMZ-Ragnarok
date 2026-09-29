package net.shurui.shuruisutilities.core.mixin.stats;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * Stops VANILLA armour from feeding DMZ's defence stat.
 *
 * <p>DMZ 2.1.3 builds {@code StatsData.getDefense()} as</p>
 *
 * <pre>
 *   ( secondaryAttr(DEFENSE) + (RES + bonusAdd) * defScale + bonusMul * defScale
 *     + armorPoints * 0.5 + armorToughness * 0.7 )
 *   * (powerRelease / 100) * secondaryEffectMultiplier("DEF")
 * </pre>
 *
 * <p>so a full set of plain vanilla armour hands over {@code 20 * 0.5 + 12 * 0.7 = 18.4} flat defence with no
 * runes and no RES behind it. That number then goes through DMZ's mitigation curve
 * ({@code defense*3 / (damage + defense*3)}), which is why an armoured player with a NEGATIVE resistance
 * modifier was still taking almost nothing: the armour term dwarfed their actual stat. The same two terms
 * appear in {@code getMaxDefense()}, so the screen's defence readout has to lose them too or the displayed
 * total and the damage taken stop agreeing.</p>
 *
 * <p>What this deliberately does NOT touch is the {@code secondaryAttr(DEFENSE)} term. That is DMZ's OWN
 * {@code MainAttributes.DEFENSE} attribute, which is how a rune or a piece of DMZ gear grants defence on
 * purpose. Only the blanket "any armour is DMZ defence" fold goes away.</p>
 *
 * <p>Vanilla's own armour reduction is already irrelevant here: DMZ's
 * {@code CombatEvent.overrideVanillaArmorReduction} runs at {@code LivingDamageEvent} LOWEST and REPLACES the
 * amount outright, recomputing it from the raw damage it stashed at {@code LivingHurtEvent}. So armour never
 * had a second, vanilla route into DMZ combat, and removing these two terms is the whole fix.</p>
 *
 * <p>{@code require = 0} per the DMZ-mixin contract: if a future DMZ reshapes these methods the injectors
 * no-op rather than failing class load, which means armour would quietly start counting again. This one has
 * to be re-checked in game after any DMZ bump, a green build proves nothing here.</p>
 */
@Mixin(targets = "com.dragonminez.common.stats.StatsData", remap = false)
public abstract class MixinDmzArmorDefense {

    /**
     * Zero the armour-points term in both defence calculations. The method name is DMZ's own so the injector
     * stays literal ({@code remap = false}), but {@code Player.getArmorValue} is VANILLA, so its {@code @At}
     * carries {@code remap = true} to be refmapped to {@code m_21230_} against the production jar.
     */
    @Redirect(
            method = { "getDefense()D", "getMaxDefense()D" },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getArmorValue()I",
                    remap = true),
            require = 0,
            remap = false
    )
    private int su$dropArmorPoints(Player player) {
        return 0;
    }

    /**
     * Zero the armour-toughness term. Injecting on DMZ's own private helper covers BOTH call sites
     * ({@code getDefense} and {@code getMaxDefense}) with one injector, and needs no remapping because
     * nothing vanilla is named. The helper has no other callers in DMZ 2.1.3.
     */
    @Inject(
            method = "getArmorToughnessValue()D",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void su$dropArmorToughness(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(0.0D);
    }
}
