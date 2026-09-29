package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dragonminez.common.dragonball.DragonBallDefinitions;

import net.shurui.shuruisutilities.compat.dmz.SuDragonBallDefinitions;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Injection point that folds Shurui's three hardcoded dragon ball sets (Black Star, Super, Cerulean) into DMZ's
 * bootstrap, replacing the old external {@code dragonballs/} filesystem pack.
 *
 * <p>WHY here. {@code DragonBallDefinitions.loadExternalBootstrapDefinitions()} is the method DMZ's static
 * initialiser calls to fold the on-disk pack into its bootstrap maps, and it runs AFTER DMZ has registered its own
 * earth and namek definitions but BEFORE {@code resetRuntimeDefinitions()} copies those maps into the runtime maps
 * that registration then reads. Every {@code registerBootstrap*} method stores by id, so appending ours at the
 * TAIL of this method lands them in the maps at exactly the right instant, last write wins by id. We only ADD new
 * ids, so DMZ's earth and namek are untouched.
 *
 * <p>SAFETY, the single most important point in this class. This handler runs INSIDE a static initialiser. If it
 * threw, {@code DragonBallDefinitions} would fail to initialise and DMZ (and the whole game) would go down at
 * load. So the entire body is wrapped in {@code try/catch(Throwable)} with a one-shot latched log: any failure
 * (a DMZ API shift, a bad constructor) degrades to "our sets are simply absent" and the game still starts on
 * DMZ's stock earth and namek sets.
 *
 * <p>remap=false: the {@code @Mixin} target and the {@code loadExternalBootstrapDefinitions} selector are DMZ's
 * own names (official in the prod jar), not vanilla overrides, so nothing here needs the refmap. require=0 per the
 * standing rule for mixins into DMZ classes: if DMZ renames or removes this method, the injector degrades to a
 * no-op (our sets absent) instead of crashing mod load.
 */
@Mixin(targets = "com.dragonminez.common.dragonball.DragonBallDefinitions", remap = false)
public abstract class MixinDmzDragonBallBootstrap
{
    // one-shot latch so a DMZ API mismatch logs exactly once instead of once per class-init attempt.
    private static boolean su$loggedFailure = false;

    @Inject(method = "loadExternalBootstrapDefinitions", at = @At("TAIL"), require = 0, remap = false)
    private static void su$registerHardcodedBallSets(CallbackInfo ci)
    {
        // REGISTRATION MUST BE SYMMETRIC BETWEEN CLIENT AND DEDICATED SERVER, ALWAYS. Do NOT gate this call on any
        // runtime predicate that can differ by distribution or key tier: PublicContent.restricted(),
        // PublicContent.allows(...), PublicContent.fullKeyOnlyDenied(), RagnarokKey.*, KeyGate.*, FMLEnvironment.dist
        // or the modules.cfg switchboard. DMZ turns each bootstrap ball SET into real registry entries (16 blocks
        // and 16 items across these three sets) when MainBlocks iterates getBootstrapBallSets(), so how many sets we
        // fold in here IS a registry-entry count. If a dedicated server folded in fewer sets than its clients, every
        // numeric block/item id after that point would shift and clients would resolve server ids to the wrong
        // entries (a trash can drawn as a bookshelf, an item that crashes on click).
        //
        // The ONE switch allowed to change this count is the COMPILE-TIME constant ReleaseToggles.CUSTOM_DRAGON_BALL_SETS,
        // which SuDragonBallDefinitions.registerBootstrapDefinitions() consults; it is baked into the jar so it is the
        // same answer on both sides. It used to be gated here on PublicContent.allows(FEATURE_DRAGONBALL_SETS): that was
        // symmetric only by luck (the feature is currently in PUBLIC_FEATURES) and would have silently desynced every
        // client on a keyless dedicated server the moment someone withheld the sets from the allow-list. The gate was
        // relocated to the EFFECT layer (the summon, in MixinDmzDragonBallBlock), so the sets always REGISTER but stay
        // inert on a keyless dedicated server that withholds the feature. Gate the effect, never the registration.
        try
        {
            SuDragonBallDefinitions.registerBootstrapDefinitions();
            su$logBootstrapCount();
        }
        catch (Throwable t)
        {
            // Never let a failure escape a static initialiser. Latch the warning so it fires at most once, then
            // fall through: DMZ keeps its own earth and namek sets and the game still loads, only our three sets
            // are missing.
            if (!su$loggedFailure)
            {
                su$loggedFailure = true;
                try
                {
                    LoggingHandler.sulog.warn(
                            "[dragonballs] Could not register Shurui's hardcoded dragon ball sets (Black Star, "
                                    + "Super, Cerulean); DMZ's own sets are unaffected. Cause: {}",
                            t.toString());
                }
                catch (Throwable ignored)
                {
                    // logging must not resurrect the failure inside a static initialiser.
                }
            }
        }
    }

    // Regression guard. Record how many dragon ball SETS the suite folded into DMZ's bootstrap, once, at load. This
    // count drives how many ball blocks and items DMZ then registers, and that number MUST be identical on a client
    // and on a dedicated server. If a future change ever regates registration on a runtime predicate, the two logs
    // will disagree and a desync becomes diagnosable from the log rather than from a player reporting a bookshelf
    // where a trash can should be. Fully guarded: a diagnostic must never turn a healthy load into a failure, least
    // of all from inside a static initialiser.
    private static void su$logBootstrapCount()
    {
        try
        {
            int sets = DragonBallDefinitions.getBootstrapBallSets().size();
            LoggingHandler.sulog.info(
                    "[dragonballs] Bootstrap ball sets folded in: {} (this count must match between client and "
                            + "dedicated server; a mismatch shifts every block/item id after it).",
                    sets);
        }
        catch (Throwable ignored)
        {
            // a diagnostic that cannot read the count is not a reason to fail the load.
        }
    }
}
