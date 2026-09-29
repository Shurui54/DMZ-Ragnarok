package net.shurui.shuruisutilities.tasks;

import net.minecraft.nbt.CompoundTag;

/**
 * One of a board's three rows, for one player: which task is sitting there and how far along it is.
 *
 * <h2>The state machine</h2>
 * <pre>
 *   OFFERED  --accept(green)-->  ACTIVE  --goal met-->  COMPLETE  --claim(yellow)-->  (rerolled)
 *      ^                            |                       |
 *      |                     abandon(red)                   |  reward is NEVER lost:
 *      +------ reroll (zeni) -------+                       |  a complete slot holds
 *                                                           |  past the reset until claimed
 * </pre>
 *
 * <h2>Expiry holds for an unclaimed reward</h2>
 * Every slot remembers the period {@link TaskPeriod#stamp} it was rolled in. When the board turns over, a slot
 * whose stamp is stale is rerolled - EXCEPT one sitting on {@link State#COMPLETE}, which is left exactly where it
 * is. A player who finished a daily at ten to midnight and logged off has not lost it; the row waits. The instant
 * they claim, the slot notices its stamp is stale and rerolls on the spot, so they are not made to wait another
 * whole period for the row to become useful again.
 *
 * <p>That is the reason claiming and rerolling are the same moment in the code rather than two events: any other
 * arrangement either drops the reward or leaves a dead row until the next reset.
 */
public class TaskSlot
{
    public enum State
    {
        /** Rolled and on offer. The player has not taken it. */
        OFFERED,
        /** Accepted. Progress counts toward it. Only one slot per board may be here at a time. */
        ACTIVE,
        /** Goal met, reward waiting. Survives a reset; claiming it is what finally frees the row. */
        COMPLETE,
        /**
         * Claimed and spent for this period.
         *
         * <p>Needed because claiming inside the period the task was rolled in must NOT hand the same task back to
         * be done again: the row is finished until the board turns over. Without this state a claimed row would be
         * indistinguishable from an unclaimed one and a daily could be farmed all day.
         */
        CLAIMED
    }

    /** Which {@link TaskDef} is in this row, or blank when the pool had nothing to offer. */
    public String defId = "";

    public State state = State.OFFERED;

    /** Counted toward {@link TaskDef#amount}. Only moves while {@link State#ACTIVE}. */
    public int progress = 0;

    /** The {@link TaskPeriod#stamp} this row was rolled in. A stale one means the board has turned over. */
    public long rolledStamp = Long.MIN_VALUE;

    public boolean isEmpty()
    {
        return defId == null || defId.isBlank();
    }

    /**
     * Whether this row's reroll button should be offered.
     *
     * <p>Anything but the task actually being worked on, and not one already claimed: paying to reroll a row that
     * is spent until the reset anyway would be selling the player nothing.
     */
    public boolean canReroll()
    {
        return state != State.ACTIVE && state != State.CLAIMED;
    }

    /**
     * Whether the board turning over should replace what is in this row.
     *
     * <p>False for a COMPLETE row however old it is, which is the "rewards are never lost" rule; false for a row
     * rolled in the current period, which is the ordinary case.
     */
    public boolean expiredBy(long currentStamp)
    {
        return state != State.COMPLETE && rolledStamp != currentStamp;
    }

    /** Put a fresh task in this row. Progress and state go back to the start; the stamp records when. */
    public void fill(String newDefId, long stamp)
    {
        this.defId = newDefId == null ? "" : newDefId;
        this.state = State.OFFERED;
        this.progress = 0;
        this.rolledStamp = stamp;
    }

    public CompoundTag save()
    {
        CompoundTag t = new CompoundTag();
        t.putString("Def", defId == null ? "" : defId);
        t.putString("State", state.name());
        t.putInt("Progress", progress);
        t.putLong("Stamp", rolledStamp);
        return t;
    }

    public static TaskSlot load(CompoundTag t)
    {
        TaskSlot s = new TaskSlot();
        s.defId = t.getString("Def");
        try
        {
            s.state = State.valueOf(t.getString("State"));
        }
        catch (Throwable ignored)
        {
            // An unknown state (hand edit, or one retired in a later version) reads as on-offer rather than
            // failing the load: the worst that costs a player is re-accepting a row.
            s.state = State.OFFERED;
        }
        s.progress = Math.max(0, t.getInt("Progress"));
        s.rolledStamp = t.contains("Stamp") ? t.getLong("Stamp") : Long.MIN_VALUE;
        return s;
    }
}
