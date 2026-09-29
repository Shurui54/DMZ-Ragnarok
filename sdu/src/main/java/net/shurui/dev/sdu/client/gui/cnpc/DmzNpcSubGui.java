package net.shurui.dev.sdu.client.gui.cnpc;

import com.goodbird.cnpcgeckoaddon.client.gui.GuiStringSelection;
import com.goodbird.cnpcgeckoaddon.data.CustomModelData;
import com.goodbird.cnpcgeckoaddon.mixin.IDataDisplay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.client.gui.DmzColorPicker;
import net.shurui.dev.sdu.compat.DmzTechniques;
import net.shurui.dev.sdu.compat.cnpc.CnpcGeckoBridge;
import net.shurui.dev.sdu.compat.cnpc.DmzAssetRegistry;
import net.shurui.dev.sdu.compat.cnpc.DmzModelPreset;
import net.shurui.dev.sdu.compat.cnpc.DmzModelPresets;
import net.shurui.dev.sdu.compat.cnpc.SduHairHolder;
import net.shurui.dev.sdu.compat.cnpc.SduNpcData;
import noppes.npcs.client.gui.util.GuiNPCInterface;
import noppes.npcs.entity.EntityCustomNpc;
import noppes.npcs.entity.EntityNPCInterface;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.components.GuiLabel;
import noppes.npcs.shared.client.gui.components.GuiTextFieldNop;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;
import noppes.npcs.shared.client.gui.listeners.ITextfieldListener;

import java.util.ArrayList;

// DMZ editor tab for a Custom NPC (top tab from GuiNpcMenuDmzTabMixin, and from the model editor).
// Model column: one-click preset, geo model + animation set, idle/walk/attack/hurt clips, hair code, hair
// colour via the DMZ HSV picker. Ki/combat column: ki blast multi-select and "Make DMZ Saga Fighter".
// Stat fields (BP/HP/ki/melee/speed/AI tier) were pulled: combat stats are set where the NPC spawns to fight
// (saga objective editor, spawn GUIs), so balance lives in one place. Everything writes to the NPC's
// CustomModelData/display and SduNpcData/SduHairHolder, which CNPC's editor-save round-trips to the server.
public class DmzNpcSubGui extends GuiNPCInterface implements ITextfieldListener {

    private static final int ID_PRESET = 9;
    private static final int ID_RAGNAROK = 8;
    private static final int ID_MODEL = 10;
    private static final int ID_ANIM = 11;
    private static final int ID_APPLY = 12;
    private static final int ID_HAIRCOLOR = 14;
    private static final int ID_KIADD = 15;
    private static final int ID_KICLEAR = 16;
    private static final int ID_NORANGED = 17;
    private static final int ID_MAKEFIGHTER = 18;
    private static final int ID_CLOSE = 670;

    private static final int TF_IDLE = 20;
    private static final int TF_WALK = 21;
    private static final int TF_ATTACK = 22;
    private static final int TF_HURT = 23;
    private static final int TF_HAIR = 24;
    private static final int TF_BP = 30;
    private static final int TF_HEALTH = 31;
    private static final int TF_KIDMG = 32;
    private static final int TF_KISPEED = 33;
    private static final int TF_MELEE = 34;
    private static final int TF_AITIER = 35;
    private static final int TF_DEFENSE = 36;

    /** HSV picker overlay for hair colour (same picker the form editor uses). */
    private DmzColorPicker hairColorPicker;

    public DmzNpcSubGui(EntityNPCInterface npc) {
        this.npc = npc;
        this.closeOnEsc = true;
    }

    private CustomModelData data() {
        return ((IDataDisplay) this.npc.display).getCustomModelData();
    }

