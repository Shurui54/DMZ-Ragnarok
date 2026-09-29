package net.shurui.dev.shuruis_dmz_dungeons.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateMetal;
import net.shurui.dev.shuruis_dmz_dungeons.block.CrateTier;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorLayout;
import net.shurui.dev.shuruis_dmz_dungeons.network.SyncCrateTiersPacket.CrateTierInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// client-side holder for the per-floor crate colour inputs (weights / refreshHours / theme), synced once on
// dungeon entry by SyncCrateTiersPacket. Crate colour handlers read from here:
//   * tintindex 0 (hoops/latch): PER-VIEWER rarity, rolled locally from crate position, game time (sliced into
//     the floor's refresh window) and the LOCAL player's UUID, so it matches what the server rolls on open with
//     no per-block packet.
//   * tintindex 1 (barrel body): the floor's THEME palette colour.
// A crate's floor is derived from its X (DungeonFloorLayout.floorNumberForX).
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons", value = Dist.CLIENT)
public final class ClientCrateTiers {

    private ClientCrateTiers() {
    }

    // floor number -> that floor's colour inputs. Replaced wholesale on each sync.
    private static final Map<Integer, CrateTierInfo> FLOORS = new HashMap<>();

    // the seven floor THEME palettes for the barrel body (tintindex 1) and chest lid/base. ARGB tints that MULTIPLY
    // the greyscale crate textures (luminance ~100), so a tint must carry real saturation or the product reads as
    // neutral grey. Three near-neutral pale tans (STONY 0xC9B89A, OVERWORLD 0xBFB080, KAIO 0xF2E4A0) collapsed to
    // muddy warm-grey over the mid-grey texture and were re-saturated to clear hues (sandstone / grass / King-Kai
    // gold) that survive the multiply; the already-saturated four are unchanged. An unknown or missing theme falls
    // back to white (no tint).
    private static final Map<String, Integer> THEME_COLORS = new HashMap<>();
    static {
        // STONY is retired from the theme picker (VEGETA now carries the "Saiyan" label) but its tint is KEPT so an
        // existing stony floor's crates still colour correctly instead of falling back to white.
        THEME_COLORS.put("STONY", 0xFFCBA060);
        THEME_COLORS.put("OVERWORLD", 0xFF8FB84A);
        THEME_COLORS.put("NAMEK", 0xFF9BE86B);
        THEME_COLORS.put("NETHER", 0xFFC2604A);
        THEME_COLORS.put("END", 0xFFA98FD6);
        THEME_COLORS.put("KAIO", 0xFFF2C838);
        THEME_COLORS.put("OTHERWORLD", 0xFFE8C44A);
        // the two planet themes, taken straight from the SU planet grass tints (NamekBlockTints.GRASS_VEGETA
        // 0x82FF3C, GRASS_BEERUS 0xC85AFF) so a crate reads the same green / purple as the floor it sits on.
        THEME_COLORS.put("VEGETA", 0xFF82FF3C);
        THEME_COLORS.put("BEERUS", 0xFFC85AFF);
    }

    private static final int WHITE = 0xFFFFFFFF;

    // last epoch signature seen, so a refresh-window rollover can force a chunk-mesh repaint (block colours are
    // baked into the compiled mesh, so without a rebuild the new rarity would not show until a section is otherwise
    // dirtied). MIN_VALUE means "not yet sampled".
    private static long lastEpochSignature = Long.MIN_VALUE;

    public static void apply(List<CrateTierInfo> entries) {
        FLOORS.clear();
        for (CrateTierInfo e : entries) {
            FLOORS.put(e.floor(), e);
        }
        // force a repaint so the just-synced weights/theme take effect immediately rather than on the next chunk edit.
        lastEpochSignature = Long.MIN_VALUE;
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            mc.levelRenderer.allChanged();
        }
    }


    /**
     * The crate a viewer sees at this position: its rarity and metal, which together name the model. Derived here
     * rather than synced per container: both sides roll the same deterministic function of position, refresh window
     * and viewer, so a crate carries nothing and never disagrees with the server's reward.
     */
    public static CrateTier tierAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc.player == null || mc.level == null) {
            return CrateTier.COMMON;
        }
        CrateTierInfo info = FLOORS.get(DungeonFloorLayout.floorNumberForPos(pos.getX(), pos.getZ()));
        int[] weights = info == null ? CrateTier.DEFAULT_WEIGHTS : info.weights();
        int refreshHours = info == null ? 0 : info.refreshHours();
        long epoch = CrateTier.epoch(mc.level.getGameTime(), refreshHours);
        return CrateTier.roll(pos.asLong(), epoch, mc.player.getUUID(), weights);
    }

    public static CrateMetal metalAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || mc.player == null || mc.level == null) {
            return CrateMetal.BRONZE;
        }
        CrateTierInfo info = FLOORS.get(DungeonFloorLayout.floorNumberForPos(pos.getX(), pos.getZ()));
        int[] weights = info == null ? CrateMetal.DEFAULT_WEIGHTS : info.metalWeights();
        int refreshHours = info == null ? 0 : info.refreshHours();
        long epoch = CrateTier.epoch(mc.level.getGameTime(), refreshHours);
        return CrateMetal.roll(pos.asLong(), epoch, mc.player.getUUID(), weights);
    }


    // per client tick in a themed dungeon dimension, detect a refresh-window (epoch) rollover and rebuild chunk
    // meshes so re-rolled colours repaint. floors with refreshHours <= 0 sit at epoch 0 forever and never trip this.
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || FLOORS.isEmpty() || !DungeonDimensions.isAnyDungeon(mc.level.dimension())) {
            return;
        }
        long sig = epochSignature(mc.level.getGameTime());
        if (sig != lastEpochSignature) {
            // skip the very first sample (nothing to repaint yet); apply() already forced the initial rebuild.
            if (lastEpochSignature != Long.MIN_VALUE && mc.levelRenderer != null) {
                mc.levelRenderer.allChanged();
            }
            lastEpochSignature = sig;
        }
    }

    // a rollover-sensitive hash of every floor's current epoch, so any window boundary changes the signature.
    private static long epochSignature(long gameTime) {
        long h = 1125899906842597L;
        for (CrateTierInfo info : FLOORS.values()) {
            h = h * 31 + CrateTier.epoch(gameTime, info.refreshHours());
        }
        return h;
    }
}
