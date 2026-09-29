package net.shurui.shuruisutilities.core.config;

import java.util.LinkedHashSet;
import java.util.Set;

import net.shurui.shuruisutilities.KeyGate;
import net.shurui.shuruisutilities.compat.worldedit.WEIntegration;

/**
 * What a server with NO key is allowed to run.
 *
 * <h2>A whitelist, deliberately, and never a blacklist</h2>
 * The two fail in opposite directions and only one of them is safe. A deny-list ships every feature somebody
 * forgot to add to it, so the failure mode is a premium system quietly going out for free and nobody noticing
 * until it is already in the wild. An allow-list ships nothing it was not told to ship, so the failure mode is a
 * public feature going missing, which the next person to open the mod reports within the hour. One of those is
 * recoverable and the other is not.
 *
 * <p>The practical consequence, and the point of writing it this way: ANY feature added from now on is locked by
 * default. Nobody has to remember to gate it. Making something public is a deliberate edit to the sets below,
 * which is exactly the moment that decision should be made consciously.
 *
 * <h2>The public set</h2>
 * By explicit decision, and grown on 2026-08-30:
 * <ul>
 *   <li>The SDU content editors (races, forms, NPCs, sagas, wishes and their asset packs).</li>
 *   <li>1v1 tournaments. The team and free-for-all formats are not public.</li>
 *   <li>Basic raid bosses. Parallel Quest and Boss Rush are not public.</li>
 *   <li>The dungeon spawner. Its disguise options are not public.</li>
 *   <li><b>Space, in full</b>: all seven modules, so travel, the hazards that make it cost something, the planets
 *       and moons, owning one, destroying one, and the Apophis event. See {@link #PUBLIC_MODULES}.</li>
 *   <li><b>The combat layer</b>: the dash, the melee clash, and going through terrain at speed.</li>
 *   <li><b>The dragon ball sets this suite adds</b> (Black Star, Super, Cerulean) with the shenron each summons.
 *       The gods of destruction that guard the Super balls come with them, since they are placed and killed by
 *       {@code PlanetSpawnModule}, which is one of the seven space modules above.</li>
 *   <li><b>The space pod chip and the flying nimbus chip</b>, split out of the {@code Hoverbikes} module they
 *       used to share a gate with. The hoverbikes themselves stay key-only.</li>
 *   <li><b>Patreon supporter perks</b>, because a server that cannot run them cannot honour a supporter, and
 *       <b>form cosmetics</b>, which change what a form looks like and nothing else.</li>
 *   <li><b>The advanced spawner's BOSS tier</b>, which was the one part of an otherwise public spawner that a
 *       keyless operator could configure and never see fire.</li>
 * </ul>
 * <p>Grown again at the 2.0 tier collapse (owner decision): hoverbikes, the senzu bean system, the saibaman seed
 * path (the pet EDITOR module stays private), the katchin tool set, character slots, every tournament format, and
 * every raid type except rifts. (The armour rune system was already ungated, so it needed no entry.) There is one
 * key now, the Ragnarok Key, and no other tier.
 *
 * <p>Everything else is key-only: every administration system, the economy and everything under it, guilds, ranks,
 * chat, tab list, teleports, tasks, holograms, saibaman PETS (the editor), the shrine, NPC regions, sparring, TP
 * boosts, prestige, instanced dungeons, dungeon crates and tickets, rifts, namekow and kaiow, and the corrupted
 * ball set.
 *
 * <p>The terrain-regen gamerule is not listed and never was gated: it is registered straight onto the event bus by
 * {@code ShuruisUtilities} rather than by a module, so no tier has ever been able to lose it.
 */
public final class PublicContent
{
    private PublicContent() {}

