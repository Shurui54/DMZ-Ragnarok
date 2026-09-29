package net.shurui.dev.sdu.client.gui.preview;

import com.dragonminez.client.render.effects.AuraRenderer;
import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.hair.HairManager;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.stats.character.Status;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.LivingEntity;
import net.shurui.dev.sdu.form.FormData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.UUID;

// The Form-editor's live character preview. Instead of hand-rendering a GeckoLib model (which forces faking
// the aura and hand-placing the pivot), we build a throwaway client-side RemotePlayer and render it through
// InventoryScreen.renderEntityInInventory, the exact code the vanilla inventory portrait uses, so
// pivot/scale/lighting match and DMZ's real DMZPlayerRenderer (model + skin + hair) draws it.
//
// Seeding: the dummy is seeded once from the real player's StatsData (copyFrom), so it inherits a full
// character (hair slots, eye types, body colours) instead of a bald body. The edited FormData is overlaid
// (active form + its colours/eyes), so it reads as "me, in this form".
//
// Aura: DMZAuraLayer only queues the aura for the world pass, and DMZ's GUI aura path (renderGuiAura) draws
// nothing here, so renderAura blits the real aura sprite (textures/entity/races/aura/<type>_aura.png) itself,
// built LIVE from the edited FormData (base aura + extraAura stack), additively tinted per the editor.
//
// Config vs live: DMZ resolves the custom model, model scaling and hair STYLE from the form's SAVED config.
// Body/eye/aura colours, aura type and the aura stack read straight from the edited form, so they update
// live; model/hair reflect the last save.
public final class FormPreview {

    private RemotePlayer fake;
    private boolean unavailable;
    private boolean seeded;

    /** Lazily build (once) the client-side dummy player. Returns null if we're not in a client level. */
    private RemotePlayer fakePlayer() {
        if (fake != null || unavailable) {
            return fake;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null; // editor is opened in-world, but guard anyway
        }
        try {
            GameProfile profile = new GameProfile(UUID.randomUUID(), "FormPreview");
            fake = new RemotePlayer(mc.level, profile);
        } catch (Throwable t) {
            unavailable = true;
        }
        return fake;
    }

    // true once building the dummy or its DMZ capability failed (screen hides the panel)
    public boolean isUnavailable() {
        // not key-gated: available everywhere (singleplayer + dedicated). editor still degrades gracefully.
        return unavailable;
    }

    private static StatsData statsOf(LivingEntity e) {
        if (e == null) {
            return null;
        }
        return StatsProvider.<StatsData>get(StatsCapability.INSTANCE, e).resolve().orElse(null);
    }

