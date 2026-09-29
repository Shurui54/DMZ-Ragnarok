package net.shurui.dev.sdu.shenron;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.entity.ShenronDisplayEntity;
import net.shurui.dev.sdu.registry.ModEntities;

import java.util.List;

// server-side Shenron-shrine summon flow: item requirement matching/consumption (by item only, tags ignored,
// counted across the whole inventory) + spawning the display entity with sky-darken/sound. fully custom, no
// coupling to DMZ's wish system.
public final class ShrineSummon {

    private static final ResourceLocation DMZ_SHENRON_SOUND = new ResourceLocation("dragonminez", "shenron");

    private ShrineSummon() {
    }

    // item registry id -> Item, null if unknown/air.
    private static Item resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    // count across the whole inventory, item match only.
    private static int countItem(ServerPlayer player, Item item) {
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                total += s.getCount();
            }
        }
        return total;
    }

    public static boolean hasRequiredItems(ServerPlayer player, List<ShrineRequiredItem> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        for (ShrineRequiredItem req : required) {
            Item item = resolve(req.item);
            if (item == null) {
                continue; // unknown ids are treated as "no requirement" so a typo can't hard-lock a shrine.
            }
            if (countItem(player, item) < Math.max(1, req.count)) {
                return false;
            }
        }
        return true;
    }

    private static void consumeRequiredItems(ServerPlayer player, List<ShrineRequiredItem> required) {
        if (required == null) {
            return;
        }
        var inv = player.getInventory();
        for (ShrineRequiredItem req : required) {
            Item item = resolve(req.item);
            if (item == null) {
                continue;
            }
            int remaining = Math.max(1, req.count);
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.getItem() == item) {
                    int take = Math.min(remaining, s.getCount());
                    s.shrink(take);
                    remaining -= take;
                }
            }
        }
    }

    // validate + consume requirements, spawn the display entity above the shrine facing the player. null if
    // requirements unmet or spawn failed.
    public static ShenronDisplayEntity summon(ServerPlayer player, BlockPos shrinePos, ShrineColor color) {
        ShrineColorConfig cfg = ShrineConfig.color(color);
        if (!hasRequiredItems(player, cfg.requiredItems)) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        ShenronDisplayEntity shenron = ModEntities.SHENRON.get().create(level);
        if (shenron == null) {
            return null;
        }
        consumeRequiredItems(player, cfg.requiredItems);

        shenron.setGeo(cfg.modelGeo);
        shenron.setTexture(cfg.modelTexture);
        shenron.setScaleValue(cfg.entityScale);
        shenron.setSummoner(player.getUUID());
        shenron.setShrinePos(shrinePos);

        long savedDayTime = level.getDayTime();
        shenron.configureLifetime(cfg.summonDurationTicks, cfg.darkenSky, savedDayTime);

        // ~3 blocks above the shrine, facing the player.
        Vec3 base = Vec3.atCenterOf(shrinePos).add(0, 3.0, 0);
        double dx = player.getX() - base.x;
        double dz = player.getZ() - base.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        shenron.moveTo(base.x, base.y, base.z, yaw, 0f);
        shenron.setYHeadRot(yaw);

        if (!level.addFreshEntity(shenron)) {
            return null;
        }

        // darken: set night + force a storm for the duration (day time already captured for restore).
        if (cfg.darkenSky) {
            level.setDayTime(18000);
            level.setWeatherParameters(0, cfg.summonDurationTicks, true, true);
        }

        // DMZ's shenron sound, falling back to vanilla thunder if DMZ is absent.
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(DMZ_SHENRON_SOUND);
        if (sound == null) {
            sound = SoundEvents.LIGHTNING_BOLT_THUNDER;
        }
        level.playSound(null, shrinePos, sound, SoundSource.AMBIENT, 1.0F, 1.0F);
        return shenron;
    }

    // run the wish's commands as console with %player% substituted, mark granted, start the short despawn.
    // assumes the caller already validated summoner/wish/colour.
    public static void grantWish(ServerPlayer player, ShenronDisplayEntity shenron, ShrineWish wish) {
        var server = player.getServer();
        if (server != null && wish.commands != null) {
            String name = player.getGameProfile().getName();
            var source = server.createCommandSourceStack().withSuppressedOutput();
            for (String cmd : wish.commands) {
                if (cmd != null && !cmd.isBlank()) {
                    try {
                        server.getCommands().performPrefixedCommand(source, cmd.replace("%player%", name));
                    } catch (Exception e) {
                        DmzNpc.LOGGER.error("[{}] Shrine wish command failed '{}': {}",
                                DmzNpc.MODID, cmd, e.toString());
                    }
                }
            }
        }
        shenron.markWishGranted();
    }

    public static ShenronDisplayEntity findShenron(ServerPlayer player, int entityId) {
        Entity e = player.serverLevel().getEntity(entityId);
        return e instanceof ShenronDisplayEntity s ? s : null;
    }
}