    /**
     * {@code @SUModule} names that run without a key.
     *
     * <p>Almost empty, and meant to stay that way. No SU FEATURE is public: not a command, not {@code /rggui},
     * and not the blocks and items ({@link ContentGate} already locks those). The single entry here is a tool
     * the public features depend on rather than a feature of its own.
     *
     * <p>This costs the public tier nothing, which is the reason it can be empty at all: the SDU editors are in
     * a different source tree that imports NOTHING from Shurui's Utilities (verified: zero imports) and are
     * reached through their own commands, so tearing down every SU module leaves all four public features intact.
     * Had the editors been opened through {@code /rggui}, locking the command module would have taken the public
     * tier down with it.
     *
     * <p>There is no tier between this list and the Ragnarok Key, so this
     * list is the only thing that should ever grant an SU module to a server without the key.
     */
    public static final Set<String> PUBLIC_MODULES = immutable(
            // WorldEdit selection tools. Public because the public features are what use them: the arena bounds
            // for tournaments and raids, and dungeon room bounds, are all picked with a WorldEdit selection.
            // It hands over a selection and nothing else; the SU systems that CONSUME selections for their own
            // purposes (protection regions, claims) are separate modules and stay torn down, so this grants the
            // ability to select a box and not one thing that could be done with it privately.
            WEIntegration.weModule,

            // SPACE, in full, by Shurui's decision on 2026-08-30. Every module of it, not a usable subset: space
            // is one system and half of it is worse than none. Travel is the trip itself (launch, the space
            // dimension, landing, the return); Hazards is what makes the trip cost something; Planets and
            // LayoutSync are the bodies to fly to and the client's copy of where they are; PlanetClaims is
            // owning one; PlanetBuster and ApophisSummon are what can be done to one. Leave any of these out and
            // a keyless server gets a space dimension with nothing in it, or planets it cannot reach. This is the only
            // place space is opened.
            "SpaceTravel",
            "SpaceHazards",
            "SpacePlanets",
            "SpaceLayoutSync",
            "SpacePlanetClaims",
            "PlanetBuster",
            "ApophisSummon",

            // Also public by Shurui's decision on 2026-08-30, and the two are public for opposite reasons.
            // Patreon is the supporter perks and crowns: it decides who is exempt from a player cap and what a
            // supporter gets, so a server that cannot run it cannot honour a supporter at all, and the tier that
            // most needs to honour one is the tier with no key. FormCosmetics changes what a form LOOKS like and
            // nothing else, so there is nothing in it worth charging for.
            "Patreon",
            "FormCosmetics",

            // Public as of the 2.0 tier collapse (owner decision). Hoverbikes: the rideable hoverbike vehicles
            // (the space pod and nimbus chips were already split out and public); the whole module now runs
            // keyless. Senzu: the senzu bean
            // system (beans, the senzu bag, the farm). Its content items are un-gated to match in ContentGate via
            // FEATURE_SENZU, and the module itself has to stay standing here or the bean handlers are torn down.
            "Hoverbikes",
            "Senzu");

    /**
     * Named non-module features that run without a key.
     *
     * <p>The four public features are event handlers, packets and blocks rather than modules, so they cannot be
     * gated by tearing a module down and need a name of their own to be asked about. Callers ask
     * {@link #allows(String)}; anything not listed here is denied.
     */
    public static final String FEATURE_SDU_EDITORS = "sdu-editors";
    public static final String FEATURE_TOURNAMENT_1V1 = "tournament-1v1";
    public static final String FEATURE_RAIDS_BASIC = "raids-basic";
    public static final String FEATURE_DUNGEON_SPAWNER = "dungeon-spawner";

    /**
     * The combat layer: the dash, the melee clash, and going THROUGH terrain at speed (the sound barrier a maxed
     * flier breaks for themselves, and the slam a clash loser is thrown by).
     *
     * <p>One name for all four because they are one mechanic. The clash ends in a slam, the slam IS the crash, and
     * the boom is how a flier starts the same crash deliberately. Splitting them into separate switches would let a
     * server end up able to lose a clash but not be thrown by it.
     */
    public static final String FEATURE_COMBAT_LAYER = "combat-layer";

    /**
     * The dragon ball sets this suite adds (Black Star, Super, Cerulean), their blocks, their radar entries and the
     * shenron each summons.
     *
     * <p>Their art is bundled in the jar rather than streamed, so the models and textures are on every client
     * already, waiting for a set to use them.
     *
     * <p>IMPORTANT: this feature gates the EFFECT, never the REGISTRATION. The sets ALWAYS register (a compile-time
     * decision, {@code ReleaseToggles.CUSTOM_DRAGON_BALL_SETS}), because registration count must be identical on a
     * client and on a dedicated server or every block/item id after them shifts and clients resolve server ids to
     * the wrong entries. Withholding the feature here (by removing it from {@link #PUBLIC_FEATURES}) makes the sets
     * un-summonable on a keyless dedicated server, so they grant no dragon and no wish, but they still register. The
     * gate is enforced in {@code MixinDmzDragonBallBlock} at the summon, NOT at the bootstrap registration.
     */
    public static final String FEATURE_DRAGONBALL_SETS = "dragonball-sets";