    /**
     * Push the edited form's appearance onto the dummy's DMZ character. Cheap; called every frame so live
     * colour/eye edits show immediately. On first call the dummy is seeded from the real player. {@code race}
     * anchors the base body (blank keeps the player's own race); {@code group}+form name select the active form.
     */
    public void configure(FormData form, String race, String group, boolean auraOn) {
        RemotePlayer p = fakePlayer();
        if (p == null) {
            return;
        }
        StatsData stats = statsOf(p);
        if (stats == null) {
            unavailable = true; // DMZ didn't attach its capability to our dummy - bail cleanly
            return;
        }
        try {
            // Seed once from the real player so hair slots / eye types / body colours are populated.
            if (!seeded) {
                StatsData self = statsOf(Minecraft.getInstance().player);
                if (self != null) {
                    stats.copyFrom(self);
                }
                seeded = true;
            }

            Character c = stats.getCharacter();
            Status s = stats.getStatus();

            s.setHasCreatedCharacter(true);
            s.setAlive(true);

            if (race != null && !race.isBlank()) {
                c.setRace(race); // else keep the seeded player's race (e.g. for race-agnostic stack forms)
            }

            // Force the default facial feature set so eyes/nose/mouth (and colours) render and stay editable
            // regardless of race. NOTE: DMZ picks the face renderer by race in DMZSkinLayer, so races that ship
            // no face textures still won't show editable faces; this only guarantees a valid default index for
            // races that do.
            c.setEyesType(0);
            c.setNoseType(0);
            c.setMouthType(0);

            // Select the form under edit so DMZ pulls its custom model / hair style / aura from config.
            String formName = form.name == null ? "" : form.name;
            if (group != null && !group.isBlank()) {
                c.setActiveFormGroup(group);
            }
            c.setActiveForm(group == null ? "" : group, formName);

            // Live colour/eye pushes (the form's own values; blank = leave the seeded default).
            if (!form.bodyColor1.isBlank()) {
                c.setBodyColor(form.bodyColor1);
            }
            if (!form.bodyColor2.isBlank()) {
                c.setBodyColor2(form.bodyColor2);
            }
            if (!form.bodyColor3.isBlank()) {
                c.setBodyColor3(form.bodyColor3);
            }
            if (!form.hairColor.isBlank()) {
                c.setHairColor(form.hairColor);
                // When a form is active, DMZHairLayer reads the hair colour from the form's CONFIG
                // (getRgbForForm, gated by hasHairColorOverride), NOT the character, so the live character
                // colour above is ignored for a saved form. Push the live colour onto the active form's config
                // object too so the edit shows immediately.
                pushHairColor(c.getActiveFormData(), form.hairColor);
                pushHairColor(c.getActiveStackFormData(), form.hairColor);
            }
            if (!form.eye1Color.isBlank()) {
                c.setEye1Color(form.eye1Color);
            }
            if (!form.eye2Color.isBlank()) {
                c.setEye2Color(form.eye2Color);
            }
            if (!form.auraColor.isBlank()) {
                c.setAuraColor(form.auraColor);
            }

            s.setAuraActive(auraOn);
        } catch (Throwable t) {
            // Any DMZ-internal shape mismatch: don't crash the editor, just stop offering the preview.
            unavailable = true;
        }
    }

