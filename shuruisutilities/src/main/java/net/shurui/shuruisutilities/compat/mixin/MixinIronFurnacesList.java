package net.shurui.shuruisutilities.compat.mixin;

import ironfurnaces.capability.PlayerFurnacesList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Caps Iron Furnaces' per-player {@code ironfurnaces:furnaces_list} capability so it can never bloat a
 * player's .dat again.
 *
 * <h2>The bug (ironfurnaces 1.20.1-4.1.8)</h2>
 * {@code PlayerFurnacesListProvider} serialises a compound {@code furnaces = { furnace0: {X,Y,Z}, ... }} plus a
 * {@code count}. Its {@code deserializeNBT} APPENDS every stored entry to {@code furnacesList.listFurances}
 * without clearing the list first and without checking for a position it already holds, so any path that
 * deserialises onto a non-empty provider (a player clone / respawn, a re-attach) doubles the list, and once the
 * list holds duplicates {@code serializeNBT} writes them straight back. On the live network one player's list had
 * grown to 1,425,760 copies of a single position and a ~20 MB .dat. Vanilla reads that .dat synchronously on the
 * server thread in {@code PlayerList#placeNewPlayer} and writes it on every save, so each login, hop or logout of
 * such a player froze the shard for 8-9 seconds and timed out every other login in flight.
 *
 * <p>The mod's own {@code PlayerFurnacesList#add} already skips a position that is present, so no hook is needed
 * there; the leak is entirely in the provider's (de)serialisation, which is what this targets.</p>
 *
 * <h2>The fix</h2>
 * Three hooks around the provider. The first is the actual correction, the other two are the safety net that
 * heals data already written by a build without it:
 * <ul>
 *   <li>at the HEAD of {@code deserializeNBT(CompoundTag)}: CLEAR the list before it is populated. Deserialising
 *       means "this tag IS the state", not "add this tag to the state", so this is simply the upstream method
 *       doing what it was always meant to do. It is what removes the doubling itself rather than undoing it after
 *       the fact, so the list is never allowed to reach 2n in the first place and no reallocation is paid. Safe
 *       because nothing merges through this path: {@code ironfurnaces.util.EventHandler} declares only
 *       {@code AttachCapabilitiesEvent} and {@code ExplosionEvent} handlers, with no clone or respawn hook that
 *       would rely on append semantics;</li>
 *   <li>at the RETURN of {@code deserializeNBT(CompoundTag)}: collapse duplicates that were in the TAG. The clear
 *       above stops new doubling, but a {@code .dat} or vault row written before this shipped still holds the
 *       duplicates inside itself, and they would survive a faithful load. This is what heals those;</li>
 *   <li>at the HEAD of {@code serializeNBT()}: collapse before writing, so the very next save shrinks the file on
 *       disk and a list that somehow grew in memory cannot be persisted.</li>
 * </ul>
 * The collapse keeps one entry per distinct {@link BlockPos}, first occurrence winning, so the order of first
 * appearance is preserved. Same semantics as {@code ShardPayload.dedupeIronFurnaces}.
 *
 * <h2>The vault no longer carries this at all</h2>
 * The single loudest doubling path was the shard vault: it captured this capability on one server and
 * {@code deserializeCaps}'d it onto a player who had ALREADY loaded their own copy from the local {@code .dat},
 * which is precisely the "deserialise onto a non-empty provider" case. Live logs for 2026-09-19 to 21 show this
 * mixin collapsing exactly 2n to n on every hop, about 1,100 times a day per shard, i.e. the mitigation holding
 * while the cause fired constantly. {@code ShardPayload.CAPS_NOT_TRANSFERRED} now excludes
 * {@code ironfurnaces:furnaces_list} from the payload outright (the positions are per world and a Million Furnace
 * resolves them against its OWN level, so they were never meaningful on another shard), which removes the cause.
 * This mixin stays as the local guarantee: the {@code .dat} is what vanilla parses on the server thread in
 * {@code PlayerList#placeNewPlayer}, so it must be incapable of growing whatever else changes.
 * The dedupe is a single O(n) pass through a {@link LinkedHashSet}, not a per-element {@code contains} scan, so
 * healing a runaway list is linear, not quadratic. It is idempotent: a list with no duplicates is left untouched
 * and no list is reallocated.
 *
 * <p>This does NOT avoid PARSING the huge compound the first time: vanilla {@code NbtIo} reads the whole file
 * before any capability sees it, and {@code deserializeNBT} still walks {@code count} sub-compounds to build the
 * list, so the first load of an already bloated .dat still pays that one-time cost. It caps the list's size from
 * then on, which is what stops the freeze recurring; there is no cheap way to skip the parse itself here.</p>
 *
 * <h2>Optionality</h2>
 * {@code ironfurnaces} is an optional dependency, so this is {@link Pseudo &#64;Pseudo} with a string target and
 * {@code require = 0}: absent the mod, the target class is never found and the mixin is simply skipped, exactly
 * like the Tinkers and resourcefullib compat mixins in this config. {@code remap = false} throughout because the
 * target is not a Minecraft class and its member names ({@code furnacesList}, {@code serializeNBT},
 * {@code deserializeNBT}) are not obfuscated. The jar is on the compile classpath compile-only (root {@code libs}
 * flatDir) purely so {@link Shadow} can name {@code furnacesList}; nothing here is bundled.
 */
@Pseudo
@Mixin(targets = "ironfurnaces.capability.PlayerFurnacesListProvider", remap = false)
public abstract class MixinIronFurnacesList
{
    @Shadow
    public PlayerFurnacesList furnacesList;

    // THE correction: deserialising replaces the state, it does not add to it. Clearing here is what stops the list
    // doubling at all, instead of doubling it and collapsing it back afterwards.
    @Inject(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"), remap = false, require = 0)
    private void su$clearBeforeLoad(CompoundTag tag, CallbackInfo ci)
    {
        if (furnacesList != null && furnacesList.listFurances != null)
            furnacesList.listFurances.clear();
    }

    // Heal an already bloated .dat as it loads: the file is parsed once (unavoidable), but the surviving list is
    // small, so the freeze does not recur and a later re-deserialise cannot double it.
    @Inject(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"), remap = false, require = 0)
    private void su$collapseOnLoad(CompoundTag tag, CallbackInfo ci)
    {
        su$collapseDuplicates();
    }

    // Write only distinct positions (and, since the mod derives count from list size, a matching count), so the
    // next save shrinks the file on disk.
    @Inject(method = "serializeNBT()Lnet/minecraft/nbt/CompoundTag;", at = @At("HEAD"), remap = false, require = 0)
    private void su$collapseOnSave(CallbackInfoReturnable<CompoundTag> cir)
    {
        su$collapseDuplicates();
    }

    /**
     * Collapse {@code furnacesList.listFurances} to one entry per distinct position, in place, first occurrence
     * kept. {@link BlockPos} has value equality, so the {@link LinkedHashSet} dedupes by {@code (x,y,z)} and keeps
     * insertion (first appearance) order. No-op when the list is null, tiny, or already distinct.
     */
    @Unique
    private void su$collapseDuplicates()
    {
        if (furnacesList == null)
            return;
        List<BlockPos> list = furnacesList.listFurances;
        if (list == null || list.size() < 2)
            return;
        LinkedHashSet<BlockPos> distinct = new LinkedHashSet<>(list);
        if (distinct.size() == list.size())
            return;
        int before = list.size();
        list.clear();
        list.addAll(distinct);
        // Never silent, but no longer one line per hop. With the clear-before-populate hook above, a collapse means
        // the STORED tag itself held duplicates, which is legacy data healing, so the first few are worth seeing and
        // the rest are not. A genuinely large collapse stays loud however often it happens, because that is a list
        // regrowing somewhere new and it is exactly what this whole file exists to catch.
        boolean loud = su$collapsesLogged < LOUD_COLLAPSE_REPORTS || (before - distinct.size()) >= LOUD_COLLAPSE_SIZE;
        if (loud)
        {
            su$collapsesLogged++;
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                    "[ironfurnaces] Collapsed a player furnace list: {} entries -> {} distinct.",
                    before, distinct.size());
        }
        else
        {
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.debug(
                    "[ironfurnaces] Collapsed a player furnace list: {} entries -> {} distinct.",
                    before, distinct.size());
        }
    }

    /** How many ordinary collapses to report per server run before dropping the rest to debug. */
    @Unique
    private static final int LOUD_COLLAPSE_REPORTS = 10;

    /** A collapse removing at least this many entries is always reported, however many have been reported already. */
    @Unique
    private static final int LOUD_COLLAPSE_SIZE = 64;

    /** Count of ordinary collapses already reported this run. Plain int, no initialiser, so nothing has to be
     *  merged into the target's static initialiser. Exactness does not matter, only that the log stops growing. */
    @Unique
    private static int su$collapsesLogged;
}