    /**
     * The space pod chip: this suite's curios chip that deploys DMZ's own space pod from the shared "hoverbike"
     * curios slot.
     *
     * <p>It used to sit behind the one {@code Hoverbikes} module gate it shared with the hoverbikes and the nimbus,
     * so all three lived or died together. Space itself was made public on 2026-08-30 (all seven space modules are in
     * {@link #PUBLIC_MODULES}), and the pod is the space-adjacent piece that was still locked, which left the odd gap
     * of a keyless server able to fly DMZ's own pod but not the one this suite hands out. Opening it closes that gap.
     * The hoverbikes it once shared the gate with stay key-only; only the pod and the nimbus were split out.
     */
    public static final String FEATURE_SPACE_POD = "space-pod";

    /**
     * The flying nimbus chip: this suite's curios chip that deploys DMZ's flying nimbus (the black nimbus instead, for
     * an evil-aligned player), also from the shared "hoverbike" curios slot.
     *
     * <p>Public by Shurui's decision on 2026-08-30, riding along with the space pod out of the shared {@code
     * Hoverbikes} gate. Only the hoverbikes themselves remain key-only after the split.
     */
    public static final String FEATURE_NIMBUS = "nimbus";

    /**
     * Trunks' time machine chip: this suite's curios chip that deploys the SU-owned rideable time machine from the
     * shared "hoverbike" curios slot, and enables space travel the same way the space pod does.
     *
     * <p>PUBLIC by consistency with the space pod and the nimbus: it is the same category of thing (a curios space
     * vehicle sharing the one slot and the one space module), space itself is public, and a keyless server that gets
     * the pod and the nimbus but not the time machine would be an odd gap. Listed in {@link #PUBLIC_FEATURES} below.
     *
     * <p>TO MAKE IT PRIVATE (full-key only) instead: remove this name from {@link #PUBLIC_FEATURES} AND change the
     * gate in {@code TimeMachineDeploy.allowed()} from {@code allows(FEATURE_TIME_MACHINE)} to
     * {@code !fullKeyOnlyDenied()}, the private gate for a non-module feature.
     */
    public static final String FEATURE_TIME_MACHINE = "time-machine";

    /**
     * The BOSS spawn tier on the advanced spawner.
     *
     * <p>The spawner block and its disguises were already public while this one roll was locked behind
     * a key check, which left a keyless operator able to set a boss chance in the config and watch
     * it silently never fire. It is a named feature rather than no gate at all so it can be withheld again by one
     * edit to {@link #PUBLIC_FEATURES}.
     */
    public static final String FEATURE_SPAWNER_BOSS = "spawner-boss";

    /**
     * The mini clone technique: the {@code su_mini_clone} ki move that summons miniature clones of the caster.
     *
     * <p>PUBLIC by Shurui's decision (2026-09-22): the move is opened up so it can be learned in-world rather than
     * being a full-key-only ability. It used to be gated at cast on {@link #fullKeyOnlyDenied()}, which hands
     * nothing to a keyless server. Naming it here and gating the cast on {@link #allows(String)} instead opens it to a keyless server.
     *
     * <p>This gates the EFFECT (the cast), never a registration: the technique, entity and items always register
     * so no numeric id shifts between a client and a keyless server. Withdrawing it is one edit to
     * {@link #PUBLIC_FEATURES}.
     */
    public static final String FEATURE_MINI_CLONE = "mini-clone";

    /**
     * The senzu bean system: the beans, the senzu bag and the senzu farm. PUBLIC as of the 2.0 tier collapse
     * (owner decision). The {@code Senzu} module is in {@link #PUBLIC_MODULES} so its handlers stay standing on a
     * keyless server; this name un-gates the registered senzu ITEMS in {@link net.shurui.shuruisutilities.content.ContentGate}
     * (which otherwise locks all SU content behind the key), so the beans can actually be used. Gates the effect,
     * never a registration.
     */
    public static final String FEATURE_SENZU = "senzu";

