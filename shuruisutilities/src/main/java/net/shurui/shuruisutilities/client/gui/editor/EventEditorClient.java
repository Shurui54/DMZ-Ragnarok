package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import net.shurui.dev.sdu.api.ClientGate;

/**
 * Client sink for the admin event editor (packet 128, {@code PacketEventEditorOpen}). It lives in a CLIENT-only
 * class so the packet class can reference it through {@code DistExecutor} without ever classloading a {@code Screen}
 * on a dedicated server. Every open is gated on {@link ClientGate#feature(String)} for {@code "events"}: a keyless
 * server never reports the feature, so a crafted or stale packet cannot pop the editor.
 *
 * <p>The payload is one compound: {@code mode="list"} carries an {@code events} list (id/name/status rows) that
 * opens {@link EventListScreen}; {@code mode="one"} carries the whole {@code event} def plus {@code picks} lists
 * that opens {@link EventEditScreen}; {@code mode="floor"} carries one copied dungeon floor config ({@code floor}) and
 * its default {@code label}, appended to the already-open editor's local def (the reply to its "addfloor" action).
 */
public final class EventEditorClient
{
    private EventEditorClient() {}

    public static void open(CompoundTag data)
    {
        if (data == null || !ClientGate.feature("events"))
            return;
        Minecraft mc = Minecraft.getInstance();
        String mode = data.getString("mode");
        if ("floor".equals(mode))
        {
            // The answer to an "addfloor" action: a copied floor config for the OPEN editor. Applied to its local
            // def so no other unsaved edit is lost; ignored if the admin has since left the editor.
            if (mc.screen instanceof EventEditScreen screen)
                screen.acceptFloor(data.getString("label"), data.getCompound("floor"));
            return;
        }
        if ("one".equals(mode))
        {
            mc.setScreen(new EventEditScreen(data.getCompound("event"), data.getCompound("picks")));
            return;
        }
        // default: the list
        List<List<String>> rows = new ArrayList<>();
        ListTag events = data.getList("events", Tag.TAG_COMPOUND);
        for (int i = 0; i < events.size(); i++)
        {
            CompoundTag e = events.getCompound(i);
            rows.add(List.of(e.getString("id"), e.getString("name"), e.getString("status")));
        }
        mc.setScreen(new EventListScreen(rows));
    }
}
