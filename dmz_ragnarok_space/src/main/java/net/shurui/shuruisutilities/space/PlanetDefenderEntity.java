package net.shurui.shuruisutilities.space;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;

/**
 * The server-side DEFENDER that holds the planet's answering ki wave during a beam clash. When a giant ball is fired
 * at a planet, this entity is spawned in the space dimension between the incoming ball and the planet centre; the
 * planet-buster module casts a {@link com.dragonminez.common.init.entities.ki.KiWaveEntity} FROM this entity back at
 * the ball, and DragonMineZ's own {@code BeamClashManager} pairs the two beams nose to nose into a clash struggle.
 * The player only busts the planet if their ball WINS that clash.
 *
 * <h2>Why a bespoke subclass and not the guild-raid clone</h2>
 * The guild-raid clone ({@link net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntity}) is a full,
 * fighting combat NPC with puppet machinery and guild-aware targeting; it is invisible only WHILE a client puppet
 * exists for it. This entity is the opposite: it never fights, never moves, and never renders. It exists only to be
 * the OWNER a DragonMineZ ki wave anchors to, so DragonMineZ's clash detector has a second qualifying beam to pair
 * with the ball. A tiny SU-owned subclass registered through {@link PlanetDefenderEntities} keeps it off the natural
 * spawn tables and gives us a clean type to sweep for orphans.
 *
 * <p>The look is INVISIBLE on purpose: the fiction is the PLANET firing back, not a person standing in space. The
 * answering ki wave is rendered invisible too (see {@link net.shurui.shuruisutilities.space.PlanetClash#DEFENDER_WAVE_MARKER}
 * and {@code MixinKiWaveRenderer}), so the struggle reads as the world itself resisting; only the attacker's own blast
 * or beam is seen straining against nothing. See {@link
 * net.shurui.shuruisutilities.client.space.PlanetDefenderRenderer}, which draws nothing at all.
 *
 * <p>DragonMineZ is a mandatory dependency of every addon in this suite, so extending {@link DBSagasEntity} directly
 * (no compat guard) is correct: the class simply cannot load without DragonMineZ, which is always present.
 *
 * <h2>NOT the visible garrison (do not confuse)</h2>
 * This INVISIBLE clash HOLDER is a different thing from the wild planet GARRISON (see {@link
 * net.shurui.shuruisutilities.space.PlanetGarrison} and {@link net.shurui.shuruisutilities.space.PlanetGarrisonRoster}).
 * A garrison defender is a VISIBLE, killable NPC that stands on a planet's SURFACE and must be beaten before a guild may
 * claim the world; this holder is invisible, invulnerable, never fights, and only exists in SPACE to anchor a ki wave
 * for the beam-clash gate. The two systems never share an entity or a type; only the word "defender" overlaps.
 */
public class PlanetDefenderEntity extends DBSagasEntity implements net.shurui.dev.sdu.api.SpaceDefenderNpc
{
    public PlanetDefenderEntity(EntityType<? extends DBSagasEntity> type, Level level)
    {
        super(type, level);
        // stationary, passive, and untouchable from the first tick it exists. DragonMineZ's freezeOwner will also
        // pin it once the clash locks, but we must not let it drift or take a hit BEFORE then, because a stray hit
        // that killed or moved it would dissolve the clash and hand the ball a free win.
        this.setInvisible(true);        // the fiction is the planet firing back; only the wave should be seen
        this.setNoAi(true);             // never wander: it only ever holds the wave in place
        this.setNoGravity(true);        // space is void; it must hang where it is placed, not fall
        this.setInvulnerable(true);     // a stray blast must never dissolve the clash by killing the holder
        this.setSilent(true);
        this.setPersistenceRequired();  // never despawn mid-clash; the module discards it on every exit path
        // per the suite convention: tell DragonMineZ its stats are hand-managed so its entity-join stat init does
        // not overwrite anything. This entity carries no meaningful DMZ stats (the wave's ki damage is the toughness
        // number, not this holder's stats), so the flag simply keeps DMZ from touching an entity that only holds a beam.
        this.getPersistentData().putBoolean("dmz_stats_configured", true);
    }

    /** Standard DBSagasEntity attribute base, reused verbatim. This holder never fights, so no values are overridden. */
    public static AttributeSupplier.Builder createAttributes()
    {
        return DBSagasEntity.createAttributes();
    }

    /**
     * Alive for as long as it exists, whatever its health says.
     *
     * <p>This is what makes a planet beam clash possible at all. {@code KiWaveEntity.tick()} discards itself on its
     * very first tick unless {@code getOwner()} is a {@code LivingEntity} that is ALIVE, and
     * {@code LivingEntity.isAlive()} is {@code !isRemoved() && getHealth() > 0}. The removal trace caught this holder
     * sitting at {@code hp=0.0} at exactly that moment, so the answering wave threw itself away one tick after
     * spawning, every single time, before the pairing test could ever run on it.
     *
     * <p>Setting the health at spawn was not enough: something zeroes it again between the spawn and the wave's first
     * tick, and the restore in {@code PlanetClash.keepAlive()} only runs on the next module tick, which is already too
     * late. That is also why the diagnostics disagreed with reality for so long, reporting a healthy 300/300 holder
     * that had in fact been at zero at the only instant that mattered.
     *
     * <p>Overriding the question rather than chasing the answer is safe HERE specifically, and would not be on an
     * ordinary mob. This entity is invulnerable, invisible, silent, has no AI, no goals and no transform chain, and
     * exists purely to be the owner of a ki wave for the length of one clash. It has no health bar anyone can see and
     * nothing that can damage it, so "how much health does it have" is a question with no meaning for it, while "does
     * it still exist" is the one every caller actually wants answered.
     */
    @Override
    public boolean isAlive()
    {
        return !this.isRemoved();
    }

    /**
     * No AI goals at all: this entity only ever stands still and holds a ki wave. DragonMineZ's inherited saga
     * combat goals would make it hunt players, so we deliberately do NOT call {@code super.registerGoals()}.
     */
    @Override
    protected void registerGoals()
    {
        // intentionally empty: no goals, no targeting, no movement.
    }

    /**
     * Never run DragonMineZ's native saga transform chain (which would spawn a new form entity and discard this one
     * mid-clash, orphaning the wave). Forcing this false keeps the entity identity stable for its whole short life.
     */
    @Override
    protected boolean hasTransformation()
    {
        return false;
    }

    /** Never advertises a next form, for the same reason {@link #hasTransformation()} is false. */
    @Override
    public EntityType<? extends DBSagasEntity> getNextTransform()
    {
        return null;
    }

    /**
     * Non-pushable: a colliding entity (or a shoved player) must not be able to nudge the holder off the clash line,
     * which could break the beam overlap DragonMineZ's detector needs and dissolve the struggle.
     */
    @Override
    public boolean isPushable()
    {
        return false;
    }

    /**
     * Model name handed to GeckoLib as a graceful-degradation fallback only. The holder's client renderer draws
     * nothing, so this is never actually used; a real DragonMineZ saga model id is returned so the resource always
     * resolves if some other path ever asks for it.
     */
    @Override
    public String getGeckolibModelName()
    {
        return "saga_zarbon";
    }
}