    /**
     * The saibaman seed path: the seed item and the crop it grows (and the tamed saibaman a mature crop yields).
     * PUBLIC as of the 2.0 tier collapse (owner decision). The saibaman PET SYSTEM stays private: the
     * {@code SaibamanPets} @SUModule (its config editor and admin tuning) is deliberately NOT in
     * {@link #PUBLIC_MODULES}, so a keyless server cannot edit the pets, but the seed can be planted, grown and
     * harvested because {@link net.shurui.shuruisutilities.content.ContentGate} un-gates the seed item and the pet
     * entity spawn under this name. Gates the effect, never a registration.
     */
    public static final String FEATURE_SAIBAMAN_SEEDS = "saibaman-seeds";

    /**
     * The katchin tool set: the katchin tools, their smithing templates and the katchin block items. PUBLIC as of
     * the 2.0 tier collapse (owner decision). This un-gates the katchin items in
     * {@link net.shurui.shuruisutilities.content.ContentGate} (which otherwise locks all SU content behind the key);
     * the admin {@code /rune} editor is a separate, OP-only tool and is NOT covered by this. Gates the effect,
     * never a registration.
     */
    public static final String FEATURE_KATCHIN = "katchin";

    // A NAME IN THIS LIST GATES AN EFFECT, NEVER A REGISTRATION. allows() reads restricted(), which is a runtime
    // predicate that answers differently on a client than on a keyless dedicated server. So the answer here must
    // only ever decide whether a feature FUNCTIONS, never how many blocks, items, entities or packets exist. Anything
    // that changes a registry-entry COUNT must be decided by a compile-time constant baked into the jar (see
    // ReleaseToggles), so both sides register the same number and no numeric id ever shifts between them. Adding or
    // removing a name here must never add or remove a registry entry on one side only.
    private static final Set<String> PUBLIC_FEATURES = immutable(
            FEATURE_SDU_EDITORS,
            FEATURE_TOURNAMENT_1V1,
            FEATURE_RAIDS_BASIC,
            FEATURE_DUNGEON_SPAWNER,
            // Public by Shurui's decision on 2026-08-30.
            FEATURE_COMBAT_LAYER,
            FEATURE_DRAGONBALL_SETS,
            FEATURE_SPACE_POD,
            FEATURE_NIMBUS,
            FEATURE_TIME_MACHINE,
            FEATURE_SPAWNER_BOSS,
            // Public by Shurui's decision on 2026-09-22.
            FEATURE_MINI_CLONE,
            // Public as of the 2.0 tier collapse (owner decision): the senzu bean system, the saibaman seed path,
            // and the katchin tool set.
            FEATURE_SENZU,
            FEATURE_SAIBAMAN_SEEDS,
            FEATURE_KATCHIN);

    /**
     * Whether a keyless server may run this module.
     *
     * <p>With a key present this is always true and the whole class is inert: gating is only ever subtractive,
     * so holding a key can never take something away.
     */
    public static boolean moduleAllowed(String moduleName)
    {
        return !restricted() || PUBLIC_MODULES.contains(moduleName);
    }

    /**
     * Whether THIS tier, whichever it is, may run this module at all. Ask this in {@code registerCommands}.
     *
     * <p>Registering a command is the one decision that cannot be revisited. {@link #enforce()} runs at
     * {@code ServerStartedEvent}, and {@code RegisterCommandsEvent} has already
     * fired by then, so tearing a module down takes it off the event bus and leaves every command it registered
     * sitting in the dispatcher, fully usable. A module that decides nothing here is therefore granted to every tier
     * no matter what the allowlists say, which is exactly how {@code /jail}, {@code /warp}, {@code /trade} and
     * {@code /spar} were reachable on a keyless server with their modules torn down.
     *
     * <p>Since 2.0 there is one key, and batch M removed the operator switchboard, so this is now exactly the
     * public allow-list: keyless keeps {@link #PUBLIC_MODULES}, the Ragnarok Key keeps everything.
     *
     * <p>Default-deny follows from the list itself, so a module added tomorrow that asks this question is
     * locked on the lower tier without anyone having to remember to add it anywhere.
     */
    public static boolean moduleEntitled(String moduleName)
    {
        return moduleAllowed(moduleName);
    }

