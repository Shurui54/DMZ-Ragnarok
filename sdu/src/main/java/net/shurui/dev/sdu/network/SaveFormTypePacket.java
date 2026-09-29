package net.shurui.dev.sdu.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.form.FormTypeManager;
import net.shurui.dev.sdu.form.FormTypeMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client -> server. Registers or unregisters a custom form type in skills.json. Op-gated.
 * {@code remove=true} deletes it; else a <em>stack</em> skill (per-level TP {@code costs}) when {@code stack}
 * is set, otherwise a plain <em>form</em> skill.
 *
 * <p>A non-remove save also carries presentation meta: {@code iconBase} (one of DMZ's six stock icon names)
 * and {@code tint} ({@code 0xRRGGBB}, or {@code -1} = use the form's aura colour), persisted to
 * {@code sdu_formtype_meta.json} and re-synced to every client.
 */
public class SaveFormTypePacket {

    private final String type;
    private final boolean remove;
    private final boolean stack;
    private final List<Integer> costs;
    private final String iconBase;
    private final int tint;

    /** Legacy constructor (no meta): defaults to the stock icon and aura-colour tint. */
    public SaveFormTypePacket(String type, boolean remove, boolean stack, List<Integer> costs) {
        this(type, remove, stack, costs, FormTypeMeta.DEFAULT_ICON, FormTypeMeta.USE_AURA_TINT);
    }

    public SaveFormTypePacket(String type, boolean remove, boolean stack, List<Integer> costs,
                              String iconBase, int tint) {
        this.type = type;
        this.remove = remove;
        this.stack = stack;
        this.costs = costs == null ? List.of() : costs;
        this.iconBase = FormTypeMeta.normalizeIcon(iconBase);
        this.tint = tint < 0 ? FormTypeMeta.USE_AURA_TINT : (tint & 0xFFFFFF);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(type);
        buf.writeBoolean(remove);
        buf.writeBoolean(stack);
        buf.writeVarInt(costs.size());
        for (int c : costs) {
            // +1 so the -1 sentinel (DMZ "always-available" cost, used by ultimate) stays a non-negative varint;
            // floor other negatives to 0 first.
            buf.writeVarInt((c == -1 ? -1 : Math.max(0, c)) + 1);
        }
        buf.writeUtf(iconBase);
        buf.writeVarInt(tint + 1); // shift so -1 (use aura) stays a non-negative varint
    }

    public static SaveFormTypePacket decode(FriendlyByteBuf buf) {
        String type = buf.readUtf();
        boolean remove = buf.readBoolean();
        boolean stack = buf.readBoolean();
        int n = buf.readVarInt();
        List<Integer> costs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            costs.add(buf.readVarInt() - 1); // undo the +1 sentinel shift from encode
        }
        String iconBase = buf.readUtf();
        int tint = buf.readVarInt() - 1;
        return new SaveFormTypePacket(type, remove, stack, costs, iconBase, tint);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            FormTypeMeta meta = new FormTypeMeta(iconBase, tint);
            // kaioken/ultimate are DMZ STACK defaults and must never leave stackSkills, or DMZ drops them from
            // the form purchase menu. Force the stack path if a stale/tampered packet saves one as a plain form.
            // (FormTypeManager.add guards too, but this keeps the addStack cost handling and message.)
            boolean effStack = stack
                    || FormTypeManager.STACK_DEFAULTS.contains(net.shurui.dev.sdu.util.SduIds.sanitize(type));
            String err = remove ? FormTypeManager.remove(type)
                    : effStack ? FormTypeManager.addStack(type, costs, meta)
                    : FormTypeManager.add(type, meta);
            if (err == null) {
                // resync icon/tint meta so radial and skills-screen mixins pick it up without a rejoin
                net.shurui.dev.sdu.network.DmzNet.syncFormTypeMetaToAll();
            }
            if (remove && err == null) {
                // Strip the deleted skill from online players now (offline covered by tombstone +
                // SkillsRepairMixin). remove() sanitizes the type into the tombstoned key, so scrub sanitized too.
                net.shurui.dev.sdu.form.FormTombstoneScrub.scrubSkillOnline(
                        player.getServer(), net.shurui.dev.sdu.util.SduIds.sanitize(type));
            }
            String okKey = remove
                    ? (effStack ? "message.dmz_ragnarok.npc.formtype.removed_stack" : "message.dmz_ragnarok.npc.formtype.removed")
                    : (effStack ? "message.dmz_ragnarok.npc.formtype.registered_stack" : "message.dmz_ragnarok.npc.formtype.registered");
            player.displayClientMessage(err == null
                    ? Component.translatable(okKey, type)
                    : Component.translatable("message.dmz_ragnarok.npc.formtype.change_failed", err), false);
        });
        context.setPacketHandled(true);
    }
}
