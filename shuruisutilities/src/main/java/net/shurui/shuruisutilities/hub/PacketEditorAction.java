package net.shurui.shuruisutilities.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: a generic edit from one of the hub editors. {@link #editor} selects which module,
 * {@link #action} the operation (e.g. {@code add}, {@code remove}, {@code set}, {@code save}, {@code delete}),
 * and {@link #args} its parameters. Op-gated; the server applies it via {@link EditorServer} and re-sends a
 * refreshed {@link PacketEditorData}.
 */
public class PacketEditorAction implements ISUPacket
{
    public String editor = "";
    public String action = "";
    public List<String> args = new ArrayList<>();

    public PacketEditorAction() {}

    public PacketEditorAction(String editor, String action, List<String> args)
    {
        this.editor = editor == null ? "" : editor;
        this.action = action == null ? "" : action;
        this.args = args == null ? new ArrayList<>() : args;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(editor);
        buf.writeUtf(action);
        buf.writeVarInt(args.size());
        for (String s : args)
            buf.writeUtf(s == null ? "" : s);
    }

    public static PacketEditorAction decode(FriendlyByteBuf buf)
    {
        PacketEditorAction p = new PacketEditorAction();
        p.editor = buf.readUtf();
        p.action = buf.readUtf();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.args.add(buf.readUtf());
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null)
            return;
        // Most editors are admin config tools and stay op-gated. A small whitelist of player-facing tools
        // (their handlers only mutate the sender's own state and re-validate server-side) is allowed for
        // any player. The consensual-PvP toggle only ever sets the sender's own stored preference; the
        // effective PvP decision is still computed server-side against PlayerInfo + BountyManager.
        if (!isPlayerEditor(editor) && !player.hasPermissions(2))
            return;
        EditorServer.handle(player, editor, action, args);
    }

    /** Editors any player may drive (only mutate the sender's own data, re-validated server-side). */
    private static boolean isPlayerEditor(String editor)
    {
        // auction and trade actions are driven by any player; both re-validate ownership/participation and escrow, and
        // never trust a client-sent item (they read the sender's own held/slotted stack server-side).
        // cosmetics: the only action starts the sender's OWN Patreon link flow, re-validated server-side.
        // stafftasks: every action re-checks the sender's roster role, clock and ownership in StaffTaskManager,
        // so a helper driving it is exactly as safe as an admin doing so. Op-gating it would have made the board
        // unusable for the people it exists for.
        // taskboard: the player daily/weekly/monthly board. accept/abandon/claim/reroll in TaskManager only ever
        // touch the sender's own PlayerTasks boards and their own economy balance, and each re-validates server
        // side (accept refuses when a row is already active, reroll checks affordability, claim checks COMPLETE).
        // Without this, every action was dropped here for non-op players, so the board opened but Accept did
        // nothing; the ReleaseToggles.TASK_BOARD_GUI gate still lives in HubRowTasks.
        // wardrobe: equip, unequip and settracker only ever touch the sender's own equipped map, and each is
        // re-validated in WardrobeManager against the ownership ledger and the catalogue, so a client naming a
        // cosmetic it does not own is refused rather than dressed. The ADMIN catalogue editors
        // ("cosmetics_admin", "cosmetic_crates", "cosmetic_shop") are deliberately NOT here and stay op-gated.
        // shop: the only action is "buy", which re-validates the listing, the window, the per-player limit and the
        // balance server side in CosmeticShop and charges atomically, so a forged buy buys a refusal.
        // cosmetic_mounts, cosmetic_animations: the player mounts and animations screens. equip/unequip touch only
        // the sender's own equipped map and re-validate in WardrobeManager; summon and preview are the existing
        // authored paths (CosmeticMountManager, CosmeticAnimationServer), each gated server side. Same standing as
        // "wardrobe": player-facing, not the op-gated catalogue editors.
        // eventhub: the player event hub. claim/buy only ever touch the sender's own quest claims (exactly-once in
        // the event ledger) and their own event-token inventory (re-validated server side in EventQuests/EventShop),
        // so a forged action buys a refusal, never someone else's reward.
        return "pvptoggle".equals(editor) || "auction".equals(editor) || "trade".equals(editor)
                || "cosmetics".equals(editor) || "stafftasks".equals(editor) || "taskboard".equals(editor)
                || "wardrobe".equals(editor) || "shop".equals(editor)
                || "cosmetic_mounts".equals(editor) || "cosmetic_animations".equals(editor)
                || "eventhub".equals(editor);
    }

    public static void handler(final PacketEditorAction message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