    /**
     * Whether a keyless server may run this named feature.
     *
     * <p>The default answer for an unknown name is NO. That is the whole design: a feature nobody has thought
     * about is locked, not free.
     */
    public static boolean allows(String feature)
    {
        return !restricted() || PUBLIC_FEATURES.contains(feature);
    }

    /**
     * Whether this instance is running the restricted public subset.
     *
     * <p>Restrictions only ever bite on a DEDICATED SERVER holding no Ragnarok Key. Singleplayer and LAN always
     * get everything. Defers to the {@link net.shurui.dev.sdu.api.RagnarokKey} facade so the one-key decision
     * lives in a single place.
     */
    public static boolean restricted()
    {
        return net.shurui.dev.sdu.api.RagnarokKey.restricted();
    }

    /**
     * Whether a private non-module feature must stay switched off here.
     *
     * <p>With ONE key there is no middle tier, so {@code restricted()} and this answer the same thing. Kept as its own name only for the call sites that read it.
     */
    public static boolean fullKeyOnlyDenied()
    {
        return net.shurui.dev.sdu.api.RagnarokKey.restricted();
    }

    /** True when this instance is running the public subset. */
    public static boolean publicTier()
    {
        return restricted();
    }

    /**
     * Tear down every module this tier is not allowed to run.
     *
     * <p>Runs at {@code ServerStartedEvent}. Default-deny by construction: it walks EVERY registered module and
     * keeps only the ones named in {@link #PUBLIC_MODULES}, rather than removing a fixed list of known-private ones.
     *
     * <p>Since SF every private module lives in the Ragnarok Key jar, so a keyless server never registers one and
     * this strips nothing ("0 module(s) inactive"). Stripping anything means a private module is still in core (or
     * a public one is missing from {@link #PUBLIC_MODULES}); that is logged as an ERROR so it cannot pass unseen.
     *
     * <p>Strips nothing unless {@link #restricted()}. With the key it only logs how many private modules the key
     * registered, and warns when that is none (a key jar that claims presence but installed nothing).
     */
    public static void enforce()
    {
        if (!restricted())
        {
            // Keyed: nothing is stripped. Count the private modules the key registered, so a key that claims presence
            // but installed nothing (a broken or fake jar) is visible in the log instead of passing as a full server.
            int privateModules = 0;
            for (String moduleName : net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher.getModuleMap()
                    .keySet())
                if (!PUBLIC_MODULES.contains(moduleName))
                    privateModules++;
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                    "[shuruisutilities] Ragnarok Key present: {} private module(s) registered, 0 module(s) inactive.",
                    privateModules);
            if (privateModules == 0)
                net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.warn(
                        "[shuruisutilities] The Ragnarok Key reports itself present but registered no private module: "
                                + "the key jar is broken or not genuine. Private features stay off.");
            return;
        }

        Set<String> stripped = new LinkedHashSet<>();
        // Names snapshotted first: unregister() mutates the map being walked.
        Set<String> registered = new LinkedHashSet<>(
                net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher.getModuleMap().keySet());
        for (String moduleName : registered)
        {
            if (PUBLIC_MODULES.contains(moduleName))
                continue;
            var container = net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher
                    .getModuleContainer(moduleName);
            if (container == null)
                continue; // already gone: disabled in Modules.cfg, or a precondition refused it
            container.isLoadable = false;
            net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher.instance.unregister(moduleName);
            stripped.add(moduleName);
        }

        net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.info(
                "[shuruisutilities] No server key: running the public feature set. {} module(s) inactive: {}",
                stripped.size(), stripped);
        if (!stripped.isEmpty())
            net.shurui.shuruisutilities.util.output.logger.LoggingHandler.sulog.error(
                    "[shuruisutilities] A keyless server registered {} private module(s) that had to be stripped: {}. "
                            + "Private modules belong in the Ragnarok Key; this is a bug, report it.",
                    stripped.size(), stripped);
    }

    private static Set<String> immutable(String... values)
    {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values)
            out.add(v);
        return java.util.Collections.unmodifiableSet(out);
    }
}
