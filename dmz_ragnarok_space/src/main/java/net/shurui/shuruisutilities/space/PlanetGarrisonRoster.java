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
    // saibaman ships as six texture variants saga_saibaman1..6 (all SagaSaibamanEntity, a full combat DBSagasEntity), so
    // the SAIBAMEN family draws evenly from all six. The enemy namekian is cc_namekian; namek_warrior is a second DMZ
    // namekian face. The frost-demon family draws on the three Frieza-Force soldiers plus a Moro soldier; the robot family
    // draws on the geti-star robots. Ids are held as strings and resolved lazily, never as compiled class references, per
    // the class note.
    private static final String DMZ = "dragonminez";
    private static final String[] SAIBAMAN_IDS =
            {"saga_saibaman1", "saga_saibaman2", "saga_saibaman3", "saga_saibaman4", "saga_saibaman5", "saga_saibaman6"};
    private static final String NAMEKIAN_ENEMY_ID = "cc_namekian";
    private static final String NAMEKIAN_WARRIOR_ID = "namek_warrior";
    private static final String[] FROST_DEMON_IDS =
            {"saga_friezasoldier01", "saga_friezasoldier02", "saga_friezasoldier03", "saga_morosoldier"};
    private static final String[] ROBOT_DMZ_IDS =
            {"robot1", "robot2", "robot3", "robotxv", "saga_gete_robot"};

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
        // Earth's fighters: pure rgnpc faces.
        OVERWORLD(rgnpc("nam", "upa", "kingchappa", "monaka", "mastershen", "jackiechun", "ninjamurasaki", "grandpagohan")),
        // Namekians: MIXED, DMZ's own enemy namekian and namek warrior plus the rgnpc slug soldier.
        NAMEKIAN(namekianMembers()),
        // Saibamen: DMZ's own saibaman mob ONLY (SU has no saibaman art of its own).
        SAIBAMEN(saibamanMembers()),
        // Frost demons: DMZ's own Frieza-Force soldiers and a Moro soldier ONLY (all DMZ faces).
        FROST_DEMON(frostDemonMembers()),
        // Robots: MIXED, DMZ's geti-star robots plus the rgnpc bio-android faces.
        ROBOT(robotMembers());

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

    // NAMEKIAN is a mixed family: DMZ's own enemy namekian and namek warrior plus the rgnpc slug soldier (a generic
    // green namekian-shaped face). Both DMZ ids resolve lazily, so a version drift on either degrades to fewer faces.
    private static List<Member> namekianMembers()
    {
        List<Member> out = new ArrayList<>();
        out.add(new DmzMember(NAMEKIAN_ENEMY_ID));
        out.add(new DmzMember(NAMEKIAN_WARRIOR_ID));
        out.add(new RgNpcMember("slugsoldier"));
        return out;
    }

    // SAIBAMEN is pure DMZ: one row per saibaman texture variant.
    private static List<Member> saibamanMembers()
    {
        List<Member> out = new ArrayList<>(SAIBAMAN_IDS.length);
        for (String id : SAIBAMAN_IDS)
        {
            out.add(new DmzMember(id));
        }
        return out;
    }

    // FROST_DEMON is pure DMZ: the three Frieza-Force soldiers plus a Moro soldier.
    private static List<Member> frostDemonMembers()
    {
        List<Member> out = new ArrayList<>(FROST_DEMON_IDS.length);
        for (String id : FROST_DEMON_IDS)
        {
            out.add(new DmzMember(id));
        }
        return out;
    }

    // ROBOT is a mixed family: DMZ's geti-star robots plus the rgnpc bio-android face (biomen). The six biowarrior1..6
    // faces were dropped: they are the only high-resolution (1280x640) models reachable by random selection, and the
    // user wants no high-res NPCs on planets. biomen and everything else in the roster are 256x256 or smaller, so the
    // family is still well populated (5 DMZ robots + biomen).
    private static List<Member> robotMembers()
    {
        List<Member> out = new ArrayList<>();
        for (String id : ROBOT_DMZ_IDS)
        {
            out.add(new DmzMember(id));
        }
        out.add(new RgNpcMember("biomen"));
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
