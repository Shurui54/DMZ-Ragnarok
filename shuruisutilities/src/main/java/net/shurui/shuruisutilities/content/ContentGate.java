package net.shurui.shuruisutilities.content;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.dev.sdu.api.key.CoreGateHooks;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Server-side lock for Shurui's Utilities' own added content (the "what SU used to be" blocks, items and
 * entities). By explicit request the default is LOCKED: this content only functions on a server that holds
 * the <strong>Ragnarok Key</strong>. As of the "private UI is not rendered without the key" pass there is no
 * singleplayer/LAN exemption: without the key this content is inert everywhere, singleplayer included.
 *
 * <p>The spawn choke point below is careful NOT to delete a rgnpc that was already saved to disk under a key,
 * only to inert fresh spawns; cancelling a load-from-disk spawn would erase the saved entity permanently.
 *
 * <p>WHY THIS EXISTS. Most SU features are {@code @SUModule}s that only the key registers (and that
 * {@code PublicContent.enforce()} would tear down on a keyless server), but a {@code DeferredRegister}'s blocks/items/entities survive that teardown (the registry
 * entries cannot be removed without destroying save data), so a stack already in a chest, or a block already
 * placed, would still work. This gate is the leak-guard: it makes the registered content do nothing without the
 * key, at three choke points, without ever unregistering an id.</p>
 *
 * <ul>
 *   <li><b>Use / placement</b>: {@link PlayerInteractEvent} right-click of a gated item, or of a placed gated
 *       block, is cancelled server-side.</li>
 *   <li><b>Spawning</b>: gated custom entities (the rgnpc "ninjin" NPCs and the saibaman pet) are removed on
 *       {@link EntityJoinLevelEvent} server-side. This is non-destructive: the entity's NBT stays on disk and
 *       returns when the key does.</li>
 *   <li><b>Crafting</b>: at {@link ServerStartedEvent} every recipe whose result is gated content is stripped
 *       from the {@link RecipeManager}, so it cannot be crafted on a keyless (limited) server.</li>
 * </ul>
 *
 * <p>What is NOT gated here (deliberate exceptions): the dungeon spawner and the whole dungeon/raid/tournament
 * content, which live in their own trees and carry their own key gating (the dungeon spawner is public by
 * request, procedural floors are Shurui's-Key-only, and so on). The hoverbike/space-pod/nimbus items are public
 * and are not gated here.</p>
 */
@Mod.EventBusSubscriber(modid = ShuruisUtilities.MODID)
public final class ContentGate
{
    private ContentGate() {}

    private static volatile Set<Item> gatedItems;

    /**
     * Content is unlocked only where the Ragnarok Key is present (singleplayer included). Asked through
     * {@link CoreGateHooks}, which only the real key installs (keyless default: locked), so a jar that merely carries
     * the key's mod id unlocks nothing here.
     */
    public static boolean unlocked()
    {
        return CoreGateHooks.get().contentUnlocked();
    }

    // Lazily collected once, at first use (well after all DeferredRegisters have fired). Every SU content register
    // that a player can obtain, use or place is included. Referencing the holder classes here classloads only SU's
    // own code, never an optional dependency.
    private static Set<Item> gatedItems()
    {
        Set<Item> local = gatedItems;
        if (local != null)
            return local;
        local = new HashSet<>();
        addAll(local, net.shurui.shuruisutilities.content.ContentItems.ITEMS.getEntries());
        addAll(local, net.shurui.shuruisutilities.corrupted.CorruptedBalls.ITEMS.getEntries());
        // The katchin tool set is PUBLIC as of 2.0 (PublicContent.FEATURE_KATCHIN): its tools, smithing templates
        // and block items are only added to the locked set when that feature is withheld, so on a keyless server
        // they stay usable. Withdrawing the feature relocks them with no other edit.
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_KATCHIN))
        {
            addAll(local, net.shurui.shuruisutilities.katchin.KatchinItems.TOOLS);
            addAll(local, net.shurui.shuruisutilities.katchin.KatchinItems.TEMPLATES);
            addAll(local, net.shurui.shuruisutilities.katchin.KatchinBlocks.BLOCK_ITEMS);
        }
        add(local, net.shurui.shuruisutilities.dragonballbag.DragonBallBagItems.BAG);
        // The senzu bean system is PUBLIC as of 2.0 (PublicContent.FEATURE_SENZU): its items are only added to the
        // locked set when that feature is withheld, so on a keyless server they stay usable. Withdrawing the
        // feature relocks them with no other edit.
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_SENZU))
        {
            addAll(local, net.shurui.shuruisutilities.senzu.SenzuRegistry.ITEMS.getEntries());
            add(local, net.shurui.shuruisutilities.senzu.bag.SenzuBagItems.BAG);
        }
        // The saibaman seed path is PUBLIC as of 2.0 (PublicContent.FEATURE_SAIBAMAN_SEEDS): the seed item stays
        // usable on a keyless server (the pet entity it grows is un-gated in isGatedEntity under the same name).
        // The admin grow tool is NOT part of that decision and stays locked, staff-only in its own use() besides.
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_SAIBAMAN_SEEDS))
        {
            add(local, net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.SAIBAMAN_SEED);
        }
        add(local, net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.SAIBAMAN_GROW_TOOL);
        // Racing content (PRIVATE feature): the track wand and the three race block items are locked on a keyless
        // server, like the rest of SU's own content. Their behaviour is inert without the key besides, but gating
        // the items too keeps a keyless player from placing or using them at all.
        add(local, net.shurui.shuruisutilities.racing.RaceRegistries.RACE_TRACK_WAND);
        for (net.minecraftforge.registries.RegistryObject<Item> bi : net.shurui.shuruisutilities.racing.RaceRegistries.BLOCK_ITEMS)
            add(local, bi);
        // Events (PRIVATE feature): the reserve event token is locked on a keyless server, like the rest of SU's
        // own content. It has no in-world use of its own yet, but gating it keeps a keyless player from obtaining
        // or spending it before the event engine (which the key installs) is present.
        add(local, net.shurui.shuruisutilities.content.ContentItems.EVENT_TOKEN);
        gatedItems = local;
        return local;
    }

    private static void addAll(Set<Item> out, Collection<RegistryObject<Item>> src)
    {
        for (RegistryObject<Item> ro : src)
            add(out, ro);
    }

    private static void add(Set<Item> out, RegistryObject<Item> ro)
    {
        if (ro != null && ro.isPresent())
            out.add(ro.get());
    }

    private static boolean isGatedItem(ItemStack stack)
    {
        return stack != null && !stack.isEmpty() && gatedItems().contains(stack.getItem());
    }

    private static boolean isGatedEntity(EntityType<?> type)
    {
        if (type == null)
            return false;
        RegistryObject<EntityType<net.shurui.shuruisutilities.ragnarok.RgNpcEntity>> rg =
                net.shurui.shuruisutilities.ragnarok.RgNpcEntities.RGNPC;
        RegistryObject<EntityType<net.shurui.shuruisutilities.saibaman.SaibamanPetEntity>> sai =
                net.shurui.shuruisutilities.saibaman.SaibamanPetEntities.SAIBAMAN_PET;
        // The saibaman pet a mature crop yields is part of the PUBLIC seed path (FEATURE_SAIBAMAN_SEEDS), so it is
        // only gated when that feature is withheld; otherwise a keyless harvest would grow a pet the join event
        // then removed, leaving the crop standing forever. The rgnpc "ninjin" NPCs stay gated regardless.
        boolean saibamanGated = !net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_SAIBAMAN_SEEDS);
        return (rg != null && rg.isPresent() && rg.get() == type)
                || (saibamanGated && sai != null && sai.isPresent() && sai.get() == type);
    }

    private static void notifyLocked(ServerPlayer player)
    {
        // action-bar system message; a plain literal avoids adding lang keys for an admin-facing lock notice.
        player.displayClientMessage(
                Component.literal("That content is locked behind Shurui's Key on this server."), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        if (event.getLevel().isClientSide() || unlocked())
            return;
        if (isGatedItem(event.getItemStack()) && event.getEntity() instanceof ServerPlayer sp)
        {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            notifyLocked(sp);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)
    {
        if (event.getLevel().isClientSide() || unlocked())
            return;
        boolean gatedHeld = isGatedItem(event.getItemStack());
        ItemLike placed = event.getLevel().getBlockState(event.getPos()).getBlock();
        boolean gatedTarget = placed != null && gatedItems().contains(placed.asItem());
        if ((gatedHeld || gatedTarget) && event.getEntity() instanceof ServerPlayer sp)
        {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            notifyLocked(sp);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getLevel().isClientSide() || unlocked())
            return;
        if (isGatedItem(event.getItemStack()) && event.getEntity() instanceof ServerPlayer sp)
        {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
            notifyLocked(sp);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide() || unlocked())
            return;
        // A rgnpc already SAVED in a chunk was placed while this server was keyed (or in an older singleplayer
        // world). Cancelling its spawn on a now-keyless server does not just hide it: the entity never re-enters
        // the world, so the next chunk save writes the chunk without it and the saved NBT is gone for good. That
        // is permanent data loss on a server that merely lost its key. Only fresh spawns are gated here; a load
        // from disk is left alone.
        if (event.loadedFromDisk())
            return;
        if (isGatedEntity(event.getEntity().getType()))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        if (unlocked())
            return; // full key present (or SP/LAN): nothing stripped
        MinecraftServer server = event.getServer();
        RecipeManager rm = server.getRecipeManager();
        Set<Item> gated = gatedItems();
        java.util.List<Recipe<?>> kept = new java.util.ArrayList<>();
        int removed = 0;
        for (Recipe<?> recipe : rm.getRecipes())
        {
            ItemStack result;
            try
            {
                result = recipe.getResultItem(server.registryAccess());
            }
            catch (Throwable t)
            {
                // a datapack recipe with a dynamic result may throw; keep it rather than risk removing something
                // unrelated.
                kept.add(recipe);
                continue;
            }
            if (result != null && !result.isEmpty() && gated.contains(result.getItem()))
                removed++;
            else
                kept.add(recipe);
        }
        if (removed > 0)
        {
            rm.replaceRecipes(kept);
            LoggingHandler.sulog.info(
                    "[shuruisutilities] Shurui's Key absent: {} SU content recipe(s) stripped (content is locked on this server).",
                    removed);
        }
    }
}
