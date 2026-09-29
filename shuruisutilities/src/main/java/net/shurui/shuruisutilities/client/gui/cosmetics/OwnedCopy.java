package net.shurui.shuruisutilities.client.gui.cosmetics;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;

/**
 * One COPY of a cosmetic the local player owns, as the wardrobe packet describes it.
 *
 * <p>The wire is {@code PacketEditorData}'s untyped rows, which is what every SU hub editor uses, so the parsing
 * lives here once rather than in each screen that reads it. Everything is read defensively past the end of the
 * row for the same reason the rest of the hub does it: an older or newer server must produce a duller screen,
 * never a crash.
 *
 * <p>Row shape, from {@code CosmeticEditorServer.openWardrobe}:
 * {@code [catalogId, displayName, slotKey, qualityKey, trackerCount, chosenTrackerId, rarity, instanceId,
 * effectId, shownCount, effectName]}.
 *
 * <p>Nothing here is authority. An equip is re-checked against the ownership ledger server side, so a forged row
 * buys a refusal.
 */
public final class OwnedCopy
{
    public final String catalogId;
    public final String name;
    public final String slotKey;
    public final CosmeticQuality quality;

    /** How many trackers the DEFINITION carries, which is what decides whether a counter control is offered. */
    public final int trackerCount;

    /** Which tracker the player chose to display, blank for none. Per cosmetic, not per copy. */
    public final String chosenTracker;

    public final String rarity;

    /** The copy's own id. Blank only from a server older than the per-copy rows. */
    public final String instanceId;

    /** The rolled effect, blank unless this copy is Magic. */
    public final String effectId;

    /** The count to show on this copy, already resolved server side. */
    public final long count;

    /**
     * The authored name of the rolled effect, resolved server side.
     *
     * <p>It has to come from the server: effects are not in the catalogue packet, so a client has nothing to
     * look an effect id up in. Blank when the copy is not Magic, or when its effect record was deleted, which
     * {@link #effectLabel()} answers by prettifying the id instead.
     */
    public final String effectName;

    private OwnedCopy(List<String> row)
    {
        this.catalogId = at(row, 0);
        String display = at(row, 1);
        this.name = display.isBlank() ? this.catalogId : display;
        this.slotKey = at(row, 2);
        this.quality = CosmeticQuality.byKey(at(row, 3));
        this.trackerCount = (int) parse(at(row, 4));
        this.chosenTracker = at(row, 5);
        this.rarity = at(row, 6);
        this.instanceId = at(row, 7);
        this.effectId = at(row, 8);
        this.count = parse(at(row, 9));
        this.effectName = at(row, 10);
    }

    public static List<OwnedCopy> parse(List<List<String>> rows)
    {
        List<OwnedCopy> out = new ArrayList<>();
        if (rows == null)
            return out;
        for (List<String> row : rows)
            if (row != null && !row.isEmpty() && !row.get(0).isBlank())
                out.add(new OwnedCopy(row));
        return out;
    }

    /** The effect as a player should read it: the authored name, else the id prettified. Blank for neither. */
    public String effectLabel()
    {
        if (!effectName.isBlank())
            return effectName;
        return CosmeticStyle.effectName(effectId);
    }

    /** The corner number for a tile, or blank when this copy counts nothing worth showing. */
    public String counterLabel()
    {
        return quality.usesCounters() && count > 0 ? Long.toString(count) : "";
    }

    private static String at(List<String> row, int i)
    {
        String v = row.size() > i ? row.get(i) : "";
        return v == null ? "" : v;
    }

    private static long parse(String s)
    {
        try
        {
            return Long.parseLong(s.trim());
        }
        catch (RuntimeException e)
        {
            return 0L;
        }
    }
}
