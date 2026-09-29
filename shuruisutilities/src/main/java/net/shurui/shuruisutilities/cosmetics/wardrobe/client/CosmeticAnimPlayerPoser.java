package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Poses the REAL player so THEY perform a triggered-animation effect, instead of a flat-grey placeholder stand-in.
 *
 * <h2>Why the player, and why here</h2>
 * The source EliteAnimations rigs carry a runtime-skinned placeholder player mesh and animate IT; the converter
 * strips that mesh ({@code convert_fx.py}), so the effect FX (tombstone, gate, spirits, particles) is drawn by
 * {@link CosmeticAnimGeoRenderer} with no character in it. To make the effect read as the PLAYER doing it, we take
 * the rig's own player-JOINT keyframes (baked by the converter into {@code fx/cosmetic_anim/player_tracks.json})
 * and retarget them onto the real player's skeleton. DMZ renders players with its own GeckoLib {@code DMZPlayerModel}
 * (its {@code PlayerRendererMixin} cancels the vanilla render), so this is called from the tail of that model's
 * per-frame bone posing, exactly like {@code sdu}'s dodge flourish, and stacks on top of DMZ's pose.
 *
 * <h2>The retarget is exact, not guessed</h2>
 * The baked values are in GeckoLib ANIMATION space (the same signed degrees / model units the rig's own
 * {@code animation.json} carries). GeckoLib turns an animation rotation {@code (x,y,z)} into a bone delta of
 * {@code (toRad(-x), toRad(-y), toRad(z))} and applies a position value straight to the bone; we reproduce exactly
 * that transform here, so a joint moves the real player's bone by precisely what it would move the rig's own bone.
 * Rotations are applied to every mapped joint. Position is applied to the ROOT only and on the VERTICAL axis only:
 * the rise from the grave, the lift through the gate, the sink and the departure. The rig's horizontal root offset
 * (its placement relative to the tombstone or gate prop) is deliberately dropped, so the real player performs the
 * effect where they actually stand rather than being shoved sideways.
 *
 * <h2>Fails safe</h2>
 * A missing tracks file, an unknown rig key, or a bone DMZ does not expose all leave the player in DMZ's own pose:
 * the effect FX still plays, the player just does not animate, which is never a crash. The local player in first
 * person is left untouched so the effect never leaks into the held-item view.
 */
@OnlyIn(Dist.CLIENT)
public final class CosmeticAnimPlayerPoser
{
    private CosmeticAnimPlayerPoser()
    {
    }

    private static final ResourceLocation TRACKS =
            new ResourceLocation(ShuruisUtilities.MODID, "fx/cosmetic_anim/player_tracks.json");

    /** rigKey ("in_risendread") -> boneName -> channel ("rotation"/"position") -> keyframes [progress,x,y,z]. */
    private static volatile Map<String, Map<String, Map<String, float[][]>>> tracks;
    private static volatile boolean loadAttempted;

