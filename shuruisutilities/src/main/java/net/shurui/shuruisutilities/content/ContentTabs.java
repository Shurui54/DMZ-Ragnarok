package net.shurui.shuruisutilities.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.deletionwand.DeletionWandItems;
import net.shurui.shuruisutilities.hoverbike.HoverbikeItems;

/**
 * The five unified creative tabs, now owned by shuruisutilities. These used to live in the sdu addon.
 * Sibling addons (dungeons, tournaments, raid_bosses, sdu) insert their own items into these tabs via
 * BuildCreativeModeTabContentsEvent keyed on the shuruisutilities ResourceLocations; nothing outside this
 * addon needs to classload these fields.
 *
 * <p>SU's own items live directly in these tabs: the four hoverbikes in equipment, the deletion wand in
 * blocks_misc. The old shuruisutilities:hoverbikes fallback tab has been removed now that SU owns equipment.
 */
public final class ContentTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ShuruisUtilities.MODID);

    // SU's own PRIVATE items, listed once so the tabs below (and the optional JEI plugin) can leave them out on a
    // server without the key. Only what is LISTED changes; the items register everywhere and ContentGate still
    // decides whether they work. Runs at class load, which is mod construction (CREATIVE_MODE_TABS is registered
    // then), well before any tab or JEI asks. The public content (katchin, senzu, saibaman seeds, hoverbikes and the
    // chips, the Ragnarok Gi, runes, the time machine, space consoles, the Shenron idols) is deliberately absent.
    static
    {
        // licence-gated: the ticket and the ocarina, defiled balls, the ball bag, guild claim upgrades, the SU
        // crate blocks and keys (the Halloween crate included), the cosmetic shop items, the auction block, the
        // guild gravity chamber and the admin saibaman grow tool.
        net.shurui.dev.sdu.api.PrivateItems.register(null, () -> java.util.stream.Stream.of(
                        ContentItems.ITEMS.getEntries(),
                        net.shurui.shuruisutilities.corrupted.CorruptedBalls.ITEMS.getEntries(),
                        net.shurui.shuruisutilities.dragonballbag.DragonBallBagItems.REGISTER.getEntries(),
                        net.shurui.shuruisutilities.guilds.ClaimUpgradeItems.ITEMS.getEntries(),
                        net.shurui.shuruisutilities.crate.block.SuCrateBlocks.ITEMS.getEntries(),
                        net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticContentItems.ITEMS.getEntries(),
                        net.shurui.shuruisutilities.auction.AuctionRegistry.ITEMS.getEntries(),
                        net.shurui.shuruisutilities.gravitychamber.GuildGravityChamberBlocks.ITEMS.getEntries(),
                        java.util.List.of(net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.SAIBAMAN_GROW_TOOL))
                .flatMap(java.util.Collection::stream)
                .map(RegistryObject::get)
                .toList());
        // key-feature-gated: the angel staff belongs to the god roles, racing and the event token to their features.
        net.shurui.dev.sdu.api.PrivateItems.register(net.shurui.shuruisutilities.api.key.RoleHooks.FEATURE_ID,
                () -> java.util.List.of(net.shurui.shuruisutilities.god.AngelItems.ANGEL_STAFF.get()));
        net.shurui.dev.sdu.api.PrivateItems.register(net.shurui.shuruisutilities.api.key.RaceHooks.FEATURE_ID,
                () -> java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(
                                        net.shurui.shuruisutilities.racing.RaceRegistries.RACE_TRACK_WAND),
                                net.shurui.shuruisutilities.racing.RaceRegistries.BLOCK_ITEMS.stream())
                        .map(RegistryObject::get)
                        .toList());
        net.shurui.dev.sdu.api.PrivateItems.register(net.shurui.dev.sdu.api.key.EventHooks.FEATURE_ID,
                () -> java.util.List.of(ContentItems.EVENT_TOKEN.get()));
    }

    public static final RegistryObject<CreativeModeTab> GEMS_SOULS = CREATIVE_MODE_TABS.register("gems_souls",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + ShuruisUtilities.MODID + ".gems_souls"))
                    // Icon: sdu's first stat gem (stat buff token), which is always registered in every output and
                    // carries the pu* art of the removed placeholder gems. It replaced the old angel_gem placeholder
                    // icon, removed in the 2.0 debloat.
                    .icon(() -> net.shurui.dev.sdu.registry.ModItems.STAT_BUFF_TOKENS.get(0).get().getDefaultInstance())
                    .displayItems((params, tabOutput) -> {
                        // Private items drop out unless the connected server opened their gate (PrivateItems).
                        var output = net.shurui.dev.sdu.api.PrivateItems.filtered(tabOutput);
                        for (var item : ContentItems.GEMS_SOULS) {
                            output.accept(item.get());
                        }
                    })
                    .build());

    // Dragon balls: every real, working ball (the old cerulean placeholders were removed, see ContentItems).
    // The earth and namek balls and the earth radar are static items in DMZ, which is a
    // mandatory dependency, so they are always present. The black star, super and cerulean balls and their
    // radars are now registered from code (SU folds them into DMZ's dragon-ball bootstrap via
    // MixinDmzDragonBallBootstrap, replacing the old external <instance>/dragonballs/ pack), so they too are
    // normally present; they are still resolved by ResourceLocation at display time and skipped when absent
    // (see acceptDmz), which keeps the tab safe if a DMZ API shift ever leaves them unregistered. SU's own
    // cracked balls come from CorruptedBalls; those are real swap blocks with BlockItems.
    //
    // Icon: uses SU's own one-star defiled ball block item, which is always registered by this addon (see
    // CorruptedBalls) and so can never break the tab regardless of which DMZ version or ball-set pack is
    // installed. It replaced the old cerub_1 placeholder icon, removed along with its old green placeholder art.
    // The defiled balls are private, so without the key the icon is SU's own Shenron idol instead (also
    // always registered); PrivateListingRefresh drops the cached icon when the synced answer changes.
    public static final RegistryObject<CreativeModeTab> DRAGON_BALLS = CREATIVE_MODE_TABS.register("dragon_balls",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + ShuruisUtilities.MODID + ".dragon_balls"))
                    .icon(() -> net.shurui.dev.sdu.api.ClientGate.key()
                            ? new net.minecraft.world.item.ItemStack(
                                    net.shurui.shuruisutilities.corrupted.CorruptedBalls.BALLS[1].get())
                            : new net.minecraft.world.item.ItemStack(
                                    net.shurui.shuruisutilities.ritual.ShenronIdol.ITEM.get()))
                    .displayItems((params, tabOutput) -> {
                        // Private items drop out unless the connected server opened their gate (PrivateItems).
                        var output = net.shurui.dev.sdu.api.PrivateItems.filtered(tabOutput);
                        // SU placeholder balls (none now; the old cerulean placeholders were removed).
                        for (var item : ContentItems.DRAGON_BALLS) {
                            output.accept(item.get());
                        }
                        // Earth balls + earth radar (static in DMZ).
                        for (int i = 1; i <= 7; i++) {
                            acceptDmz(output, "dball" + i);
                        }
                        acceptDmz(output, "dball_radar");
                        // Namek balls (static in DMZ). Namek keeps DMZ's own namek radar in DMZ's tabs.
                        for (int i = 1; i <= 7; i++) {
                            acceptDmz(output, "dball" + i + "_namek");
                        }
                        // Black star balls + radar (external pack; skipped when the pack is not installed).
                        for (int i = 1; i <= 7; i++) {
                            acceptDmz(output, "dball" + i + "_blackstar");
                        }
                        acceptDmz(output, "blackstar_dball_radar");
                        // Super balls + radar (external pack; skipped when the pack is not installed).
                        for (int i = 1; i <= 7; i++) {
                            acceptDmz(output, "dball" + i + "_super");
                        }
                        acceptDmz(output, "super_dball_radar");
                        // SU's own cracked balls (the ex-"cracked_*" placeholders, now real swap blocks).
                        for (int s = 1; s <= net.shurui.shuruisutilities.corrupted.CorruptedBalls.COUNT; s++) {
                            net.shurui.shuruisutilities.corrupted.CorruptedBalls.BALLS[s].ifPresent(output::accept);
                        }
                        // The idols a spent set leaves behind. They belong here rather than in a blocks tab: they are
                        // part of the ball cycle, and the recreation prompt is the only thing either of them does.
                        net.shurui.shuruisutilities.ritual.ShenronIdol.ITEM.ifPresent(output::accept);
                        net.shurui.shuruisutilities.ritual.ShenronIdol.PORUNGA_ITEM.ifPresent(output::accept);
                    })
                    .build());

    /**
     * Accept a dragonminez item into the tab by registry path, but only when it is actually registered. DMZ is
     * a mandatory dependency so its static balls are always there, but the black star and super sets ship in an
     * external pack that may not be installed; resolving through {@code containsKey} means a missing pack quietly
     * yields fewer entries instead of crashing or log-spamming. Guarding the earth and namek lookups the same way
     * costs nothing and keeps the tab safe across DMZ version changes.
     */
    private static void acceptDmz(net.minecraft.world.item.CreativeModeTab.Output output, String path) {
        net.minecraft.resources.ResourceLocation id =
                new net.minecraft.resources.ResourceLocation("dragonminez", path);
        if (net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id)) {
            output.accept(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id));
        }
    }

    // Equipment: weapons, armor and curios content plus SU's own four hoverbikes.
    public static final RegistryObject<CreativeModeTab> EQUIPMENT = CREATIVE_MODE_TABS.register("equipment",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + ShuruisUtilities.MODID + ".equipment"))
                    // Icon: SU's own Ragnarok Gi chestplate, always registered by this addon. It replaced the old
                    // power_pole placeholder icon, removed in the 2.0 debloat.
                    .icon(() -> net.shurui.shuruisutilities.armor.RagnarokGiItems.CHESTPLATE.get().getDefaultInstance())
                    .displayItems((params, tabOutput) -> {
                        // Private items drop out unless the connected server opened their gate (PrivateItems).
                        var output = net.shurui.dev.sdu.api.PrivateItems.filtered(tabOutput);
                        for (var item : ContentItems.EQUIPMENT) {
                            output.accept(item.get());
                        }
                        // SU's own hoverbike spawn items (hoverbike_1..4).
                        for (int v = 1; v <= 4; v++) {
                            HoverbikeItems.BIKES[v].ifPresent(output::accept);
                        }
                        // SU's own space-pod chip (shares the hoverbike curios slot + keybind).
                        HoverbikeItems.POD_CHIP.ifPresent(output::accept);
                        // SU's own nimbus chip (same shared slot + keybind; flying/black chosen by alignment).
                        HoverbikeItems.NIMBUS_CHIP.ifPresent(output::accept);
                        // SU's own time machine chip (same shared slot + keybind; a flying vehicle that enables space).
                        HoverbikeItems.TIME_MACHINE_CHIP.ifPresent(output::accept);
                        // Ragnarok Gi: three pieces, no helmet (the art covers chest, legs and boots only).
                        net.shurui.shuruisutilities.armor.RagnarokGiItems.CHESTPLATE.ifPresent(output::accept);
                        net.shurui.shuruisutilities.armor.RagnarokGiItems.LEGGINGS.ifPresent(output::accept);
                        net.shurui.shuruisutilities.armor.RagnarokGiItems.BOOTS.ifPresent(output::accept);
                        // SU's own Curios-equipped dragon ball bag.
                        net.shurui.shuruisutilities.dragonballbag.DragonBallBagItems.BAG.ifPresent(output::accept);
                        // SU's own Curios-equipped senzu bean bag (the ex-placeholder "senzubag", now real).
                        net.shurui.shuruisutilities.senzu.bag.SenzuBagItems.BAG.ifPresent(output::accept);
                        // Katchin + katchi katchin tools (ten, no sword). The katchi katchin tools show their default
                        // colour here; the real colour is rolled when one is smithed.
                        for (var tool : net.shurui.shuruisutilities.katchin.KatchinItems.TOOLS) {
                            output.accept(tool.get());
                        }
                    })
                    .build());

    public static final RegistryObject<CreativeModeTab> CONSUMABLES = CREATIVE_MODE_TABS.register("consumables",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + ShuruisUtilities.MODID + ".consumables"))
                    // Icon: SU's own real golden senzu bean, always registered by this addon. It replaced the old
                    // senzubean placeholder icon, removed in the 2.0 debloat.
                    .icon(() -> net.shurui.shuruisutilities.senzu.SenzuRegistry.GOLDEN_BEAN.get().getDefaultInstance())
                    .displayItems((params, tabOutput) -> {
                        // Private items drop out unless the connected server opened their gate (PrivateItems).
                        var output = net.shurui.dev.sdu.api.PrivateItems.filtered(tabOutput);
                        for (var item : ContentItems.CONSUMABLES) {
                            output.accept(item.get());
                        }
                        // SU's own guild claim-upgrade consumables (+1/+5/+10/+25 claim chunks). Creative only for
                        // now: no recipe or drop, so the creative tab (and give commands) are the sole source.
                        for (var item : net.shurui.shuruisutilities.guilds.ClaimUpgradeItems.ALL) {
                            output.accept(item.get());
                        }
                        // Events (PRIVATE feature): the reserve event token. displayItems runs on the client, so
                        // hide it unless the connected server reported the event engine installed
                        // (ClientGate.feature, the correct client gate, NOT a server-side key read). On a keyless
                        // client this leaves the tab exactly as before.
                        if (net.shurui.dev.sdu.api.ClientGate.feature(
                                net.shurui.dev.sdu.api.key.EventHooks.FEATURE_ID)) {
                            net.shurui.shuruisutilities.content.ContentItems.EVENT_TOKEN.ifPresent(output::accept);
                        }
                    })
                    .build());

    // Blocks & Misc: misc content plus SU's own deletion wand. Icon uses the surviving ss_ticket (Ragnarok
    // Ticket), a tab-owned item; the old itemwarenai icon was a placeholder removed in the 2.0 debloat. The ticket is
    // private, so without the key the icon is the first katchin block instead (see PrivateListingRefresh).
    public static final RegistryObject<CreativeModeTab> BLOCKS_MISC = CREATIVE_MODE_TABS.register("blocks_misc",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + ShuruisUtilities.MODID + ".blocks_misc"))
                    .icon(() -> net.shurui.dev.sdu.api.ClientGate.key()
                            ? ContentItems.byName("ss_ticket").getDefaultInstance()
                            : net.shurui.shuruisutilities.katchin.KatchinBlocks.BLOCK_ITEMS.get(0).get()
                                    .getDefaultInstance())
                    .displayItems((params, tabOutput) -> {
                        // Private items drop out unless the connected server opened their gate (PrivateItems).
                        var output = net.shurui.dev.sdu.api.PrivateItems.filtered(tabOutput);
                        for (var item : ContentItems.BLOCKS_MISC) {
                            output.accept(item.get());
                        }
                        // SU's own admin deletion wand.
                        DeletionWandItems.DELETION_WAND.ifPresent(output::accept);
                        // SU's own decorative life-size time machine block (for show only; a placement multiblock).
                        net.shurui.shuruisutilities.timemachine.TimeMachineBlocks.TIME_MACHINE_ITEM
                                .ifPresent(output::accept);
                        // Katchin material blocks (katchin + the three katchi katchin colours) and the two smithing
                        // templates. Blocks live in blocks_misc; the tools live in the equipment tab above.
                        for (var item : net.shurui.shuruisutilities.katchin.KatchinBlocks.BLOCK_ITEMS) {
                            output.accept(item.get());
                        }
                        for (var tpl : net.shurui.shuruisutilities.katchin.KatchinItems.TEMPLATES) {
                            output.accept(tpl.get());
                        }
                        // The four decorative space consoles (console, compact console, hologram projector, module).
                        for (var console : net.shurui.shuruisutilities.spaceconsole.SpaceConsoleBlocks.BLOCK_ITEMS) {
                            output.accept(console.get());
                        }
                        // the crate blocks the crate system binds to, and the keys that open them.
                        for (var crate : net.shurui.shuruisutilities.crate.block.SuCrateBlocks.CRATE_ITEMS) {
                            output.accept(crate.get());
                        }
                        for (var key : net.shurui.shuruisutilities.crate.block.SuCrateBlocks.KEY_ITEMS) {
                            output.accept(key.get());
                        }
                        // The Halloween crate block and its key. The block is an animated crate, but it is registered
                        // as its own field to keep its item out of CRATE_ITEMS, so it is added here once; the key
                        // lives with the Halloween cosmetic art.
                        net.shurui.shuruisutilities.crate.block.SuCrateBlocks.HALLOWEEN_CRATE_ITEM
                                .ifPresent(output::accept);
                        net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticContentItems.HW_CRATE_KEY
                                .ifPresent(output::accept);
                        // Racing (PRIVATE feature): the track wand and the three race block items (boost pad, item
                        // spawner, finish line). displayItems runs on the client, so hide them unless the connected
                        // server reported the racing feature installed (ClientGate.feature, the correct client gate,
                        // NOT a server-side key read). On a keyless client this leaves the tab exactly as before.
                        if (net.shurui.dev.sdu.api.ClientGate.feature(
                                net.shurui.shuruisutilities.api.key.RaceHooks.FEATURE_ID))
                        {
                            net.shurui.shuruisutilities.racing.RaceRegistries.RACE_TRACK_WAND.ifPresent(output::accept);
                            for (var item : net.shurui.shuruisutilities.racing.RaceRegistries.BLOCK_ITEMS)
                                output.accept(item.get());
                        }
                    })
                    .build());

    private ContentTabs() {
    }
}
