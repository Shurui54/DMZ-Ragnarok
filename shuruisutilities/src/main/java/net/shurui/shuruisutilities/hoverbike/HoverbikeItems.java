package net.shurui.shuruisutilities.hoverbike;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

// the four hoverbike spawn items (hoverbike_1..4), each a HoverbikeItem for the matching variant
public final class HoverbikeItems
{
    private HoverbikeItems() {}

    public static final DeferredRegister<Item> REGISTER =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    // index 0 unused; 1-4 = per-variant items so BIKES[variant] reads naturally
    @SuppressWarnings("unchecked")
    public static final RegistryObject<Item>[] BIKES = new RegistryObject[5];

    // the space-pod chip lives here (not a new register) so it rides the same mod-bus REGISTER hookup and shares
    // the "hoverbike" curios slot. it is a PodChipItem, not a HoverbikeItem: different vehicle, different deploy.
    public static final RegistryObject<Item> POD_CHIP = REGISTER.register("space_pod_chip",
            () -> new net.shurui.shuruisutilities.spacepod.PodChipItem(new Item.Properties().stacksTo(1)));

    // the nimbus chip lives here too (same register, same "hoverbike" curios slot + keybind). one chip: which DMZ
    // nimbus entity it summons (flying vs black) is decided from the player's alignment at deploy time.
    public static final RegistryObject<Item> NIMBUS_CHIP = REGISTER.register("nimbus_chip",
            () -> new net.shurui.shuruisutilities.nimbus.NimbusChipItem(new Item.Properties().stacksTo(1)));

    // the time machine chip lives here too (same register, same "hoverbike" curios slot + keybind). deploys SU's own
    // rideable, flying TimeMachineEntity, which enables space travel like the space pod.
    public static final RegistryObject<Item> TIME_MACHINE_CHIP = REGISTER.register("time_machine_chip",
            () -> new net.shurui.shuruisutilities.timemachine.TimeMachineChipItem(new Item.Properties().stacksTo(1)));

    static
    {
        for (int v = 1; v <= 4; v++)
        {
            final int variant = v;
            BIKES[v] = REGISTER.register("hoverbike_" + v,
                    () -> new HoverbikeItem(variant, new Item.Properties().stacksTo(1)));
        }
    }
}
