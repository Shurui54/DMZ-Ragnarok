package net.shurui.shuruisutilities.teleport.portal;

import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.util.NamedWorldArea;
import net.shurui.shuruisutilities.util.NamedWorldPoint;

/**
 * 
 */
public class Portal
{

    /** Fill-block texture styles this server-only portal supports. */
    public static final String FILL_NETHER = "nether";

    public static final String FILL_END = "end";

    public static final String DEFAULT_FRAME_MATERIAL = "minecraft:obsidian";

    /** Symbolic target kinds. {@link #KIND_COORD} is the legacy fixed-coordinate target stored in {@link #target};
     * the others carry no baked coordinate and resolve at walk-through time so a portal follows the spawn/floor as
     * it moves, is regenerated, or its theme changes. */
    public static final String KIND_COORD = "coord";

    public static final String KIND_SPAWN = "spawn";

    public static final String KIND_FLOOR = "floor";

    public static final String KIND_NEXT_FLOOR = "nextfloor";

    protected NamedWorldArea portalArea;

    protected NamedWorldPoint target;

    /** Which target this portal resolves. Defaults to {@link #KIND_COORD} so portals saved before symbolic targets
     * (their JSON has no field, and GSON allocates without running initializers) still read as coordinate portals via
     * the null-guard in {@link #getTargetKind()}. */
    protected String targetKind = KIND_COORD;

    /** Meaningful only for {@link #KIND_FLOOR}: the 1-based dungeon floor this portal targets. */
    protected int targetFloor = 0;

    /**
     * When set, this portal turns a player away unless they have redeemed the dungeon floor ticket for the floor it
     * leads to. The check itself lives in the dungeons addon, which owns tickets and floors; SU only records the
     * operator's intent so the flag survives a restart and can be set per portal.
     *
     * <p>Independent of a floor's own {@code requireticket} setting rather than replacing it. A floor can be sealed
     * everywhere by its own config, or left open while ONE particular door into it asks for the ticket; either
     * switch alone is enough to lock a portal.
     *
     * <p>Defaults false so portals saved before this existed deserialize unlocked (GSON allocates without running
     * initializers, and a missing boolean field reads as false either way).
     */
    protected boolean requireTicket = false;

    /**
     * When set, this portal is a BOSS portal: it stays locked while the boss of the dungeon boss floor it sits in is
     * still alive, and opens (behaves as a normal portal, resolving whatever target it carries) once that boss is
     * killed. The alive/killed state is the floor's own {@code bossDefeated} flag, which the dungeons addon owns,
     * persists and syncs across shards, so the boss (which lives on one shard) opening the portal reaches every shard.
     * SU only records the operator's intent so the flag survives a restart and can be set per portal.
     *
     * <p>Defaults false so portals saved before this existed deserialize unlocked (GSON allocates without running
     * initializers, and a missing boolean field reads as false either way).
     */
    protected boolean bossLock = false;

    protected boolean frame = true;

    /**
     * Registry id of the block used for the frame ring around the portal opening (e.g. {@code minecraft:obsidian},
     * {@code minecraft:gold_block}). Fields default so portals saved before this feature still deserialize.
     */
    protected String frameMaterial = DEFAULT_FRAME_MATERIAL;

    /** Portal fill texture: {@link #FILL_NETHER} (purple nether portal) or {@link #FILL_END} (end portal). */
    protected String fillType = FILL_NETHER;

    /**
     * Dye-color name (e.g. {@code "cyan"}) for the colored nether-portal fill block. Empty = default purple.
     * Only meaningful for {@link #FILL_NETHER}.
     */
    protected String fillColor = "";

    /**
     * For {@link #FILL_END} portals: {@code true} fills with the custom vertical colored end-portal plane;
     * {@code false} fills with the real (horizontal) vanilla end portal. Defaults on so existing/new end
     * portals stand upright.
     */
    protected boolean fillVertical = true;

