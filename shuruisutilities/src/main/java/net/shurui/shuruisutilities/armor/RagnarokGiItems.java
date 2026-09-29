package net.shurui.shuruisutilities.armor;

import com.dragonminez.common.init.armor.DbzArmorCapeItem;
import com.dragonminez.common.init.armor.DbzArmorItem;

import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The Ragnarok Gi: three pieces (chest, legs, boots), rendered by DragonMineZ's own armour pipeline so it sits on
 * the wearer like a skin, with a cape on the chest piece.
 *
 * <h2>Why these extend DMZ's armour classes instead of plain {@code ArmorItem}</h2>
 *
 * DMZ replaces the player renderer entirely ({@code DMZPlayerRenderer}), so a layer added to the vanilla
 * {@code PlayerRenderer} is simply never drawn for a DMZ player, and a vanilla-shaped armour model is not what
 * DMZ paints either. DMZ instead re-renders the wearer's own body geo, inflated and re-textured, which is exactly
 * the "like a skin" look. {@code DbzArmorItem} and {@code DbzArmorCapeItem} both take a {@code (modId, itemId)}
 * pair for precisely this purpose: they are DMZ's supported way for an addon to hand it armour to draw. So rather
 * than reimplement DMZ's renderer, these pieces join it.
 *
 * <p>The cape follows from the chest piece being a {@code DbzArmorCapeItem} and nothing else.
 * {@code DMZCapeLayer} runs on the {@code armorBody} bone, reads the wearer's chest stack (honouring Cosmetic
 * Armor if present) and draws its cape whenever that stack {@code instanceof DbzArmorCapeItem}. It supplies the
 * geometry ({@code dragonminez:geo/armor/armorcape.geo.json}) and the sway
 * ({@code dragonminez:animations/armorcape.animation.json}), so the cape animates like DMZ's rather than hanging
 * stiff. That is why the supplied {@code armorcape.geo.json} is not shipped: DMZ's cape model hardcodes its own
 * geo path, and the file we were given is a variant of that same DMZ model.</p>
 *
 * <h2>Texture paths are DMZ's convention, not ours</h2>
 *
 * {@code ArmorTextureResolver} builds {@code <modId>:textures/armor/<itemId>_layer1.png} (and {@code _layer2} for
 * the leggings slot, plus {@code _damaged_} variants it falls back from). With modId {@code dmz_ragnarok} and
 * itemId {@code ragnarok_gi} that is exactly where the two textures ship. Note {@code _layer1}, with no
 * underscore before the digit: that is DMZ's spelling, and the resolver does no searching, so a file named any
 * other way is simply not found and the armour renders untextured.
 *
 * <p>The cape reads the SAME {@code _layer1} texture as the chest, from the cape's own UV region (rows 32 to 52
 * of the 64x64 UV space, outer face on the left half and inner face on the right). That is why the cape colour
 * lives in the chest texture rather than in a file of its own.</p>
 */
public final class RagnarokGiItems
{
    private RagnarokGiItems() {}

    /** DMZ resolves our textures from this pair. Changing either renames the files it looks for. */
    public static final String MOD_ID = ShuruisUtilities.MODID;
    public static final String ITEM_ID = "ragnarok_gi";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    /** Chest: the cape piece. A {@code DbzArmorCapeItem} is the whole reason the cape is drawn. */
    public static final RegistryObject<Item> CHESTPLATE = ITEMS.register("ragnarok_gi_chestplate",
            () -> new DbzArmorCapeItem(RagnarokGiMaterial.RAGNAROK_GI, ArmorItem.Type.CHESTPLATE, props(),
                    MOD_ID, ITEM_ID));

    public static final RegistryObject<Item> LEGGINGS = ITEMS.register("ragnarok_gi_leggings",
            () -> new DbzArmorItem(RagnarokGiMaterial.RAGNAROK_GI, ArmorItem.Type.LEGGINGS, props(),
                    MOD_ID, ITEM_ID));

    public static final RegistryObject<Item> BOOTS = ITEMS.register("ragnarok_gi_boots",
            () -> new DbzArmorItem(RagnarokGiMaterial.RAGNAROK_GI, ArmorItem.Type.BOOTS, props(),
                    MOD_ID, ITEM_ID));

    /** Whether this stack is any piece of the gi. */
    public static boolean isPiece(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return false;
        Item item = stack.getItem();
        return item == CHESTPLATE.orElse(null) || item == LEGGINGS.orElse(null) || item == BOOTS.orElse(null);
    }

    // A fresh Properties per piece: Item.Properties is mutable and stateful, so sharing one instance across
    // registrations lets a later piece inherit whatever an earlier one set.
    private static Item.Properties props()
    {
        return new Item.Properties().rarity(Rarity.UNCOMMON);
    }
}