    /**
     * {@link #data()} for a WRITE. Flips the NPC onto the addon's render proxy first: the addon only writes the
     * CustomModelData block to NBT while the ModelData entity IS that proxy (see
     * {@link CnpcGeckoBridge#ensureCustomModelEntity}). Picking a model and closing without "Apply Model" used
     * to set it in memory then lose it on save, which made a DMZ model look reverted to Steve after a reload.
     *
     * <p>Reads stay on {@link #data()}: this has a side effect, don't call it just to redraw a row.
     */
    private CustomModelData writeModelData() {
        if (this.npc instanceof EntityCustomNpc customNpc) {
            CnpcGeckoBridge.ensureCustomModelEntity(customNpc);
        }
        return data();
    }

    private SduNpcData stats() {
        return (SduNpcData) this.npc.display;
    }

    @Override
    public void init() {
        super.init();
        CustomModelData d = data();

        int lx = this.guiLeft + 8;
        int y = this.guiTop + 6;
        // Two one-click model sets share this row: at large GUI scales the column already runs to the screen
        // bottom, so an extra row would push Apply Model off it.
        this.addLabel(new GuiLabel(0, "Preset", lx, y + 5, 0xFFFFFF));
        boolean ragnarok = RagnarokModelPicker.hasEntries();
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_PRESET,
                lx + 74, y, ragnarok ? 74 : 150, 20, "DMZ"));
        // Ragnarok characters, browsable by entry id (same id /su entity takes). Absent when Shurui's Utilities
        // is missing or every entry is key-gated on a keyless server, when the button would only open an empty list.
        if (ragnarok) {
            this.addButton(new GuiButtonNop((IGuiInterface) this, ID_RAGNAROK, lx + 150, y, 74, 20, "Ragnarok"));
        }
        y += 24;
        this.addLabel(new GuiLabel(1, "Model", lx, y + 5, 0xFFFFFF));
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_MODEL, lx + 74, y, 150, 20, shortName(d.getModel(), "<model>")));
        y += 22;
        this.addLabel(new GuiLabel(2, "Animations", lx, y + 5, 0xFFFFFF));
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_ANIM, lx + 74, y, 150, 20, shortName(d.getAnimFile(), "<animations>")));
        y += 24;
        this.addLabel(new GuiLabel(3, "Idle clip", lx, y + 5, 0xFFFFFF));
        this.addTextField(new GuiTextFieldNop(TF_IDLE, (Screen) this, lx + 74, y, 150, 20, nz(d.getIdleAnim())));
        y += 22;
        this.addLabel(new GuiLabel(4, "Walk clip", lx, y + 5, 0xFFFFFF));
        this.addTextField(new GuiTextFieldNop(TF_WALK, (Screen) this, lx + 74, y, 150, 20, nz(d.getWalkAnim())));
        y += 22;
        this.addLabel(new GuiLabel(5, "Attack clip", lx, y + 5, 0xFFFFFF));
        this.addTextField(new GuiTextFieldNop(TF_ATTACK, (Screen) this, lx + 74, y, 150, 20, nz(d.getAttackAnim())));
        y += 22;
        this.addLabel(new GuiLabel(6, "Hurt clip", lx, y + 5, 0xFFFFFF));
        this.addTextField(new GuiTextFieldNop(TF_HURT, (Screen) this, lx + 74, y, 150, 20, nz(d.getHurtAnim())));
        y += 24;
        this.addLabel(new GuiLabel(7, "Hair code", lx, y + 5, 0xFFFFFF));
        GuiTextFieldNop hair = new GuiTextFieldNop(TF_HAIR, (Screen) this, lx + 74, y, 150, 20, hairCode());
        hair.setMaxLength(20000);
        this.addTextField(hair);
        y += 22;
        this.addLabel(new GuiLabel(8, "Hair color", lx, y + 5, 0xFFFFFF));
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_HAIRCOLOR, lx + 74, y, 150, 20, hairColorLabel()));
        y += 26;
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_APPLY, lx + 74, y, 150, 20, "Apply Model"));

        // Stat fields absent: combat stats live in the spawn GUIs so every fightable NPC is balanced in one place.
        int rx = this.guiLeft + 250;
        int ry = this.guiTop + 6;
        this.addLabel(new GuiLabel(20, "§7Most combat stats are set where the NPC", rx, ry + 3, 0xFFAAAAAA));
        ry += 12;
        this.addLabel(new GuiLabel(21, "§7is spawned (saga objective editor).", rx, ry + 3, 0xFFAAAAAA));
        ry += 18;
        this.addLabel(new GuiLabel(26, "Ki moves", rx, ry + 5, 0xFFFFFF));
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_KIADD, rx + 76, ry, 74, 20, "Add..."));
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_KICLEAR, rx + 152, ry, 60, 20, "Clear"));
        ry += 20;
        this.addLabel(new GuiLabel(27, "§7" + kiMovesSummary(), rx, ry + 3, 0xFFAAAAAA));
        ry += 22;
        // Defense: DMZ-scale value fed into sdu's NPC-defense mitigation curve (0 = no mitigation). Stored on
        // SduNpcData and threaded into SpawnFighterPacket. The one stat sdu itself mitigates, so it lives here.
        this.addLabel(new GuiLabel(28, "Defense", rx, ry + 5, 0xFFFFFF));
        this.addTextField(new GuiTextFieldNop(TF_DEFENSE, (Screen) this, rx + 76, ry, 74, 20, fmtDef(stats().sdu$getDefense())));
        ry += 22;
        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_MAKEFIGHTER, rx, ry, 212, 20, "Make DMZ Saga Fighter"));

        this.addButton(new GuiButtonNop((IGuiInterface) this, ID_CLOSE, this.width - 22, 2, 20, 20, "X"));
    }

    @Override
    public void buttonEvent(GuiButtonNop button) {
        switch (button.id) {
            case ID_PRESET -> this.setSubGui((Screen) new GuiStringSelection((Screen) this, "Select a DMZ preset:",
                    DmzModelPreset.displayNames(DmzModelPresets.all()), name -> applyPreset(DmzModelPresets.byDisplayName(name))));
            case ID_RAGNAROK -> openRagnarokPicker();
            case ID_MODEL -> this.setSubGui((Screen) new GuiStringSelection((Screen) this, "Select DMZ model:",
                    DmzAssetRegistry.geoModels(), name -> {
                        writeModelData().setModel(name);
                        this.getButton(ID_MODEL).setDisplayText(shortName(name, "<model>"));
                    }));
            case ID_ANIM -> this.setSubGui((Screen) new GuiStringSelection((Screen) this, "Select DMZ animations:",
                    DmzAssetRegistry.animationFiles(), name -> {
                        writeModelData().setAnimFile(name);
                        this.getButton(ID_ANIM).setDisplayText(shortName(name, "<animations>"));
                    }));
            case ID_HAIRCOLOR -> openHairColorPicker();
            case ID_KIADD -> this.setSubGui((Screen) new GuiStringSelection((Screen) this, "Add a ki move:",
                    new ArrayList<>(DmzTechniques.kiSkillTypeNames()), this::addKiMove));
            case ID_KICLEAR -> {
                stats().sdu$setKiMoves("");
                this.setSubGui((Screen) new DmzNpcSubGui(this.npc));
            }
            case ID_MAKEFIGHTER -> makeSagaFighter();
            case ID_APPLY -> {
                // Kept for an NPC already on a model before any row was touched. Every write now flips the
                // proxy itself, so this is confirm-and-close, not the step that makes the model stick.
                if (this.npc instanceof EntityCustomNpc customNpc) {
                    CnpcGeckoBridge.ensureCustomModelEntity(customNpc);
                }
                this.close();
            }
            case ID_CLOSE -> this.close();
            default -> { }
        }
    }

    /**
     * Replace this Custom NPC with a real DMZ saga fighter (saga AI + animations) from the current tab values.
     * Sent to the server so it works even before the NPC is saved.
     */
    private void makeSagaFighter() {
        try {
            // Text fields only write on blur; commit first so unblurred edits aren't lost (why values seemed
            // not to apply while the model/animation buttons did).
            for (int id : new int[]{TF_HAIR}) {
                GuiTextFieldNop tf = this.getTextField(id);
                if (tf != null) {
                    unFocused(tf);
                }
            }
            SduNpcData s = stats();
            int skinType = this.npc.display.skinType;
            String skinValue = switch (skinType) {
                case 1 -> nz(this.npc.display.getSkinPlayer());
                case 2 -> nz(this.npc.display.getSkinUrl());
                default -> nz(this.npc.display.getSkinTexture());
            };
            net.shurui.dev.sdu.network.DmzNet.sendToServer(new net.shurui.dev.sdu.network.SpawnFighterPacket(
                    this.npc.getId(), nz(data().getModel()), skinType, skinValue,
                    hairCode(), hairColor(), s.sdu$getBattlePower(), s.sdu$getHealth(), s.sdu$getKiBlastDamage(),
                    s.sdu$getMoveSpeed(), s.sdu$getMeleeDamage(), s.sdu$getAiTier(), s.sdu$getKiMoves(),
                    s.sdu$isNoRanged(), s.sdu$getDefense()));
            this.close();
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Make saga fighter failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** Browse ragnarok characters. A pick applies geo + texture + hitbox + saga clips at once, then re-reads the rows it wrote. */
    private void openRagnarokPicker() {
        Screen picker = RagnarokModelPicker.build((Screen) this, this.npc, this::refreshModelRows);
        if (picker != null) {
            this.setSubGui(picker);
        }
    }

    /** Re-read the model / animation rows from the NPC. Called after something else has written them. */
    private void refreshModelRows() {
        CustomModelData d = data();
        GuiButtonNop model = this.getButton(ID_MODEL);
        if (model != null) {
            model.setDisplayText(shortName(d.getModel(), "<model>"));
        }
        GuiButtonNop anim = this.getButton(ID_ANIM);
        if (anim != null) {
            anim.setDisplayText(shortName(d.getAnimFile(), "<animations>"));
        }
        setFieldValue(TF_IDLE, d.getIdleAnim());
        setFieldValue(TF_WALK, d.getWalkAnim());
        setFieldValue(TF_ATTACK, d.getAttackAnim());
        setFieldValue(TF_HURT, d.getHurtAnim());
    }

    private void setFieldValue(int id, String value) {
        GuiTextFieldNop tf = this.getTextField(id);
        if (tf != null) {
            tf.setValue(nz(value));
        }
    }

    private void applyPreset(DmzModelPreset preset) {
        if (preset == null || !(this.npc instanceof EntityCustomNpc customNpc)) {
            return;
        }
        CnpcGeckoBridge.applyDmzModel(customNpc, preset.geo(), preset.animation(),
                preset.idle(), preset.walk(), preset.attack(), preset.hurt(), preset.texture());
        this.setSubGui((Screen) new DmzNpcSubGui(this.npc));
    }

    /** Append a ki move token {@code TYPE:cooldown:size} (defaults 60 ticks / size 1.0; edit the summary token to tune). */
    private void addKiMove(String type) {
        if (type == null || type.isBlank()) {
            return;
        }
        String cur = stats().sdu$getKiMoves();
        java.util.List<String> list = new ArrayList<>();
        if (!cur.isBlank()) {
            for (String p : cur.split(",")) {
                if (!p.isBlank()) {
                    list.add(p.trim());
                }
            }
        }
        String token = type.trim().toUpperCase(java.util.Locale.ROOT) + ":60:1.0";
        // replace same-type move, don't duplicate
        list.removeIf(t -> t.split(":")[0].equalsIgnoreCase(type.trim()));
        list.add(token);
        stats().sdu$setKiMoves(String.join(",", list));
        this.setSubGui((Screen) new DmzNpcSubGui(this.npc));
    }

    private String kiMovesSummary() {
        String cur = stats().sdu$getKiMoves();
        if (cur == null || cur.isBlank()) {
            return "(none)";
        }
        int n = cur.split(",").length;
        String shown = cur.length() > 42 ? cur.substring(0, 40) + "..." : cur;
        return n + ": " + shown;
    }

    private void openHairColorPicker() {
        hairColorPicker = new DmzColorPicker(this.guiLeft + 74, this.guiTop + 220);
        hairColorPicker.open(hairColor());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (hairColorPicker != null) {
            hairColorPicker.render(graphics, this.getFontRenderer(), mouseX, mouseY);
            if (hairColorPicker.isCloseRequested()) {
                setHairColor(hairColorPicker.hex());
                hairColorPicker = null;
                GuiButtonNop b = this.getButton(ID_HAIRCOLOR);
                if (b != null) {
                    b.setDisplayText(hairColorLabel());
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (hairColorPicker != null) {
            hairColorPicker.mouseClicked(mx, my);
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (hairColorPicker != null) {
            hairColorPicker.mouseDragged(mx, my);
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (hairColorPicker != null) {
            hairColorPicker.mouseReleased();
            return true;
        }
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public void unFocused(GuiTextFieldNop tf) {
        // The four clip fields go through writeModelData() for the same reason as the pickers: a clip name
        // written while the NPC is not on the addon's proxy is dropped by the save.
        switch (tf.id) {
            case TF_IDLE -> writeModelData().setIdleAnim(tf.getValue());
            case TF_WALK -> writeModelData().setWalkAnim(tf.getValue());
            case TF_ATTACK -> writeModelData().setAttackAnim(tf.getValue());
            case TF_HURT -> writeModelData().setHurtAnim(tf.getValue());
            case TF_HAIR -> commitHair(tf);
            case TF_DEFENSE -> commitDefense(tf);
            default -> { }
        }
    }

    /** Store the Defense field as a non-negative double (0 = none). */
    private void commitDefense(GuiTextFieldNop tf) {
        String v = tf.getValue() == null ? "" : tf.getValue().trim();
        double def = 0.0;
        if (!v.isEmpty()) {
            try {
                def = Math.max(0.0, Double.parseDouble(v));
            } catch (NumberFormatException e) {
                tf.setValue(fmtDef(stats().sdu$getDefense()));
                return;
            }
        }
        stats().sdu$setDefense(def);
    }

    /** Defense as a field string, blank for 0 so it reads "unset". */
    private static String fmtDef(double v) {
        if (v <= 0.0) {
            return "";
        }
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private void commitHair(GuiTextFieldNop tf) {
        String code = tf.getValue() == null ? "" : tf.getValue().trim();
        if (code.isEmpty() || net.shurui.dev.sdu.compat.DmzHair.isValidCode(code)) {
            setHairCode(code);
        } else {
            DmzNpc.LOGGER.info("[{}] Rejected invalid DMZ hair code.", DmzNpc.MODID);
            tf.setValue(hairCode());
        }
    }

    private String hairCode() {
        try {
            return nz(((SduHairHolder) this.npc.display).sdu$getHairCode());
        } catch (Throwable t) {
            return "";
        }
    }

    private void setHairCode(String code) {
        try {
            ((SduHairHolder) this.npc.display).sdu$setHairCode(code);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not store hair code: {}", DmzNpc.MODID, t.toString());
        }
    }

    private String hairColor() {
        try {
            return nz(((SduHairHolder) this.npc.display).sdu$getHairColor());
        } catch (Throwable t) {
            return "";
        }
    }

    private void setHairColor(String hex) {
        try {
            ((SduHairHolder) this.npc.display).sdu$setHairColor(hex);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not store hair color: {}", DmzNpc.MODID, t.toString());
        }
    }

    private String hairColorLabel() {
        String c = hairColor();
        return c.isBlank() ? "<use code colors>" : c;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String shortName(String loc, String fallback) {
        if (loc == null || loc.isBlank()) {
            return fallback;
        }
        int slash = loc.lastIndexOf('/');
        return slash >= 0 && slash < loc.length() - 1 ? loc.substring(slash + 1) : loc;
    }
}
