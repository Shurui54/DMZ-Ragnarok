package net.shurui.shuruisutilities.racing.client;

import net.minecraft.client.Minecraft;

import net.shurui.dev.sdu.api.ClientGate;

/**
 * Client-only entry points for the racing screens. Reached from common code (the track wand's {@code use}) through
 * {@code DistExecutor}, so this class is never classloaded on a dedicated server.
 */
public final class RaceScreens
{
    private RaceScreens() {}

    /** Open the track editor for the bound track id, gated on the racing feature. */
    public static void openTrackEditor(String trackId)
    {
        if (trackId == null || trackId.isEmpty() || !ClientGate.feature("racing"))
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        mc.setScreen(new TrackEditorScreen(trackId));
    }

    /** Open the race tuning screen (odds grid + session numbers), gated on the racing feature. */
    public static void openTuning(net.shurui.shuruisutilities.racing.tuning.RaceTuningDto tuning, String trackId)
    {
        if (tuning == null || !ClientGate.feature("racing"))
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        mc.setScreen(new RaceTuningScreen(tuning, trackId));
    }
}
