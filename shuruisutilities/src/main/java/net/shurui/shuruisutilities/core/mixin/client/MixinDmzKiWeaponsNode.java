package net.shurui.shuruisutilities.core.mixin.client;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.client.gui.radial.RadialNode;
import com.dragonminez.client.gui.radial.nodes.KiWeaponsNode;
import com.dragonminez.common.stats.StatsData;


/**
 * Adds the Angel's Staff to DMZ's ki weapon quick menu.
 *
 * <p>Appended to whatever DMZ built rather than replacing the list, so every ki weapon DMZ offers stays exactly where
 * it was and a future DMZ version adding more of them needs no change here.
 *
 * <p>The node hides itself for anyone who is not the Angel ({@code AngelStaffRadialNode.visible}), so the menu is
 * unchanged for every other player. Selecting it only sends a request; the server decides.
 *
 * <p>{@code require = 0}: a DMZ-targeting mixin must never harden the build against a DMZ version that moved this,
 * and losing the menu entry is a cosmetic loss, not a broken feature - the staff can still be summoned by any other
 * path we add.
 */
@Mixin(value = KiWeaponsNode.class, remap = false)
public abstract class MixinDmzKiWeaponsNode
{
    @Inject(method = "buildChildren", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$addAngelStaff(StatsData stats, CallbackInfoReturnable<List<RadialNode>> cir)
    {
        try
        {
            List<RadialNode> original = cir.getReturnValue();
            List<RadialNode> rebuilt = new ArrayList<>();

            // DROP DMZ'S OWN ENTRIES FOR OUR TYPES FIRST. buildChildren makes one KiWeaponNode per entry in
            // CombatConfig.getKiWeaponTypes(), and we widen that list so the server will actually summon our weapons
            // (see MixinDmzKiWeaponTypes). The side effect is that DMZ then generates a wheel entry for each of ours
            // too, so the staff appeared twice: once loose at the top level carrying the raw
            // skill.dragonminez.kiweapon.<type> key it has no translation for, and once as our own entry. Ours is
            // the one with a name and an icon, so DMZ's copy goes.
            for (RadialNode node : (original == null ? List.<RadialNode>of() : original))
            {
                if (su$isDuplicateOfOurs(node))
                    continue;
                rebuilt.add(node);
            }

            // The staff selects through DMZ's own SelectKiWeaponC2S, so it behaves exactly like blade, scythe and
            // clawlance.
            rebuilt.add(new net.shurui.shuruisutilities.client.radial.RagnarokKiWeaponNode(
                    net.shurui.shuruisutilities.god.RagnarokKiWeapon.ANGEL_STAFF));
            cir.setReturnValue(rebuilt);
        }
        catch (Throwable ignored)
        {
            // Never let an extra menu entry break the menu itself; DMZ's own list stands.
        }
    }

    /**
     * True for an entry DragonMineZ generated for one of OUR weapon types.
     *
     * <p>Ours are excluded explicitly rather than by type: a {@code RagnarokKiWeaponNode} now extends DMZ's
     * {@code KiWeaponNode}, so an {@code instanceof} check alone would throw away the very entries being added.
     */
    private static boolean su$isDuplicateOfOurs(RadialNode node)
    {
        if (!(node instanceof com.dragonminez.client.gui.radial.nodes.KiWeaponNode))
            return false;
        if (node instanceof net.shurui.shuruisutilities.client.radial.RagnarokKiWeaponNode)
            return false;
        String type = ((net.shurui.shuruisutilities.core.mixin.dmz.AccessorKiWeaponNode) node).su$getType();
        if (type == null)
            return false;
        for (net.shurui.shuruisutilities.god.RagnarokKiWeapon weapon
                : net.shurui.shuruisutilities.god.RagnarokKiWeapon.values())
        {
            if (weapon.type.equalsIgnoreCase(type))
                return true;
        }
        return false;
    }
}
