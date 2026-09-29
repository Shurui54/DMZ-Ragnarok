package net.shurui.shuruisutilities.disguise;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The full, self-contained identity a disguised player is shown as. It carries everything a client needs to draw the
 * disguise WITHOUT having to look the target up (the target may be offline, or a random pick the viewer has never
 * met): the visible name, the target's rank id and crown, the target's skin (a Mojang textures property, resolved to
 * a real skin client-side) and the target's DragonMineZ race appearance (the same bounded set the mini-clone copies).
 *
 * <p>The REAL identity ({@link #realId} / {@link #realName}) rides along so a staff viewer can be shown a see-through
 * marker; it is sent to every client but only DRAWN by a client the server flagged as able to see through disguises.
 *
 * <p>Kept deliberately compact: read as HEX-parsed ints server-side (never DMZ's client-only ColorUtils), so the DTO
 * is safe to build on a dedicated server. Every DMZ field is optional; when {@link #hasDmz} is false the client
 * leaves the disguised player's own race body untouched and only the name / skin / rank change.
 */
public final class DisguiseView
{
    // Who is disguised (the staff member), and their real name for the staff-only see-through marker.
    public UUID realId;
    public String realName = "";

    // Who they appear to be.
    public UUID targetId;
    public String targetName = "";

    // Target's rank id ("" = none) and crown codepoint (0 = none), so tab / nametag / chat badge match the target.
    public String rankId = "";
    public int crownCodepoint = 0;

    // Target's skin as a Mojang textures property. Empty when it could not be resolved (offline with no cached
    // profile); the client then keeps the disguised player's own skin rather than a Steve.
    public String skinTexturesValue = "";
    public String skinTexturesSignature = "";
    public boolean slim = false;

    // Target's DragonMineZ base-appearance. Only applied when hasDmz is true.
    public boolean hasDmz = false;
    public String race = "";
    public int bodyType = 0;
    public int eyesType = 0;
    public int bodyColor1 = 0xFFFFFF;
    public int bodyColor2 = 0xFFFFFF;
    public int bodyColor3 = 0xFFFFFF;
    public int hairColor = 0xFFFFFF;
    public int eye1Color = 0xFFFFFF;
    public int eye2Color = 0xFFFFFF;
    // The rest of the base look: gender (DMZ picks a gendered race geo from it), face parts, tail and the base hair
    // style (DMZ's own CustomHair NBT, empty when the target has none). Same rule: only applied when hasDmz.
    public String gender = "";
    public int hairId = 0;
    public int noseType = 0;
    public int mouthType = 0;
    public int tattooType = 0;
    public boolean saiyanTail = false;
    public CompoundTag hairBase = new CompoundTag();

    // Client-side memo of the decoded hair style, built once per view by the render override so a frame never parses
    // NBT. Object-typed so this DTO never loads a DragonMineZ class on its own. Not synced, not persisted.
    public transient Object hairCache;

    public DisguiseView() {}

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUUID(realId);
        buf.writeUtf(realName);
        buf.writeUUID(targetId);
        buf.writeUtf(targetName);
        buf.writeUtf(rankId);
        buf.writeVarInt(crownCodepoint);
        buf.writeUtf(skinTexturesValue);
        buf.writeUtf(skinTexturesSignature);
        buf.writeBoolean(slim);
        buf.writeBoolean(hasDmz);
        buf.writeUtf(race);
        buf.writeVarInt(bodyType);
        buf.writeVarInt(eyesType);
        buf.writeInt(bodyColor1);
        buf.writeInt(bodyColor2);
        buf.writeInt(bodyColor3);
        buf.writeInt(hairColor);
        buf.writeInt(eye1Color);
        buf.writeInt(eye2Color);
        buf.writeUtf(gender);
        buf.writeVarInt(hairId);
        buf.writeVarInt(noseType);
        buf.writeVarInt(mouthType);
        buf.writeVarInt(tattooType);
        buf.writeBoolean(saiyanTail);
        buf.writeNbt(hairBase);
    }

    public static DisguiseView decode(FriendlyByteBuf buf)
    {
        DisguiseView v = new DisguiseView();
        v.realId = buf.readUUID();
        v.realName = buf.readUtf();
        v.targetId = buf.readUUID();
        v.targetName = buf.readUtf();
        v.rankId = buf.readUtf();
        v.crownCodepoint = buf.readVarInt();
        v.skinTexturesValue = buf.readUtf();
        v.skinTexturesSignature = buf.readUtf();
        v.slim = buf.readBoolean();
        v.hasDmz = buf.readBoolean();
        v.race = buf.readUtf();
        v.bodyType = buf.readVarInt();
        v.eyesType = buf.readVarInt();
        v.bodyColor1 = buf.readInt();
        v.bodyColor2 = buf.readInt();
        v.bodyColor3 = buf.readInt();
        v.hairColor = buf.readInt();
        v.eye1Color = buf.readInt();
        v.eye2Color = buf.readInt();
        v.gender = buf.readUtf();
        v.hairId = buf.readVarInt();
        v.noseType = buf.readVarInt();
        v.mouthType = buf.readVarInt();
        v.tattooType = buf.readVarInt();
        v.saiyanTail = buf.readBoolean();
        CompoundTag hair = buf.readNbt();
        v.hairBase = hair == null ? new CompoundTag() : hair;
        return v;
    }

    /**
     * Persisted form (the per-player merge store). Only non-default fields are written, so an entry stays small and a
     * field added later is simply absent from older entries; {@link #fromNbt} restores every absent field to its
     * default. Do not rename these keys.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putUUID("real", realId);
        putStr(t, "realName", realName);
        t.putUUID("target", targetId);
        putStr(t, "targetName", targetName);
        putStr(t, "rank", rankId);
        if (crownCodepoint != 0)
            t.putInt("crown", crownCodepoint);
        putStr(t, "skin", skinTexturesValue);
        putStr(t, "skinSig", skinTexturesSignature);
        if (slim)
            t.putBoolean("slim", true);
        if (!hasDmz)
            return t;
        t.putBoolean("hasDmz", true);
        putStr(t, "race", race);
        putInt(t, "bt", bodyType, 0);
        putInt(t, "et", eyesType, 0);
        putInt(t, "b1", bodyColor1, 0xFFFFFF);
        putInt(t, "b2", bodyColor2, 0xFFFFFF);
        putInt(t, "b3", bodyColor3, 0xFFFFFF);
        putInt(t, "hair", hairColor, 0xFFFFFF);
        putInt(t, "e1", eye1Color, 0xFFFFFF);
        putInt(t, "e2", eye2Color, 0xFFFFFF);
        putStr(t, "gender", gender);
        putInt(t, "hairId", hairId, 0);
        putInt(t, "nose", noseType, 0);
        putInt(t, "mouth", mouthType, 0);
        putInt(t, "tattoo", tattooType, 0);
        if (saiyanTail)
            t.putBoolean("tail", true);
        if (hairBase != null && !hairBase.isEmpty())
            t.put("hairBase", hairBase.copy());
        return t;
    }

    public static DisguiseView fromNbt(CompoundTag t)
    {
        DisguiseView v = new DisguiseView();
        v.realId = t.getUUID("real");
        v.realName = t.getString("realName");
        v.targetId = t.getUUID("target");
        v.targetName = t.getString("targetName");
        v.rankId = t.getString("rank");
        v.crownCodepoint = t.getInt("crown");
        v.skinTexturesValue = t.getString("skin");
        v.skinTexturesSignature = t.getString("skinSig");
        v.slim = t.getBoolean("slim");
        v.hasDmz = t.getBoolean("hasDmz");
        v.race = t.getString("race");
        v.bodyType = t.getInt("bt");
        v.eyesType = t.getInt("et");
        v.bodyColor1 = getInt(t, "b1", 0xFFFFFF);
        v.bodyColor2 = getInt(t, "b2", 0xFFFFFF);
        v.bodyColor3 = getInt(t, "b3", 0xFFFFFF);
        v.hairColor = getInt(t, "hair", 0xFFFFFF);
        v.eye1Color = getInt(t, "e1", 0xFFFFFF);
        v.eye2Color = getInt(t, "e2", 0xFFFFFF);
        v.gender = t.getString("gender");
        v.hairId = t.getInt("hairId");
        v.noseType = t.getInt("nose");
        v.mouthType = t.getInt("mouth");
        v.tattooType = t.getInt("tattoo");
        v.saiyanTail = t.getBoolean("tail");
        v.hairBase = t.getCompound("hairBase").copy();
        return v;
    }

    private static void putStr(CompoundTag t, String key, String value)
    {
        if (value != null && !value.isEmpty())
            t.putString(key, value);
    }

    private static void putInt(CompoundTag t, String key, int value, int dflt)
    {
        if (value != dflt)
            t.putInt(key, value);
    }

    private static int getInt(CompoundTag t, String key, int dflt)
    {
        return t.contains(key) ? t.getInt(key) : dflt;
    }
}
