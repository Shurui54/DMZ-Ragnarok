package net.shurui.shuruisutilities.space;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Side-agnostic snapshot of one generated planet's public info, built on the SERVER and drawn by the client overlay
 * {@code PlanetInfoOverlay}. Plain fields with one {@code write}/{@code read} pair, so the client NEVER derives any of
 * these itself. Every field has a defined "none/unknown" state (empty string, false flag, zero count) so a
 * never-visited, unclaimed or partly-resolved planet renders cleanly instead of drawing blanks.
 */
public class PlanetInfoView
{
    public String planetName = "";
    public boolean destroyed;
    public int surfaceSize;

    // never below 1.0. Meaningful even for a never-visited planet (falls back to the flat wild value), so NOT gated
    // on populated.
    public double toughness;
    // strongest defender; only meaningful once POPULATED, else 0 and the screen shows "unknown".
    public double recommendedBattlePower;

    // false for a planet nobody has landed on, so the screen says "not yet explored" rather than 0/0 defenders.
    public boolean populated;
    // PlanetGarrisonRoster.Family.name(), or "" for an undefended populated planet. Readable label via lang key.
    public String garrisonFamily = "";
    public int defendersRemaining;
    public int defendersTotal;

    // SurfaceStamp.Theme.name(), or "" if unresolved. Readable phrase client-side, never the raw enum id.
    public String environmentTheme = "";

    // a claim whose guild has disbanded reads as unclaimed (stays false), matching every ownership path here.
    public boolean claimed;
    public String ownerGuildName = "";
    public int ownerMemberCount;
    public double ownerBattlePower;

    public void write(FriendlyByteBuf buf)
    {
        buf.writeUtf(planetName);
        buf.writeBoolean(destroyed);
        buf.writeVarInt(surfaceSize);
        buf.writeDouble(toughness);
        buf.writeDouble(recommendedBattlePower);
        buf.writeBoolean(populated);
        buf.writeUtf(garrisonFamily);
        buf.writeVarInt(defendersRemaining);
        buf.writeVarInt(defendersTotal);
        buf.writeUtf(environmentTheme);
        buf.writeBoolean(claimed);
        buf.writeUtf(ownerGuildName);
        buf.writeVarInt(ownerMemberCount);
        buf.writeDouble(ownerBattlePower);
    }

    public static PlanetInfoView read(FriendlyByteBuf buf)
    {
        PlanetInfoView v = new PlanetInfoView();
        v.planetName = buf.readUtf();
        v.destroyed = buf.readBoolean();
        v.surfaceSize = buf.readVarInt();
        v.toughness = buf.readDouble();
        v.recommendedBattlePower = buf.readDouble();
        v.populated = buf.readBoolean();
        v.garrisonFamily = buf.readUtf();
        v.defendersRemaining = buf.readVarInt();
        v.defendersTotal = buf.readVarInt();
        v.environmentTheme = buf.readUtf();
        v.claimed = buf.readBoolean();
        v.ownerGuildName = buf.readUtf();
        v.ownerMemberCount = buf.readVarInt();
        v.ownerBattlePower = buf.readDouble();
        return v;
    }
}
