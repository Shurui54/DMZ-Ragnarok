package net.shurui.shuruisutilities.core.mixin.client;

import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;

import net.shurui.shuruisutilities.disguise.client.DisguiseIdentity;
import net.shurui.shuruisutilities.disguise.client.DisguiseSkins;
import net.shurui.shuruisutilities.patreon.PatreonCrowns;
import net.shurui.shuruisutilities.ranks.RankManager;
import net.shurui.shuruisutilities.tablist.client.TabListBannerClient;

import net.minecraft.client.Minecraft;
import net.shurui.shuruisutilities.ranks.client.RankBadgeMetrics;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * SU's custom tab list: (1) draws the animated rank badge before each name in the full-size ranks font via
 * getNameForDisplay, and (2) replaces vanilla render with a copy drawing each row's coloured bar 13px tall
 * (badge + 1px above/below) with the badge dead-centre and face/name/ping on the same baseline. Rows pitched
 * SU_ROW_H apart. Server-name header comes for free: it's the vanilla tab header SU's module sets server-side.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class MixinPlayerTabOverlay
{
    // 15px badge in a 17px bar: 1px of bar above and below. Raised from 11 when the badge art was
    // re-cut; the ranks font must stay at ASCENT 8 for the centring below to hold (see the generator).
    private static final int SU_BADGE_HEIGHT = RankBadgeMetrics.HEIGHT;
    // coloured row bar: badge + 1px above/below
    private static final int SU_BG_H = SU_BADGE_HEIGHT + 2;
    // row pitch: bar + 1px gap
    private static final int SU_ROW_H = SU_BG_H + 1;
    // content offset from row top. badge (ascent 8) drawn at cy + 2 has its top at cy + 1, seating the 11px
    // badge dead-centre in the 13px bar.
    // Derived, not literal: content sits so the badge's top lands PAD below the bar top.
    private static final int SU_CONTENT_Y = RankBadgeMetrics.PAD - RankBadgeMetrics.GLYPH_TOP_OFFSET;
    // max height the server banner scales to before drawing across the top
    private static final int SU_BANNER_MAX_H = 32;

    @Shadow @Final private Minecraft minecraft;
    @Shadow private Component header;
    @Shadow private Component footer;

    @Shadow public abstract Component getNameForDisplay(PlayerInfo info);

    @Shadow protected abstract void renderPingIcon(GuiGraphics graphics, int width, int x, int y, PlayerInfo info);

    @Shadow
    private List<PlayerInfo> getPlayerInfos()
    {
        throw new AssertionError();
    }

    @Shadow
    private void renderTablistScore(Objective objective, int y, String name, int x1, int x2, UUID id, GuiGraphics graphics)
    {
        throw new AssertionError();
    }

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void su$rankBadge(PlayerInfo info, CallbackInfoReturnable<Component> cir)
    {
        UUID id = info.getProfile().getId();
        if (id == null)
            return;
        // A disguised player's row is the TARGET's: name, crown and rank all come from the disguise-aware helper, which
        // answers exactly as before for everyone else.
        Component name = DisguiseIdentity.nameFor(id, cir.getReturnValue());
        // Supporter crown first, so it ends up immediately before the player's own name: "<crown> | Name". The
        // crowns font is ascent 7 / height 9, which drawn at SU_CONTENT_Y spans rows 2..10 of the SU_BG_H bar:
        // centred, 2px clear above and below, so it can never overflow the row (nor the name-tag box or a chat
        // line, which are tighter than this bar). nameW is measured through this same method, so the row widens
        // to fit the crown instead of the name running past the bar.
        int crownCp = DisguiseIdentity.crownOf(id);
        if (crownCp > 0)
            name = PatreonCrowns.decorate(crownCp, name);
        // Rank badge outermost: "[rank] <crown> | Name".
        RankManager.Rank rank = DisguiseIdentity.rankOf(id);
        if (rank != null)
            name = RankManager.withAnimatedBadge(rank, System.currentTimeMillis(), name, RankManager.FONT);
        if (name != cir.getReturnValue())
            cir.setReturnValue(name);
    }

    /**
     * Most senior rank first, then vanilla's own order within a rank.
     *
     * <p>Vanilla sorts spectators last, then by team name, then alphabetically, which puts the owner wherever
     * their name happens to fall. Seniority is what a tab list is actually read for on a server with ranks.
     *
     * <p>{@code List.sort} is STABLE, and the list handed in is already in vanilla order, so everyone sharing a
     * rank keeps the alphabetical ordering they had instead of shuffling frame to frame. The incoming list is
     * immutable ({@code Stream.toList}), hence the copy. Rank is read from the client cache, so an unranked or
     * not-yet-synced player scores -1 and sorts to the bottom rather than being dropped.
     */
    private static List<PlayerInfo> su$byRank(List<PlayerInfo> in)
    {
        try
        {
            List<PlayerInfo> out = new java.util.ArrayList<>(in);
            out.sort(java.util.Comparator.comparingInt((PlayerInfo p) ->
            {
                UUID id = p.getProfile().getId();
                return id == null ? -1 : RankManager.priorityOf(DisguiseIdentity.rankOf(id));
            }).reversed());
            return out;
        }
        catch (Throwable t)
        {
            // Ordering is cosmetic; a tab list in the wrong order beats no tab list.
            return in;
        }
    }

    // replace vanilla render with a copy spacing rows by SU_ROW_H. adapted from PlayerTabOverlay#render;
    // header/footer keep vanilla 9px spacing.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void su$renderTab(GuiGraphics graphics, int width, Scoreboard scoreboard, Objective objective, CallbackInfo ci)
    {
        // The custom tab list is private: unless the connected server reported the key, vanilla draws its own.
        if (!net.shurui.dev.sdu.api.ClientGate.key())
            return;
        ci.cancel();

        List<PlayerInfo> list = su$byRank(getPlayerInfos());
        int nameW = 0;
        int scoreW = 0;
        for (PlayerInfo info : list)
        {
            nameW = Math.max(nameW, this.minecraft.font.width(getNameForDisplay(info)));
            if (objective != null && objective.getRenderType() != ObjectiveCriteria.RenderType.HEARTS)
            {
                int w = this.minecraft.font.width(" " + scoreboard.getOrCreatePlayerScore(info.getProfile().getName(), objective).getScore());
                scoreW = Math.max(scoreW, w);
            }
        }

        int count = list.size();
        int rows = count;
        int cols;
        for (cols = 1; rows > 20; rows = (count + cols - 1) / cols)
            ++cols;

        boolean secure = this.minecraft.isLocalServer() || this.minecraft.getConnection().getConnection().isEncrypted();
        int extra;
        if (objective != null)
            extra = objective.getRenderType() == ObjectiveCriteria.RenderType.HEARTS ? 90 : scoreW;
        else
            extra = 0;

        int colW = Math.min(cols * ((secure ? 9 : 0) + nameW + extra + 13), width - 50) / cols;
        int left = width / 2 - (colW * cols + (cols - 1) * 5) / 2;
        int y = 10;
        int bgW = colW * cols + (cols - 1) * 5;

        List<FormattedCharSequence> headerLines = null;
        if (this.header != null)
        {
            headerLines = this.minecraft.font.split(this.header, width - 50);
            for (FormattedCharSequence line : headerLines)
                bgW = Math.max(bgW, this.minecraft.font.width(line));
        }
        List<FormattedCharSequence> footerLines = null;
        if (this.footer != null)
        {
            footerLines = this.minecraft.font.split(this.footer, width - 50);
            for (FormattedCharSequence line : footerLines)
                bgW = Math.max(bgW, this.minecraft.font.width(line));
        }

        // server-supplied banner across the top, above the header. bytes synced by PacketTabListBanner,
        // held in TabListBannerClient.
        if (TabListBannerClient.isLoaded())
        {
            int imgW = TabListBannerClient.width();
            int imgH = TabListBannerClient.height();
            float scale = Math.min(Math.min((float) (width - 50) / 2 / imgW, (float) SU_BANNER_MAX_H / imgH), 1.0f);
            int bw = Math.max(1, Math.round(imgW * scale));
            int bh = Math.max(1, Math.round(imgH * scale));
            RenderSystem.enableBlend();
            graphics.blit(TabListBannerClient.TEXTURE, width / 2 - bw / 2, y, bw, bh, 0.0f, 0.0f, imgW, imgH, imgW, imgH);
            y += bh + 2;
            bgW = Math.max(bgW, bw);
        }

        if (headerLines != null)
        {
            graphics.fill(width / 2 - bgW / 2 - 1, y - 1, width / 2 + bgW / 2 + 1, y + headerLines.size() * 9, Integer.MIN_VALUE);
            for (FormattedCharSequence line : headerLines)
            {
                int w = this.minecraft.font.width(line);
                graphics.drawString(this.minecraft.font, line, width / 2 - w / 2, y, -1);
                y += 9;
            }
            ++y;
        }

        graphics.fill(width / 2 - bgW / 2 - 1, y - 1, width / 2 + bgW / 2 + 1, y + rows * SU_ROW_H, Integer.MIN_VALUE);
        int rowBg = this.minecraft.options.getBackgroundColor(553648127);

        for (int i = 0; i < count; ++i)
        {
            int col = i / rows;
            int row = i % rows;
            int cx = left + col * colW + col * 5;
            int cy = y + row * SU_ROW_H;
            graphics.fill(cx, cy, cx + colW, cy + SU_BG_H, rowBg);
            RenderSystem.enableBlend();
            if (i >= list.size())
                continue;

            PlayerInfo info = list.get(i);
            GameProfile profile = info.getProfile();
            int textY = cy + SU_CONTENT_Y;
            int x = cx;
            if (secure)
            {
                Player p = this.minecraft.level.getPlayerByUUID(profile.getId());
                boolean upsideDown = p != null && LivingEntityRenderer.isEntityUpsideDown(p);
                boolean hat = p != null && p.isModelPartShown(PlayerModelPart.HAT);
                // A disguised player's face is the target's skin once it has resolved.
                DisguiseSkins.Resolved disguised = DisguiseSkins.get(profile.getId());
                PlayerFaceRenderer.draw(graphics, disguised != null ? disguised.location : info.getSkinLocation(),
                        x, textY, 8, hat, upsideDown);
                x += 9;
            }

            graphics.drawString(this.minecraft.font, getNameForDisplay(info), x, textY,
                    info.getGameMode() == GameType.SPECTATOR ? -1862270977 : -1);
            if (objective != null && info.getGameMode() != GameType.SPECTATOR)
            {
                int sx1 = x + nameW + 1;
                int sx2 = sx1 + extra;
                if (sx2 - sx1 > 5)
                    renderTablistScore(objective, cy + SU_CONTENT_Y, profile.getName(), sx1, sx2, profile.getId(), graphics);
            }
            renderPingIcon(graphics, colW, x - (secure ? 9 : 0), textY, info);
        }

        if (footerLines != null)
        {
            y += rows * SU_ROW_H + 1;
            graphics.fill(width / 2 - bgW / 2 - 1, y - 1, width / 2 + bgW / 2 + 1, y + footerLines.size() * 9, Integer.MIN_VALUE);
            for (FormattedCharSequence line : footerLines)
            {
                int w = this.minecraft.font.width(line);
                graphics.drawString(this.minecraft.font, line, width / 2 - w / 2, y, -1);
                y += 9;
            }
        }
    }
}
