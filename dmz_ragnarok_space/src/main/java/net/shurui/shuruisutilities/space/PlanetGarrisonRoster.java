package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The FAMILY/roster table for a wild planet's visible GARRISON: the set of NPC "faces" a planet's defenders can wear.
 * This is the art/species catalogue only; the live spawning, stat-stamping, defeat tracking and cleanup live in
 * {@link PlanetGarrison}, and the per-planet persisted state in {@link PlanetGarrisonData}.
 *
 * <h2>Not the clash holder</h2>
 * Do NOT confuse a GARRISON defender with {@link PlanetDefenderEntity} / {@link PlanetDefenderEntities}. That pair is
 * the INVISIBLE, invulnerable beam-clash HOLDER the space-side planet-buster spawns between an incoming giant ball and
 * a planet so DragonMineZ can pair two beams into a struggle; it never fights and never renders. A GARRISON defender
 * here is the OPPOSITE: a VISIBLE, killable NPC that stands on a planet's SURFACE and must be beaten before a guild may
 * claim the world. The two systems never share an entity or a type. See the mirror note on {@link PlanetDefenderEntity}.
 *
 * <h2>Two kinds of member, no special-casing downstream</h2>
 * A roster row is EITHER an SU {@code rgnpc} model id (spawned as a {@link PlanetGarrisonDefenderEntity}, SU's own
 * DragonMineZ saga-fighter subclass wearing that rgnpc GeckoLib model) OR a DragonMineZ entity type id (spawned as
 * that DMZ mob). Both are hidden behind the {@link Member#create(ServerLevel)} factory, which returns a fresh,
 * un-added {@link LivingEntity} or {@code null} to skip the row, so {@link PlanetGarrison}'s spawn loop treats every
 * member uniformly and never branches on the kind.
 *
 * <h2>DMZ ids are resolved by ResourceLocation, never compiled against</h2>
 * The DMZ entity families ({@code saibaman}, the enemy {@code namekian}) are resolved through {@link
 * ForgeRegistries#ENTITY_TYPES} by {@link ResourceLocation}, NOT by importing a DragonMineZ entity class. Standing
 * workspace rule: DMZ being present is not proof its registry ids match this build. A missing id logs ONCE (latched per
 * id, never per spawn) and that row is skipped, so a DMZ version bump degrades to "fewer faces" rather than a crash.
 */
public final class PlanetGarrisonRoster
{
    private PlanetGarrisonRoster()
    {
    }

    // DragonMineZ registered entity ids, confirmed present in the dragonminez-2.1.3.jar lang (entity.dragonminez.<id>).
    // Conquering a planet is the villain's role, so its wild GARRISON is the world's HEROES, not enemy mobs. The families
    // still carry their historical NAMES (persisted by name, see PlanetGarrison, so they must not be renamed), but the
    // faces they spawn are now GOOD-aligned DragonMineZ characters (all normal-sized DBSagasEntity fighters):
    //   NAMEKIAN  -> Nail and the Piccolo line plus the neutral namek warrior (was the enemy namekian + slug soldier).
    //   SAIBAMEN  -> Earth's human Z-fighters (was the six saibaman creatures).
    //   FROST_DEMON -> a veteran hero guard, Piccolo and the hybrid warriors (was the Frieza-Force soldiers).
    //   ROBOT     -> the GOOD-era androids 16/17/18 (was the geti-star robots + a bio-android face).
    // Ids are held as strings and resolved lazily, never as compiled class references, per the class note.
    private static final String DMZ = "dragonminez";
    // Earth's human Z-fighters, one row each; a full combat DBSagasEntity apiece.
    private static final String[] Z_FIGHTER_HUMAN_IDS =
            {"saga_krillin", "saga_tien_early", "saga_yamcha", "saga_chaoz", "saga_goten_ssj", "saga_kid_trunks_ssj"};
    private static final String NAMEKIAN_WARRIOR_ID = "namek_warrior";
    // the good Namekian defenders: Nail and the Piccolo line, plus the neutral namek warrior.
    private static final String[] NAMEKIAN_HERO_IDS =
            {"saga_nail", "saga_piccolo", "saga_piccolo_kami", NAMEKIAN_WARRIOR_ID};
    // a veteran hero guard for the (renamed-in-spirit) FROST_DEMON slot: Piccolo and the strongest hybrid warriors.
    private static final String[] HERO_ELITE_IDS =
            {"saga_piccolo", "saga_gohan_mid_ssj2", "saga_vegeta_mid_ssj", "saga_ftrunks_ssj"};
    // the GOOD-era androids for the ROBOT slot: 16 (a true robot) and the reformed 17/18.
    private static final String[] GOOD_ANDROID_IDS =
            {"saga_a16", "saga_a17", "saga_a18"};

    /** One roster face: a factory that mints a fresh, un-added defender entity, or null to skip this row. */
    public interface Member
    {
        LivingEntity create(ServerLevel level);
    }

    // an rgnpc-faced garrison FIGHTER: an SU-owned DBSagasEntity subclass that fights with DragonMineZ saga AI but
    // wears one of the vetted rgnpc GeckoLib models via its own renderer (see PlanetGarrisonDefenderEntity). The model
    // id is one of the vetted non-HD (64x32) entries the user approved; setModelId sanitises/remaps defensively, so a
    // stale id can never crash a client render. This replaced the old display-only RgNpcEntity so the defenders can
    // actually chase and fight, per the user's decision that they use the same AI as saga fighters.
    private record RgNpcMember(String modelId) implements Member
    {
        @Override
        public LivingEntity create(ServerLevel level)
        {
            PlanetGarrisonDefenderEntity defender = PlanetGarrisonDefenderEntities.type().create(level);
            if (defender == null)
            {
                return null;
            }
            defender.setModelId(modelId);
            return defender;
        }
    }

    // a DMZ custom-character garrison saiyan: an SU-owned DBSagasEntity subclass rendered with DragonMineZ's own race
    // skin (body/face/hair/tail) plus equipped saiyan armor, NOT a saga mob. Its whole appearance (gender, body type,
    // hair, face, colours, and whether it is one of the three named NPCs) is rolled in PlanetGarrison.spawnOne after the
    // entity is created, so this factory only mints the fresh entity; a null result skips the row.
    private record SaiyanMember() implements Member
    {
        @Override
        public LivingEntity create(ServerLevel level)
        {
            return PlanetSaiyanGarrisonEntities.type().create(level);
        }
    }

    // a DragonMineZ mob, resolved by ResourceLocation at spawn time. A missing id (version drift) logs once and yields
    // null so the row is skipped; a resolved type that is not a LivingEntity is discarded and skipped too.
    private record DmzMember(String path) implements Member
    {
        @Override
        public LivingEntity create(ServerLevel level)
        {
            EntityType<?> type = resolveDmz(path);
            if (type == null)
            {
                return null;
            }
            Entity e = type.create(level);
            if (!(e instanceof LivingEntity living))
            {
                if (e != null)
                {
                    e.discard();
                }
                return null;
            }
            return living;
        }
    }

    /**
     * The six wild-defender families the user approved. Each carries its list of faces; a planet's whole garrison is
     * ONE family (chosen per planet in {@link PlanetGarrison}), so all of a planet's defenders share a species theme.
     *
     * <p>Family is PERSISTED BY NAME (see {@link PlanetGarrison}), so reordering or inserting constants never remaps an
     * already-stamped planet. The one ordinal coupling is {@link PlanetSpawnModule#wildDefenderFamilyWeights()}, whose
     * positional int[] MUST stay aligned with this order. The first four constants keep their original ordinals so the
     * existing weight keys line up unchanged; FROST_DEMON and ROBOT were appended.
     */
    public enum Family
    {
        // Saiyans: DMZ custom characters (race skins), NOT rgnpc faces or saga mobs. Every spawn rolls its own look in
        // PlanetGarrison.spawnOne (roughly 50/50 male/female, black hair, brown tail, randomised hair/face, saiyan
        // armor), with a small chance to be one of the three stronger named NPCs. A single member suffices because the
        // variety lives in the per-spawn appearance roll, not in the roster row.
        SAIYAN(List.of(new SaiyanMember())),
        // Red Ribbon Army outpost: pure rgnpc faces, all bundled NinjinEntities saga models (the classic Red Ribbon
        // cast). Name kept for the persisted-by-name contract, so planets already stamped OVERWORLD keep loading and
        // simply wear the new faces. Every id here is a LIVE RgNpcModels entry, so setModelId never falls back to the
        // default 2stars face (Haze Shenron), which is exactly what the old, non-existent "upa" id used to do here.
        OVERWORLD(rgnpc("saga_cl_red_ribbon_soldier_gunner", "saga_cl_red_ribbon_soldier_bazooka",
                "saga_cl_officer_black", "saga_cl_ninja_murasaki", "saga_cl_colonel_silver", "saga_cl_colonel_violet",
                "saga_cl_general_blue", "saga_cl_general_white", "saga_cl_major_metallitron", "saga_cl_mercenary_tao",
                "saga_cl_android8", "saga_cl_commander_red")),
        // Namekian heroes: Nail and the Piccolo line plus the neutral namek warrior (all DMZ faces).
        NAMEKIAN(namekianMembers()),
        // Earth's human Z-fighters (Krillin, Tien, Yamcha, Chiaotzu, Goten, kid Trunks). Name kept for the persisted-by-name
        // contract; these are heroes now, not saibaman creatures.
        SAIBAMEN(zFighterHumanMembers()),
        // A veteran hero guard (Piccolo and the strongest hybrid warriors). Name kept for the persisted-by-name contract;
        // there is no good frost demon, so this slot is the elite hero defenders.
        FROST_DEMON(heroEliteMembers()),
        // The GOOD-era androids 16/17/18. Name kept for the persisted-by-name contract.
        ROBOT(goodAndroidMembers());

        private final List<Member> members;

        Family(List<Member> members)
        {
            this.members = members;
        }

        /** A random face from this family, or null if (defensively) the family somehow has no members. */
        public Member pickMember(RandomSource random)
        {
            if (members.isEmpty())
            {
                return null;
            }
            return members.get(random.nextInt(members.size()));
        }
    }

    // build a list of rgnpc faces from a set of entry ids.
    private static List<Member> rgnpc(String... modelIds)
    {
        List<Member> out = new ArrayList<>(modelIds.length);
        for (String id : modelIds)
        {
            out.add(new RgNpcMember(id));
        }
        return out;
    }

    // NAMEKIAN is pure DMZ now: the good Namekian defenders (Nail and the Piccolo line) plus the neutral namek warrior.
    // Every id resolves lazily, so a version drift on any one degrades to fewer faces.
    private static List<Member> namekianMembers()
    {
        List<Member> out = new ArrayList<>(NAMEKIAN_HERO_IDS.length);
        for (String id : NAMEKIAN_HERO_IDS)
        {
            out.add(new DmzMember(id));
        }
        return out;
    }

    // SAIBAMEN slot is Earth's human Z-fighters now: one row per hero (all DMZ faces).
    private static List<Member> zFighterHumanMembers()
    {
        List<Member> out = new ArrayList<>(Z_FIGHTER_HUMAN_IDS.length);
        for (String id : Z_FIGHTER_HUMAN_IDS)
        {
            out.add(new DmzMember(id));
        }
        return out;
    }

    // FROST_DEMON slot is a veteran hero guard now: Piccolo and the strongest hybrid warriors (all DMZ faces).
    private static List<Member> heroEliteMembers()
    {
        List<Member> out = new ArrayList<>(HERO_ELITE_IDS.length);
        for (String id : HERO_ELITE_IDS)
        {
            out.add(new DmzMember(id));
        }
        return out;
    }

    // ROBOT slot is the GOOD-era androids now: 16 (a true robot) and the reformed 17/18 (all DMZ faces, normal-sized).
    private static List<Member> goodAndroidMembers()
    {
        List<Member> out = new ArrayList<>(GOOD_ANDROID_IDS.length);
        for (String id : GOOD_ANDROID_IDS)
        {
            out.add(new DmzMember(id));
        }
        return out;
    }

    /**
     * Pick a family by weight. {@code weights} is indexed by {@link Family#ordinal()} (SAIYAN, OVERWORLD, NAMEKIAN,
     * SAIBAMEN, FROST_DEMON, ROBOT). A zero or negative weight excludes that family. If every weight is non-positive (an operator disabled
     * them all) we fall back to an even pick so a garrison is never impossible to form. Pure function of the weights and
     * the roll.
     */
    public static Family pickFamily(RandomSource random, int[] weights)
    {
        Family[] families = Family.values();
        int total = 0;
        for (int i = 0; i < families.length; i++)
        {
            int w = (weights != null && i < weights.length) ? Math.max(0, weights[i]) : 1;
            total += w;
        }
        if (total <= 0)
        {
            // all weights zero: fall back to an even pick rather than refusing to form a garrison.
            return families[random.nextInt(families.length)];
        }
        int roll = random.nextInt(total);
        int acc = 0;
        for (int i = 0; i < families.length; i++)
        {
            int w = (weights != null && i < weights.length) ? Math.max(0, weights[i]) : 1;
            acc += w;
            if (roll < acc)
            {
                return families[i];
            }
        }
        // unreachable (roll < total), but fall back defensively.
        return families[0];
    }

    /**
     * Every distinct rgnpc model id any family's roster can put on a garrison defender, collected across ALL families.
     * Only the OVERWORLD family wears rgnpc faces today; the others mint DragonMineZ saga entities, which never go
     * through {@link RgNpcModels}. Collecting across all families means a future rgnpc-faced family is guarded for free.
     */
    public static List<String> rgnpcModelIds()
    {
        List<String> out = new ArrayList<>();
        for (Family fam : Family.values())
        {
            for (Member m : fam.members)
            {
                if (m instanceof RgNpcMember rm && !out.contains(rm.modelId()))
                {
                    out.add(rm.modelId());
                }
            }
        }
        return out;
    }

    /**
     * Startup guard: every rgnpc model id this roster can spawn MUST resolve through {@link RgNpcModels#resolveId} to a
     * live bundled entry. A null resolution means {@code PlanetGarrisonDefenderEntity}/Model would silently fall back to
     * {@link RgNpcModels#DEFAULT_ID} (the 2stars Haze Shenron face), which is exactly the non-existent "upa" id bug this
     * guard exists to catch. Returns the list of ids that do NOT resolve (empty when all are fine) and logs each one
     * clearly. Never throws. Cheap (a dozen map lookups), so it is called on every server start.
     */
    public static List<String> validateRgNpcModelIds()
    {
        List<String> ids = rgnpcModelIds();
        List<String> bad = new ArrayList<>();
        for (String id : ids)
        {
            String resolved;
            try
            {
                resolved = RgNpcModels.resolveId(id);
            }
            catch (Throwable t)
            {
                resolved = null;
            }
            if (resolved == null)
            {
                bad.add(id);
                LoggingHandler.sulog.error(
                        "[PlanetGarrison] rgnpc garrison model id '{}' does NOT resolve to a bundled RgNpcModels entry; a "
                                + "defender wearing it would fall back to the default '{}' face (Haze Shenron). Add the "
                                + "bundled model or fix the id.", id, RgNpcModels.DEFAULT_ID);
            }
        }
        if (bad.isEmpty())
        {
            LoggingHandler.sulog.info(
                    "[PlanetGarrison] all {} rgnpc garrison model id(s) resolve to bundled entries (no default fallback).",
                    ids.size());
        }
        return bad;
    }

    // parse a stored family name back to the enum, or null if it is unknown (a config/family rename between saves).
    static Family byName(String name)
    {
        if (name == null)
        {
            return null;
        }
        try
        {
            return Family.valueOf(name);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }

    // ids we have already logged as missing, so a version drift warns ONCE per id, never per spawn (which would flood the
    // log every time a player lands on a saibaman/namekian planet). A present id is never entered here.
    private static final Set<String> LOGGED_MISSING = ConcurrentHashMap.newKeySet();

    // resolve a DragonMineZ entity path to its registered EntityType, or null if it is not registered in this build. A
    // null resolution logs once (latched) and returns null so the caller skips the row; it never throws.
    private static EntityType<?> resolveDmz(String path)
    {
        try
        {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(DMZ, path));
            if (type == null && LOGGED_MISSING.add(path))
            {
                LoggingHandler.sulog.warn(
                        "[PlanetGarrison] DragonMineZ entity '{}:{}' is not registered in this build; skipping that "
                                + "defender face (planets still get their other faces).", DMZ, path);
            }
            return type;
        }
        catch (Throwable t)
        {
            if (LOGGED_MISSING.add(path))
            {
                LoggingHandler.sulog.warn("[PlanetGarrison] Failed resolving DragonMineZ entity '{}:{}'; skipping "
                        + "that defender face.", DMZ, path, t);
            }
            return null;
        }
    }
}