    /**
     * What the frame build wrote OVER when this portal was created: "x,y,z" to the block state that stood there, for
     * every position of the opening and frame ring that was not air. Deleting or recreating the portal puts these
     * back instead of leaving air, so a portal set into a floor no longer leaves a hole the size of its frame.
     *
     * <p>Null for portals created before this existed (GSON leaves a missing field null and skips null on save, so
     * their files are unchanged); those keep the old clear-to-air behaviour.
     */
    protected java.util.Map<String, String> replacedBlocks;

    public Portal(NamedWorldArea portalArea, NamedWorldPoint target, boolean frame)
    {
        this.portalArea = portalArea;
        this.target = target;
        this.frame = frame;
    }

    public Portal(NamedWorldArea portalArea, NamedWorldPoint target, boolean frame, String frameMaterial,
            String fillType)
    {
        this.portalArea = portalArea;
        this.target = target;
        this.frame = frame;
        if (frameMaterial != null && !frameMaterial.isEmpty())
            this.frameMaterial = frameMaterial;
        if (fillType != null && !fillType.isEmpty())
            this.fillType = fillType;
    }

    public Portal(WorldArea portalArea, WorldPoint target, boolean frame)
    {
        this(new NamedWorldArea(portalArea), new NamedWorldPoint(target), frame);
    }

    public NamedWorldArea getPortalArea()
    {
        return portalArea;
    }

    public void setPortalArea(NamedWorldArea portalArea)
    {
        this.portalArea = portalArea;
    }

    public NamedWorldPoint getTarget()
    {
        return target;
    }

    public void setTarget(NamedWorldPoint target)
    {
        this.target = target;
    }

    /** @return this portal's symbolic target kind, treating a legacy/absent value as {@link #KIND_COORD}. */
    public String getTargetKind()
    {
        return targetKind == null || targetKind.isEmpty() ? KIND_COORD : targetKind;
    }

    public void setTargetKind(String targetKind)
    {
        this.targetKind = targetKind;
    }

    public int getTargetFloor()
    {
        return targetFloor;
    }

    public void setTargetFloor(int targetFloor)
    {
        this.targetFloor = targetFloor;
    }

    /** @return whether this portal is locked behind the floor ticket for the floor it leads to. */
    public boolean isRequireTicket()
    {
        return requireTicket;
    }

    public void setRequireTicket(boolean requireTicket)
    {
        this.requireTicket = requireTicket;
    }

    /** @return whether this portal stays shut while its dungeon boss floor's guardian is still alive. */
    public boolean isBossLock()
    {
        return bossLock;
    }

    public void setBossLock(boolean bossLock)
    {
        this.bossLock = bossLock;
    }

    public boolean hasFrame()
    {
        return frame;
    }

    public void setFrame(boolean frame)
    {
        this.frame = frame;
    }

    public String getFrameMaterial()
    {
        return frameMaterial == null || frameMaterial.isEmpty() ? DEFAULT_FRAME_MATERIAL : frameMaterial;
    }

    public void setFrameMaterial(String frameMaterial)
    {
        this.frameMaterial = frameMaterial;
    }

    public String getFillType()
    {
        return FILL_END.equalsIgnoreCase(fillType) ? FILL_END : FILL_NETHER;
    }

    public void setFillType(String fillType)
    {
        this.fillType = fillType;
    }

    /** @return the fill dye-color name, or an empty string for a vanilla (uncolored) fill. */
    public String getFillColor()
    {
        return fillColor == null ? "" : fillColor;
    }

    public void setFillColor(String fillColor)
    {
        this.fillColor = fillColor == null ? "" : fillColor;
    }

    /** @return whether an end portal fill stands vertical (custom block) rather than lying flat (vanilla). */
    public boolean isFillVertical()
    {
        return fillVertical;
    }

    public void setFillVertical(boolean fillVertical)
    {
        this.fillVertical = fillVertical;
    }

    /** @return the blocks the frame build replaced at creation, or null when none were recorded. */
    public java.util.Map<String, String> getReplacedBlocks()
    {
        return replacedBlocks;
    }

    public void setReplacedBlocks(java.util.Map<String, String> replacedBlocks)
    {
        this.replacedBlocks = replacedBlocks;
    }

}