    /**
     * Decode the form's forced hair code and apply it to every hair slot of the dummy, so it shows regardless
     * of which slot (base/ssj/…) the active form selects. Uses DMZ's {@link HairManager} decoder (single and
     * full-set codes). Returns true on success.
     */
    public boolean applyHairCode(String code) {
        if (code == null || code.isBlank() || isUnavailable()) {
            return false;
        }
        StatsData stats = statsOf(fakePlayer());
        if (stats == null) {
            return false;
        }
        try {
            Character c = stats.getCharacter();
            // DMZHairLayer renders HairManager.getEffectiveHair(c), which uses the CUSTOM hairBase slot only
            // when hairId == 0 (any non-zero id = a preset). copyFrom seeded the player's preset id, so our
            // custom hair was ignored until we force the custom sentinel here.
            c.setHairId(0);
            c.setRenderHairBase(true);
            if (HairManager.isFullSetCode(code)) {
                CustomHair[] set = HairManager.fromFullSetCode(code);
                if (set != null && set.length >= 4) {
                    c.setHairBase(set[0]);
                    c.setHairSSJ(set[1]);
                    c.setHairSSJ2(set[2]);
                    c.setHairSSJ3(set[3]);
                    return true;
                }
                return false;
            }
            CustomHair h = HairManager.fromCode(code);
            if (h == null || h.isEmpty()) {
                return false;
            }
            c.setHairBase(h);
            c.setHairSSJ(h.copy());
            c.setHairSSJ2(h.copy());
            c.setHairSSJ3(h.copy());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Render just the character model, clipped to the preview box. {@code (cx, feetY)} is the horizontal centre
     * and feet baseline (the model grows upward, matching the inventory portrait); {@code scale} is the pixel
     * size; {@code yaw}/{@code pitch} are the drag-controlled rotation in radians. Wrapped so a render hiccup
     * disables the panel instead of taking down the GUI.
     */
    public void renderModel(GuiGraphics g, int cx, int feetY, int scale, float yaw, float pitch) {
        RemotePlayer p = fakePlayer();
        if (p == null || unavailable) {
            return;
        }
        // rotateZ(PI) flips the model upright (the inventory scales Y by -scale); yaw/pitch then orbit it.
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        pose.rotateY(yaw);
        pose.rotateX(pitch);

        // Freeze the entity's own body/head orientation so the pose quaternion is the sole rotation source,
        // otherwise the renderer's yBodyRot fights the drag.
        p.yBodyRot = 0.0F;
        p.yBodyRotO = 0.0F;
        p.setYRot(0.0F);
        p.yRotO = 0.0F;
        p.setXRot(0.0F);
        p.xRotO = 0.0F;
        p.setYHeadRot(0.0F);
        p.yHeadRot = 0.0F;
        p.yHeadRotO = 0.0F;

        try {
            InventoryScreen.renderEntityInInventory(g, cx, feetY, scale, pose, null, p);
        } catch (Throwable t) {
            unavailable = true;
        }
    }

    private com.mojang.blaze3d.pipeline.TextureTarget maskTarget;

    /**
     * Real silhouette outline: render the model into an offscreen mask, then draw only the pixels JUST OUTSIDE
     * the silhouette (edge-detected in the {@code sdu:outline} shader), tinted the outline colour. Unlike the
     * offset-copies trick this gives a clean edge even with a translucent model. Call BEFORE the real model.
     * {@code thickness} is the outline width in framebuffer pixels.
     */
    public void renderOutline(GuiGraphics g, int cx, int feetY, int scale, float yaw, float pitch,
                              float thickness, float[] primary, float[] secondary, float noiseScale, float mixSpeed) {
        RemotePlayer p = fakePlayer();
        net.minecraft.client.renderer.ShaderInstance shader = net.shurui.dev.sdu.client.SduShaders.outline();
        if (p == null || unavailable || shader == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        com.mojang.blaze3d.pipeline.RenderTarget main = mc.getMainRenderTarget();
        int w = main.width, h = main.height;
        if (w <= 0 || h <= 0) {
            return;
        }
        try {
            if (maskTarget == null) {
                maskTarget = new com.mojang.blaze3d.pipeline.TextureTarget(w, h, true, Minecraft.ON_OSX);
            } else if (maskTarget.width != w || maskTarget.height != h) {
                maskTarget.resize(w, h, Minecraft.ON_OSX);
            }

            // 1) Render the model's silhouette into the offscreen mask (same transform as the real model).
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            pose.rotateY(yaw);
            pose.rotateX(pitch);
            freezeRotations(p);
            maskTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            maskTarget.clear(Minecraft.ON_OSX);
            maskTarget.bindWrite(true);
            InventoryScreen.renderEntityInInventory(g, cx, feetY, scale, pose, null, p);
            g.flush();
            main.bindWrite(true);

            // 2) Draw a full-screen quad; the shader colours only the silhouette edge. Scissor (set by the
            //    caller) keeps it inside the preview box.
            int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
            RenderSystem.setShader(net.shurui.dev.sdu.client.SduShaders::outline);
            RenderSystem.setShaderTexture(0, maskTarget.getColorTextureId());
            var priUni = shader.getUniform("PrimaryColor");
            if (priUni != null) {
                priUni.set(primary[0], primary[1], primary[2], 1.0F);
            }
            var secUni = shader.getUniform("SecondaryColor");
            if (secUni != null) {
                secUni.set(secondary[0], secondary[1], secondary[2], 1.0F);
            }
            var stepUni = shader.getUniform("TexelStep");
            if (stepUni != null) {
                stepUni.set(Math.max(1.0F, thickness) / w, Math.max(1.0F, thickness) / h);
            }
            var timeUni = shader.getUniform("Time");
            if (timeUni != null) {
                timeUni.set((float) ((System.currentTimeMillis() % 100000L) / 1000.0));
            }
            var noiseUni = shader.getUniform("NoiseScale");
            if (noiseUni != null) {
                noiseUni.set(noiseScale);
            }
            var mixUni = shader.getUniform("ColorMixSpeed");
            if (mixUni != null) {
                mixUni.set(mixSpeed);
            }
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            Matrix4f mvp = g.pose().last().pose();
            com.mojang.blaze3d.vertex.BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
            bb.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
            // V is flipped: GUI y=0 (top) samples the top of the framebuffer texture (v=1).
            bb.vertex(mvp, 0, 0, 0).uv(0.0F, 1.0F).endVertex();
            bb.vertex(mvp, 0, gh, 0).uv(0.0F, 0.0F).endVertex();
            bb.vertex(mvp, gw, gh, 0).uv(1.0F, 0.0F).endVertex();
            bb.vertex(mvp, gw, 0, 0).uv(1.0F, 1.0F).endVertex();
            com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.end());
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        } catch (Throwable t) {
            if (!auraErrLogged) {
                auraErrLogged = true;
                net.shurui.dev.sdu.DmzNpc.LOGGER.warn("[sdu] outline failed: {}", t.toString());
            }
        }
    }

    private static void freezeRotations(RemotePlayer p) {
        p.yBodyRot = 0.0F;
        p.yBodyRotO = 0.0F;
        p.setYRot(0.0F);
        p.yRotO = 0.0F;
        p.setXRot(0.0F);
        p.xRotO = 0.0F;
        p.setYHeadRot(0.0F);
        p.yHeadRot = 0.0F;
        p.yHeadRotO = 0.0F;
    }

    /** One aura ring to draw: sprite type, colour (hex), and layer index (drives its size). */
    private record AuraSpec(String type, String color, int layerId) {
    }

    /**
     * Render the form's aura by blitting the REAL DMZ aura sprite
     * ({@code textures/entity/races/aura/<type>_aura.png}, a 1x4 animation strip), additively tinted with the
     * editor colour. Layers are built LIVE from the edited {@link FormData} (base aura + {@code extraAura}
     * stack), NOT DMZ's getAuraLayers (which only reads saved config), so unsaved edits show immediately.
     * {@code centerY} is the model centre, {@code modelH} its on-screen height.
     */
    public void renderAura(GuiGraphics g, FormData form, int cx, int centerY, int modelH) {
        if (unavailable || form == null) {
            return;
        }
        java.util.List<AuraSpec> specs = new java.util.ArrayList<>();
        if (form.auraType != null && !form.auraType.isBlank()) {
            specs.add(new AuraSpec(form.auraType, form.auraColor, form.auraLayer));
        }
        if (form.extraAura != null) {
            for (net.shurui.dev.sdu.form.FormAuraData.Layer l : form.extraAura.layers) {
                if (l.type != null && !l.type.isBlank()) {
                    specs.add(new AuraSpec(l.type, l.color, l.layer));
                }
            }
        }
        if (specs.isEmpty()) {
            return;
        }
        // The fake player is never ticked, so drive the 4-frame animation off the wall clock (~10 fps).
        int frame = (int) ((System.currentTimeMillis() / 100L) & 3L);
        float u0 = frame * 0.25F, u1 = u0 + 0.25F;
        // Aura billboard size relative to the model, matching DMZ: it scales the aura by getAuraScale and the
        // aura reads ~2x the model. Reflect DMZ's auraScale so per-form aura scaling is respected.
        float auraScale = reflectAuraScaleY(statsOf(fakePlayer()));
        // SDU per-form aura size multipliers (default 1.0), mirroring the in-world AuraScaleMixin.
        float wMul = form.extraAura != null ? form.extraAura.width : 1.0F;
        float hMul = form.extraAura != null ? form.extraAura.height : 1.0F;
        int baseW = Math.round(modelH * auraScale * 2.0F * wMul);
        int baseH = Math.round(modelH * auraScale * 2.0F * hMul);
        int auraCy = centerY - Math.round(modelH * 0.34F); // aura sits well above the model centre
        int feetY = centerY + modelH / 2;                  // ground/cross part sits at the feet

        Matrix4f mat = g.pose().last().pose();
        RenderSystem.enableBlend();
        // Additive: the aura textures are grayscale with no alpha, so black must add nothing (be invisible).
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        net.minecraft.resources.ResourceLocation sparks = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "dragonminez", "textures/entity/races/aura/sparking_effects.png");
        try {
            for (AuraSpec spec : specs) {
                String type = spec.type().toLowerCase(java.util.Locale.ROOT);
                float[] rgb = hexToRgb(spec.color());
                float r = rgb[0], gr = rgb[1], b = rgb[2];
                // Each stacked layer is larger (DMZ radius = 1 + layerId*0.15) so layers read as distinct.
                float grow = 1.0F + Math.max(0, spec.layerId()) * 0.15F;
                int sideW = Math.round(baseW * grow);
                int sideH = Math.round(baseH * grow);
                // Main billboard, dimmed a bit (additive over-brightens vs the game's alpha blend).
                auraQuad(mat, auraTex(type, "aura"), cx, auraCy, sideW, sideH, u0, u1, r, gr, b, 0.6F);
                // Ground/cross part at the feet - wide + flattened to fake the ground-plane perspective.
                int cw = Math.round(sideW * 0.85F);
                auraQuad(mat, auraTex(type, "cross"), cx, feetY, cw, Math.round(cw * 0.42F), u0, u1, r, gr, b, 0.55F);
                // Sparks are drawn PER LAYER in DMZ (on the same billboard), tinted by that layer and
                // animated by the same 4-frame slice. DMZ scales them 2.2 vs the aura's 2.0 => 1.1x.
                int ssW = Math.round(sideW * 1.1F);
                int ssH = Math.round(sideH * 1.1F);
                auraQuad(mat, sparks, cx, auraCy, ssW, ssH, u0, u1, r, gr, b, 0.5F);
            }
            // Lightning: DMZ's is a procedural vertex-shader bolt mesh (no texture) that won't draw via the GUI
            // shader path, so approximate it with animated jagged bolts tinted the lightning colour.
            if (form.hasLightnings && form.lightningColor != null && !form.lightningColor.isBlank()) {
                drawLightning(mat, cx, centerY - modelH / 2, centerY + modelH / 2, modelH, hexToRgb(form.lightningColor));
            }
        } catch (Throwable t) {
            if (!auraErrLogged) {
                auraErrLogged = true;
                net.shurui.dev.sdu.DmzNpc.LOGGER.warn("[sdu] aura blit failed: {}", t.toString());
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private boolean auraErrLogged;

    /**
     * Push the live hair colour onto an active form's CONFIG object (where DMZHairLayer reads it when a form is
     * active). Null-safe. Mutates DMZ's in-memory config for the previewed form (the transient editor state we
     * want); disk config is untouched.
     */
    private static void pushHairColor(com.dragonminez.common.config.FormConfig.FormData fd, String hex) {
        if (fd == null) {
            return;
        }
        try {
            fd.setHairColor(hex);
            fd.setRgbHairColor(hexToRgb(hex));
        } catch (Throwable ignored) {
        }
    }

    /** Draw a few animated jagged lightning bolts spanning {@code topY..botY}, tinted {@code rgb}. */
    private static void drawLightning(Matrix4f mat, int cx, int topY, int botY, int modelH, float[] rgb) {
        // DMZ's bolts have a near-white hot core (mix(color, white, ~0.5)); brighten to match.
        float r = rgb[0] * 0.5F + 0.5F, g = rgb[1] * 0.5F + 0.5F, b = rgb[2] * 0.5F + 0.5F;
        int span = Math.max(1, botY - topY);
        float spread = modelH * 0.32F;                 // how far bolts sit from centre
        float thick = Math.max(1.5F, modelH * 0.02F);
        long tick = System.currentTimeMillis() / 90L;  // regenerate ~11x/sec so bolts flicker
        java.util.Random rnd = new java.util.Random(tick);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        com.mojang.blaze3d.vertex.BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        bb.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        int bolts = 4;
        int segs = 7;
        for (int i = 0; i < bolts; i++) {
            float a = 0.55F + rnd.nextFloat() * 0.4F;  // per-bolt brightness flicker
            boolean horizontal = rnd.nextFloat() < 0.35F; // ~1 in 3 bolts arcs sideways
            if (horizontal) {
                float baseY = topY + rnd.nextFloat() * span;
                float x0 = cx - spread, x1 = cx + spread;
                float px = x0, py = baseY;
                for (int s = 1; s <= segs; s++) {
                    float x = x0 + (x1 - x0) * s / segs;
                    float y = baseY + (rnd.nextFloat() * 2 - 1) * modelH * 0.10F;
                    boltSegment(bb, mat, px, py, x, y, thick, r, g, b, a);
                    px = x;
                    py = y;
                }
            } else {
                float baseX = cx + (rnd.nextFloat() * 2 - 1) * spread;
                float px = baseX, py = topY;
                for (int s = 1; s <= segs; s++) {
                    float y = topY + (float) span * s / segs;
                    float x = baseX + (rnd.nextFloat() * 2 - 1) * modelH * 0.10F;
                    boltSegment(bb, mat, px, py, x, y, thick, r, g, b, a);
                    px = x;
                    py = y;
                }
            }
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.end());
    }

    /** One thick line segment (a quad) between two points, for {@link #drawLightning}. */
    private static void boltSegment(com.mojang.blaze3d.vertex.BufferBuilder bb, Matrix4f mat,
                                    float x1, float y1, float x2, float y2, float t,
                                    float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001F) {
            return;
        }
        float nx = -dy / len * t * 0.5F, ny = dx / len * t * 0.5F;
        bb.vertex(mat, x1 + nx, y1 + ny, 0).color(r, g, b, a).endVertex();
        bb.vertex(mat, x2 + nx, y2 + ny, 0).color(r, g, b, a).endVertex();
        bb.vertex(mat, x2 - nx, y2 - ny, 0).color(r, g, b, a).endVertex();
        bb.vertex(mat, x1 - nx, y1 - ny, 0).color(r, g, b, a).endVertex();
    }

    private static net.minecraft.resources.ResourceLocation auraTex(String type, String part) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                "dragonminez", "textures/entity/races/aura/" + type + "_" + part + ".png");
    }

    /** Blit one textured quad (frame slice {@code u0..u1}) centred at {@code (cx,cy)}, tinted {@code rgba}. */
    private static void auraQuad(Matrix4f mat, net.minecraft.resources.ResourceLocation tex,
                                 int cx, int cy, int w, int h, float u0, float u1,
                                 float r, float g, float b, float a) {
        float x0 = cx - w / 2.0F, x1 = cx + w / 2.0F, y0 = cy - h / 2.0F, y1 = cy + h / 2.0F;
        RenderSystem.setShaderTexture(0, tex);
        com.mojang.blaze3d.vertex.BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        bb.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(mat, x0, y1, 0).uv(u0, 1.0F).color(r, g, b, a).endVertex();
        bb.vertex(mat, x1, y1, 0).uv(u1, 1.0F).color(r, g, b, a).endVertex();
        bb.vertex(mat, x1, y0, 0).uv(u1, 0.0F).color(r, g, b, a).endVertex();
        bb.vertex(mat, x0, y0, 0).uv(u0, 0.0F).color(r, g, b, a).endVertex();
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.end());
    }

