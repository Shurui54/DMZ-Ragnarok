package net.shurui.shuruisutilities.disguise;

import java.io.File;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.compat.DmzBridge;
import net.shurui.shuruisutilities.shard.ShardExecutor;
import net.shurui.shuruisutilities.shard.ShardSync;

/**
 * Core-side capture of a target's DragonMineZ base appearance into a {@link DisguiseView}. This lives in CORE, not in
 * the key mod, because the key mod is content-free and does not compile against DragonMineZ: only core reads DMZ. The
 * key's {@code DisguiseFeature} calls this so the DMZ classes are touched exactly once, here, behind the mandatory
 * dependency the rest of the suite already relies on.
 *
 * <p>Colours are read as HEX STRINGS and parsed on the SERVER (never DMZ's client-only ColorUtils), exactly as the
 * mini-clone does, so this is safe on a dedicated server. Any read failure leaves {@code hasDmz} false and the client
 * keeps the disguised player's own body.
 */
public final class DisguiseCapture
{
    private DisguiseCapture() {}

    /** Fill the DMZ appearance fields of {@code v} from {@code target}. No-op (hasDmz stays false) on any failure. */
    public static void fillDmz(DisguiseView v, ServerPlayer target)
    {
        try
        {
            com.dragonminez.common.stats.StatsData stats = DmzBridge.stats(target);
            if (stats == null)
                return;
            fillFromCharacter(v, stats.getCharacter());
        }
        catch (Throwable t)
        {
            v.hasDmz = false;
        }
    }

    /**
     * The OFFLINE player's DragonMineZ {@code Character} tag from their authoritative saved data: the shard vault when
     * the network is live (the vault blob is a {@code CharacterSlots} snapshot, so the stats sit under
     * {@code character.dmz}), else the local {@code playerdata/<uuid>.dat} (stats under the capability id in
     * {@code ForgeCaps}). Either way the tag is DMZ's own {@code StatsData.save()} output, so its {@code "Character"}
     * sub-tag is read, never a guessed layout. Null when neither source has one.
     *
     * <p>BLOCKING (a JDBC round trip on the network, a file read otherwise): call it through {@link #offThread}, never
     * on the server thread.
     */
    public static CompoundTag readOfflineCharacter(MinecraftServer server, UUID id)
    {
        if (ShardSync.active())
        {
            try
            {
                CompoundTag ch = ShardHooks.get().vaultCharacter(id);
                if (ch != null && !ch.isEmpty())
                    return ch;
            }
            catch (Throwable ignored)
            {
                // Vault miss: fall through to local playerdata.
            }
        }
        try
        {
            File file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat").toFile();
            if (!file.isFile())
                return null;
            CompoundTag caps = NbtIo.readCompressed(file).getCompound("ForgeCaps");
            CompoundTag ch = caps.getCompound(com.dragonminez.common.stats.StatsProvider.ID.toString())
                    .getCompound("Character");
            return ch.isEmpty() ? null : ch;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * The OFFLINE player's suite NICKNAME (empty when they have none), read from the same place the nick feature
     * writes it: the top level of the player's Forge persistent data ({@code getPersistentData()}, saved under
     * {@code ForgeData} in {@code playerdata/<uuid>.dat}), key {@code nickname}. Best-effort and a SNAPSHOT, like the
     * rest of the offline capture: it reads this shard's local file, which is the authority the nick feature reads
     * for an online player on this shard. BLOCKING: call through {@link #offThread}.
     */
    public static String readOfflineNickname(MinecraftServer server, UUID id)
    {
        try
        {
            File file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat").toFile();
            if (!file.isFile())
                return "";
            String nick = NbtIo.readCompressed(file).getCompound("ForgeData").getString("nickname");
            return nick == null ? "" : nick;
        }
        catch (Throwable ignored)
        {
            return "";
        }
    }

    /**
     * Fill the DMZ appearance fields of {@code v} from a {@link #readOfflineCharacter} tag, parsed with DragonMineZ's
     * OWN code (a fresh {@code Character}, then {@code load}). No-op (hasDmz stays false) for a null tag or a failure.
     */
    public static void fillDmzFromTag(DisguiseView v, CompoundTag characterTag)
    {
        if (characterTag == null || characterTag.isEmpty())
            return;
        try
        {
            com.dragonminez.common.stats.character.Character ch = new com.dragonminez.common.stats.character.Character();
            ch.load(characterTag);
            fillFromCharacter(v, ch);
        }
        catch (Throwable t)
        {
            v.hasDmz = false;
        }
    }

    /**
     * Run blocking disguise work (profile lookups, vault or playerdata reads, presence checks) off the server thread:
     * on the single shard vault thread when the network is live, as every vault read must, else on vanilla's
     * background pool. The task hands its result back with {@code server.execute}.
     */
    public static void offThread(Runnable task)
    {
        if (ShardSync.active())
            ShardExecutor.submit(task);
        else
            net.minecraft.Util.backgroundExecutor().execute(task);
    }

    private static void fillFromCharacter(DisguiseView v, com.dragonminez.common.stats.character.Character ch)
    {
        if (ch == null)
            return;
        v.hasDmz = true;
        v.race = ch.getRaceName() == null ? "" : ch.getRaceName();
        v.bodyType = ch.getBodyType();
        v.eyesType = ch.getEyesType();
        v.bodyColor1 = parseHex(ch.getBodyColor());
        v.bodyColor2 = parseHex(ch.getBodyColor2());
        v.bodyColor3 = parseHex(ch.getBodyColor3());
        v.hairColor = parseHex(ch.getHairColor());
        v.eye1Color = parseHex(ch.getEye1Color());
        v.eye2Color = parseHex(ch.getEye2Color());
        v.gender = ch.getGender() == null ? "" : ch.getGender();
        v.hairId = ch.getHairId();
        v.noseType = ch.getNoseType();
        v.mouthType = ch.getMouthType();
        v.tattooType = ch.getTattooType();
        v.saiyanTail = ch.isHasSaiyanTail();
        try
        {
            // DMZ's own CustomHair codec, so the client rebuilds exactly the style the target wears.
            v.hairBase = ch.getHairBase() == null ? new CompoundTag() : ch.getHairBase().save();
        }
        catch (Throwable t)
        {
            v.hairBase = new CompoundTag();
        }
    }

    private static int parseHex(String hex)
    {
        if (hex == null || hex.isEmpty())
            return 0xFFFFFF;
        try
        {
            String cleaned = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(cleaned, 16) & 0xFFFFFF;
        }
        catch (NumberFormatException e)
        {
            return 0xFFFFFF;
        }
    }
}
