package net.shurui.shuruisutilities.compat.tinkers;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.resource.PathPackResources;

/**
 * Ships our Tinkers' Construct rebalances as a datapack that Tinkers cannot out-rank.
 *
 * <h2>Why a pack and not just files in our own data/ folder</h2>
 * Every mod's {@code data/} is folded into ONE "mod resources" pack, and inside it the winner between two mods
 * writing the same path is decided by mod load order - which for us is alphabetical against "tconstruct" and would
 * silently go the wrong way. A pack added here with {@link Pack.Position#TOP} sits ABOVE that combined pack, so our
 * copy of a Tinkers file is the one that loads, every time, without depending on anybody's load order.
 *
 * <h2>What it changes, and why each</h2>
 * <ul>
 *   <li>{@code melee_protection} loses its protection module. Tinkers computes its own flat percentage off the final
 *       damage number, outside vanilla's armour maths and so outside everything DMZ does to it: a cobalt chestplate
 *       was cutting melee damage no matter what the two fighters' stats said.</li>
 *   <li>{@code enderclearance} has every leveling value zeroed. It teleports your attacker off you, which on a melee
 *       server is a dodge nobody can play around.</li>
 *   <li>The {@code necrotic} and {@code spitting} recipes are switched off with a false condition. Both are made
 *       inert in code (see the compat mixins) and this only stops players paying slots for a dead modifier; their
 *       SALVAGE recipes are untouched, so anyone already carrying one can take it back off.</li>
 * </ul>
 *
 * <p>Gated on Tinkers being present: with no tconstruct there is nothing to override, and an empty pack in the list
 * is just noise in the pack screen.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class TinkersOverridePack
{
    private TinkersOverridePack() {}

    /** Folder inside our jar holding the pack. Not under {@code data/}, so it is invisible until added here. */
    private static final String ROOT = "tinkers_overrides";

    private static final String PACK_ID = "dmz_ragnarok:tinkers_overrides";

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event)
    {
        if (event.getPackType() != PackType.SERVER_DATA)
            return;
        if (!ModList.get().isLoaded("tconstruct"))
            return;

        Path root = findRoot();
        if (root == null)
            return;

        PathPackResources resources = new PathPackResources(PACK_ID, true, root);
        Pack pack = Pack.readMetaAndCreate(PACK_ID, Component.literal("DMZ Ragnarok - Tinkers tweaks"), true,
                id -> resources, PackType.SERVER_DATA, Pack.Position.TOP, PackSource.BUILT_IN);
        if (pack == null)
            return;
        event.addRepositorySource(consumer -> consumer.accept(pack));
    }

    /**
     * The folder inside whichever jar we are actually running from.
     *
     * <p>Named by modid rather than resolved from this class's code source because the suite ships merged as
     * {@code dmz_ragnarok} but each subproject is still its own mod when built alone, and a hard-coded id would be
     * wrong in one of those two worlds. Both are tried, then anything that happens to carry the folder.
     */
    private static Path findRoot()
    {
        Path direct = rootIn("dmz_ragnarok");
        if (direct != null)
            return direct;
        direct = rootIn("shuruisutilities");
        if (direct != null)
            return direct;
        for (net.minecraftforge.forgespi.language.IModInfo info : ModList.get().getMods())
        {
            Path candidate = rootIn(info.getModId());
            if (candidate != null)
                return candidate;
        }
        return null;
    }

    private static Path rootIn(String modid)
    {
        try
        {
            var info = ModList.get().getModFileById(modid);
            if (info == null)
                return null;
            IModFile file = info.getFile();
            if (file == null)
                return null;
            Path path = file.findResource(ROOT);
            return path != null && Files.exists(path) ? path : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
