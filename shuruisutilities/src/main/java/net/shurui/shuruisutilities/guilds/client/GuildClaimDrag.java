package net.shurui.shuruisutilities.guilds.client;

/**
 * The chunk a world-map claim drag started on, and the command it turns into when the button comes back up.
 *
 * <p>Client state only, and only ever one drag at a time, so a plain pair of fields is enough; the map screen is
 * driven from the render thread and nothing else touches these.
 *
 * <p>It decides nothing about whether a claim is allowed. The whole rectangle goes to {@code /guild claimarea} and
 * the server applies the same guild, permission, limit and bank checks a single claim gets. A client that lied about
 * the rectangle would still be refused chunk by chunk.
 */
public final class GuildClaimDrag
{
    private GuildClaimDrag() {}

    private static boolean dragging;
    private static int anchorChunkX;
    private static int anchorChunkZ;

    /** Remember where a drag began. */
    public static void begin(int chunkX, int chunkZ)
    {
        dragging = true;
        anchorChunkX = chunkX;
        anchorChunkZ = chunkZ;
    }

    public static boolean isDragging()
    {
        return dragging;
    }

    /** The chunk the drag started on, for anything that wants to draw the pending rectangle. */
    public static int anchorChunkX()
    {
        return anchorChunkX;
    }

    public static int anchorChunkZ()
    {
        return anchorChunkZ;
    }

    /**
     * End the drag and ask the server for the rectangle it covered.
     *
     * <p>A drag that never left its starting chunk is sent anyway rather than being treated as a misclick: a
     * one-chunk rectangle is a perfectly ordinary thing to want, and silently doing nothing after a deliberate
     * shift-drag would read as the feature being broken.
     */
    public static void finish(int chunkX, int chunkZ)
    {
        if (!dragging)
            return;
        dragging = false;
        GuildGuiClient.run("guild claimarea " + anchorChunkX + " " + anchorChunkZ + " " + chunkX + " " + chunkZ);
    }

    /** Drop a drag in progress without sending anything, e.g. when the map screen closes mid-drag. */
    public static void cancel()
    {
        dragging = false;
    }
}