    /** Reflect DMZ's private getModelScale/getAuraScale for the vertical aura scale (per-form), default 1. */
    private static float reflectAuraScaleY(StatsData st) {
        if (st == null) {
            return 1.0F;
        }
        try {
            java.lang.reflect.Method gms = AuraRenderer.class.getDeclaredMethod("getModelScale", StatsData.class);
            gms.setAccessible(true);
            Object ms = gms.invoke(null, st);
            java.lang.reflect.Method gas = AuraRenderer.class.getDeclaredMethod("getAuraScale", StatsData.class, float[].class);
            gas.setAccessible(true);
            float[] as = (float[]) gas.invoke(null, st, ms);
            if (as != null && as.length >= 2 && as[1] > 0.01F) {
                return as[1];
            }
        } catch (Throwable ignored) {
        }
        return 1.0F;
    }

    /** "#RRGGBB" -> {r,g,b} in 0..1; white when blank/invalid (matches DMZ's default aura tint). */
    private static float[] hexToRgb(String hex) {
        if (hex != null) {
            String s = hex.trim();
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            if (s.length() == 6) {
                try {
                    int rgb = Integer.parseInt(s, 16);
                    return new float[]{((rgb >> 16) & 0xFF) / 255F, ((rgb >> 8) & 0xFF) / 255F, (rgb & 0xFF) / 255F};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new float[]{1F, 1F, 1F};
    }

    /** Best-effort current-player race, used when previewing a group with no owning race (stack forms). */
    public static String currentPlayerRace() {
        try {
            StatsData stats = statsOf(Minecraft.getInstance().player);
            if (stats != null) {
                String r = stats.getCharacter().getRaceName();
                if (r != null && !r.isBlank()) {
                    return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return "human";
    }
}
