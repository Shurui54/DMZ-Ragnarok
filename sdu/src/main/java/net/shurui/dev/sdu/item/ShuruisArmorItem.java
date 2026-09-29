package net.shurui.dev.sdu.item;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.shurui.dev.sdu.client.model.ShuruisArmorModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

// creative-only, whitelist-locked cosmetic armor set (chest/legs/boots, no helmet). invincible while worn,
// only whitelisted UUIDs may keep a piece equipped; see ShuruisArmorHandler. rendering mirrors DMZ's
// DbzArmorItem: armor layer texture overridden via getArmorTexture, LEGS on layer_2, CHEST/FEET on layer_1.
public class ShuruisArmorItem extends ArmorItem {

    private static final String LAYER_1 = "dmz_ragnarok:textures/models/armor/shuruis_armor_layer_1.png";
    private static final String LAYER_2 = "dmz_ragnarok:textures/models/armor/shuruis_armor_layer_2.png";

    public ShuruisArmorItem(Type type, Properties properties) {
        super(ShuruisArmorMaterial.SHURUIS_ARMOR, type, properties);
    }

    // type arg is the dyeable overlay layer; we have none, so ignore it. LEGS -> inner layer (layer 2),
    // everything else -> outer layer (layer 1).
    @Nullable
    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return slot == EquipmentSlot.LEGS ? LAYER_2 : LAYER_1;
    }

    // render on our custom 64x64 model, not the vanilla one (textures are authored for this model's UVs so
    // vanilla would garble them). mirrors DMZ's DbzArmorItem#initializeClient; baked model cached per instance.
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private ShuruisArmorModel model;

            @NotNull
            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack itemStack,
                                                          EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
                if (this.model == null) {
                    this.model = new ShuruisArmorModel(
                            Minecraft.getInstance().getEntityModels().bakeLayer(ShuruisArmorModel.LAYER_LOCATION));
                }
                return this.model;
            }
        });
    }

    public static boolean isShuruisArmor(@Nullable Item item) {
        return item instanceof ShuruisArmorItem;
    }

    public static boolean isPiece(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && isShuruisArmor(stack.getItem());
    }
}
