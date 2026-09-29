package net.shurui.dev.sdu.client;

import com.dragonminez.client.gui.character.CharacterStatsScreen;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.shurui.dev.sdu.DmzNpc;

import java.util.UUID;

/**
 * Client-only holder for the staff {@code /dmzinfo} read-only viewer. Loaded only through
 * {@link net.minecraftforge.fml.DistExecutor} and client mixins, so it never reaches a dedicated server.
 *
 * <p>While {@link #active()} is true, {@code StatsProviderViewMixin} makes DMZ's V-menu screens read the
 * detached {@link #viewed()} data (for the LOCAL player lookup only), {@code NetworkHandlerReadOnlyMixin}
 * drops every DMZ client-to-server packet so the staff member cannot modify their own or the target's data,
 * and {@link PlayerInfoViewEvents} draws the "Viewing: <name>" label and clears the view the moment the staff
 * member leaves DMZ's character-menu screens.
 *
 * <p>Viewing is keyed to being inside DMZ's V menu, not to a single screen instance, so it survives the tab
 * switches between the six menu screens (each switch constructs a new sibling screen).
 */
public final class PlayerInfoView {

    private static volatile boolean active;
    private static volatile StatsData viewed;
    private static volatile String targetName = "";
    private static volatile UUID targetUuid;
    private static volatile boolean syncing;

    private PlayerInfoView() {
    }

    /**
     * Deserialise the target's DMZ blob into a detached {@link StatsData}, flip viewing on and open DMZ's
     * character/stats screen. The constructor needs any {@code Player} reference; the local player is used and
     * is only a backing reference, never mutated. DMZ's {@code StatsData.load} is all-or-nothing on a full
     * {@code save()} blob (it depends on the {@code PlayerQuestData} tag), so a partial or bad blob aborts the
     * open cleanly rather than showing half a sheet.
     */
    public static void open(String name, UUID uuid, CompoundTag nbt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        if (nbt == null || nbt.isEmpty()) {
            DmzNpc.LOGGER.warn("[{}] /dmzinfo: empty DMZ data for {}; not opening a viewer.", DmzNpc.MODID, name);
            return;
        }
        StatsData detached;
        try {
            detached = new StatsData(mc.player);
            detached.load(nbt);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] /dmzinfo: could not load DMZ data for {} ({}); not opening a viewer.",
                    DmzNpc.MODID, name, t.toString());
            return;
        }
        viewed = detached;
        targetName = name == null ? "" : name;
        targetUuid = uuid;
        active = true;
        // Open DMZ's own V-menu entry screen. Its init() reads the local player's StatsData through
        // StatsProvider.get, which StatsProviderViewMixin now redirects to the detached viewed data.
        mc.setScreen(new CharacterStatsScreen());
    }

    /** True while the staff member is inside DMZ's V menu viewing another player. */
    public static boolean active() {
        return active && viewed != null;
    }

    /** The detached, read-only StatsData of the viewed player, or null when not viewing. */
    public static StatsData viewed() {
        return viewed;
    }

    /**
     * True while DragonMineZ is applying one of its own stat-sync packets, which is the ONE window in which
     * {@code StatsProviderViewMixin} must not redirect the local player's lookup.
     *
     * <p>Why it is needed: {@code ClientPacketHandler.handleStatsSyncPacket} resolves the target's data through the
     * very same {@code StatsProvider.get} the screens use, and then calls {@code StatsData.load(nbt)} on whatever it
     * gets back. Redirected, that write lands in the DETACHED viewed copy and overwrites the target's sheet with the
     * staff member's own numbers. All four sync packets (resource, stats, progression, appearance) funnel through
     * that one method, and the resource one fires constantly, so the viewer showed the target for about a frame and
     * then silently became a mirror of the viewer.
     *
     * <p>The flag makes the viewed copy unreachable from that path without dropping the packet, so the staff
     * member's OWN client data stays live and correct underneath the viewer and is right the moment it closes.
     */
    public static boolean syncing() {
        return syncing;
    }

    /**
     * Mark DragonMineZ's stat-sync write as in progress, or finished.
     *
     * <p>Both ends run on the client thread (Forge's {@code enqueueWork}), the same thread the screens read on, so
     * this is a plain flag and not a lock. It is set at the head and cleared at the return of DMZ's handler; should
     * that handler throw between the two (it wraps a load failure in a RuntimeException), the flag self-heals on the
     * next sync that completes, and in the meantime the viewer shows the staff member's own data rather than
     * corrupting the target's.
     */
    public static void syncing(boolean value) {
        syncing = value;
    }

    /** Display name of the viewed player. */
    public static String targetName() {
        return targetName;
    }

    /** UUID of the viewed player, or null. */
    public static UUID targetUuid() {
        return targetUuid;
    }

    /** End viewing mode and drop the detached data. Idempotent. */
    public static void clear() {
        active = false;
        viewed = null;
        targetName = "";
        targetUuid = null;
        syncing = false;
    }
}