    /**
     * Apply the current cosmetic-animation pose for a subject to a DMZ player GeoModel. A no-op when the subject has
     * no active geo effect, when the tracks are unavailable, or (for the local player) in first person.
     */
    public static void applyGeo(GeoModel<?> model, UUID subject, int entityId, float partialTick)
    {
        if (model == null || subject == null)
            return;
        CosmeticAnimationClientStore.ActivePose pose = CosmeticAnimationClientStore.activePose(subject, partialTick);
        if (pose == null || pose.rigKey() == null || pose.rigKey().isBlank())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entityId == mc.player.getId() && mc.options.getCameraType().isFirstPerson())
            return;
        Map<String, Map<String, float[][]>> rig = tracks().get((pose.arriving() ? "in_" : "out_") + pose.rigKey());
        if (rig == null)
            return;
        float p = pose.progress();
        for (Map.Entry<String, Map<String, float[][]>> boneEntry : rig.entrySet())
        {
            String boneName = boneEntry.getKey();
            Map<String, float[][]> channels = boneEntry.getValue();
            float[][] rot = channels.get("rotation");
            float[][] pos = channels.get("position");
            if (rot != null)
            {
                float[] v = sample(rot, p);
                // GeckoLib animation-to-bone rotation transform: delta = (toRad(-x), toRad(-y), toRad(z)).
                float dx = (float) Math.toRadians(-v[0]);
                float dy = (float) Math.toRadians(-v[1]);
                float dz = (float) Math.toRadians(v[2]);
                model.getBone(boneName).ifPresent(b ->
                {
                    b.setRotX(b.getRotX() + dx);
                    b.setRotY(b.getRotY() + dy);
                    b.setRotZ(b.getRotZ() + dz);
                });
            }
            // Vertical translation on the root only: the rig's horizontal placement offset is not the player's motion.
            if (pos != null && "root".equals(boneName))
            {
                float dyPos = sample(pos, p)[1];
                model.getBone(boneName).ifPresent(b -> b.setPosY(b.getPosY() + dyPos));
            }
        }
    }

    /** Linear sample of a [progress,x,y,z] track at progress p, clamped to the endpoints. Never null. */
    private static float[] sample(float[][] track, float p)
    {
        if (track.length == 0)
            return new float[] {0F, 0F, 0F};
        if (p <= track[0][0])
            return new float[] {track[0][1], track[0][2], track[0][3]};
        for (int i = 1; i < track.length; i++)
        {
            if (p <= track[i][0])
            {
                float t0 = track[i - 1][0];
                float t1 = track[i][0];
                float span = t1 - t0;
                float f = span <= 0F ? 0F : (p - t0) / span;
                float[] a = track[i - 1];
                float[] b = track[i];
                return new float[] {a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f};
            }
        }
        float[] last = track[track.length - 1];
        return new float[] {last[1], last[2], last[3]};
    }

    /**
     * Forget the loaded tracks so the next use re-reads them. Called after every client resource reload
     * ({@link CosmeticPackFinder}), because the tracks file is streamed with the cosmetic art and may arrive, or change,
     * after the first read.
     */
    public static void invalidate()
    {
        synchronized (CosmeticAnimPlayerPoser.class)
        {
            tracks = null;
            loadAttempted = false;
        }
    }

    private static Map<String, Map<String, Map<String, float[][]>>> tracks()
    {
        Map<String, Map<String, Map<String, float[][]>>> local = tracks;
        if (local != null)
            return local;
        if (loadAttempted)
            return java.util.Collections.emptyMap();
        synchronized (CosmeticAnimPlayerPoser.class)
        {
            if (tracks != null)
                return tracks;
            if (!loadAttempted)
            {
                loadAttempted = true;
                tracks = load();
            }
            return tracks == null ? java.util.Collections.emptyMap() : tracks;
        }
    }

    private static Map<String, Map<String, Map<String, float[][]>>> load()
    {
        Map<String, Map<String, Map<String, float[][]>>> out = new HashMap<>();
        try
        {
            java.util.Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(TRACKS);
            if (res.isEmpty())
            {
                LoggingHandler.sulog.warn(
                        "[Cosmetics] player_tracks.json missing ({}); triggered animations will not pose the player.",
                        TRACKS);
                return out;
            }
            try (Reader reader = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8))
            {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject rigs = root.getAsJsonObject("tracks");
                if (rigs == null)
                    return out;
                for (Map.Entry<String, com.google.gson.JsonElement> rigEntry : rigs.entrySet())
                {
                    JsonObject bones = rigEntry.getValue().getAsJsonObject();
                    Map<String, Map<String, float[][]>> boneMap = new HashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> boneEntry : bones.entrySet())
                    {
                        JsonObject channels = boneEntry.getValue().getAsJsonObject();
                        Map<String, float[][]> channelMap = new HashMap<>();
                        for (Map.Entry<String, com.google.gson.JsonElement> chEntry : channels.entrySet())
                            channelMap.put(chEntry.getKey(), readTrack(chEntry.getValue().getAsJsonArray()));
                        boneMap.put(boneEntry.getKey(), channelMap);
                    }
                    out.put(rigEntry.getKey(), boneMap);
                }
            }
            LoggingHandler.sulog.info("[Cosmetics] Loaded player pose tracks for {} triggered-animation rigs.",
                    out.size());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Could not read player_tracks.json: {}", t.toString());
        }
        return out;
    }

    private static float[][] readTrack(JsonArray arr)
    {
        float[][] rows = new float[arr.size()][];
        for (int i = 0; i < arr.size(); i++)
        {
            JsonArray row = arr.get(i).getAsJsonArray();
            rows[i] = new float[] {row.get(0).getAsFloat(), row.get(1).getAsFloat(), row.get(2).getAsFloat(),
                    row.get(3).getAsFloat()};
        }
        return rows;
    }
}
