package net.shurui.shuruisutilities.client.combat;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.combat.ClashRhythm;
import net.shurui.shuruisutilities.combat.PacketClashScore;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * The melee clash rhythm game, drawn into the HUD rather than opened as a screen.
 *
 * <p>An overlay and not a {@code Screen} on purpose. DragonMineZ's own Supreme Kai rhythm game is a full screen
 * training menu welded to its hub, and borrowing it would have meant both fighters staring at a menu while their
 * bodies were locked together in the world, unable to see the fight they were in. Drawn as an overlay in the same
 * place the ki clash meter appears, the fight stays visible behind it and the two read as the same kind of moment.
 *
 * <h2>Why the client scores itself</h2>
 * The hit window is seven ticks at its widest. Routing every press to the server and waiting for a verdict would put a
 * round trip inside that window and make a correct press read as late on any real connection. So the client judges its
 * own presses against the shared chart and reports one number at the end, which the server clamps and compares. See
 * {@link net.shurui.shuruisutilities.combat.PacketClashScore} for what that costs and why it is acceptable.
 *
 * <h2>Input</h2>
 * WASD, because the player's hand is already there and they cannot walk during a clash anyway. Presses are edge
 * triggered off the key state each tick rather than taken from key events, so holding a direction cannot machine gun
 * a lane, and each note can only be scored once.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClashRhythmOverlay
{
    private ClashRhythmOverlay() {}

    public static final String OVERLAY_ID = "melee_clash_rhythm";

    // Panel geometry. Sized and placed to sit where DragonMineZ draws its ki clash meter: centred horizontally, in
    // the upper third, clear of the crosshair and of the hotbar.
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 76;
    private static final int PANEL_Y_NUMERATOR = 1;
    private static final int PANEL_Y_DENOMINATOR = 4;

    private static final int LANE_COUNT = 4;
    private static final int NOTE_SIZE = 13;
    private static final int HIT_LINE_INSET = 10;

    private static final int COL_PANEL = 0xB0101018;
    private static final int COL_BORDER = 0xFF6A4CB8;
    private static final int COL_HIT_LINE = 0xFFCFE8B0;
    private static final int COL_NOTE = 0xFF8FD0FF;
    private static final int COL_NOTE_PERFECT = 0xFFFFF06A;
    private static final int COL_NOTE_DEAD = 0x60707070;
    private static final int COL_TEXT = 0xFFE8E8F0;

    // How long the result stays up after the clash ends.
    private static final int RESULT_HOLD_TICKS = 40;

    private static boolean active;
    private static long seed;
    private static String opponent = "";
    private static int tick;
    private static int score;
    private static List<ClashRhythm.Note> notes = new ArrayList<>();
    private static boolean[] consumed = new boolean[0];
    // Sustains in progress, by note index, and whether their tail has already paid out.
    private static boolean[] holding = new boolean[0];
    private static boolean[] holdPaid = new boolean[0];
    private static final boolean[] wasDown = new boolean[LANE_COUNT];

    // result state, drawn after the clash proper has ended
    private static int resultTicks;
    private static int outcome;
    private static int finalOwn;
    private static int finalOpponent;

    /**
     * Which performance this chart is: a fight, or an instrument.
     *
     * <p>The chart, the timing windows, the scoring and the drawing are identical - it is the same minigame - so
     * rather than a second copy of all of it the mode only decides two things: where the score is reported, and
     * whether a hit lands as a punch or as a note.
     */
    private static boolean ocarinaMode;
    private static int ocarinaSong;

    /** Server says a SONG has begun. Same chart machinery, reported to the ocarina instead of a clash. */
    public static void beginOcarina(long songSeed, String songName, int songId)
    {
        begin(songSeed, songName);
        ocarinaMode = true;
        ocarinaSong = songId;
    }

    /** Server says a clash has begun. Build the same chart it did and start drawing. */
    public static void begin(long clashSeed, String opponentName)
    {
        seed = clashSeed;
        opponent = opponentName == null ? "" : opponentName;
        notes = ClashRhythm.timeline(clashSeed);
        consumed = new boolean[notes.size()];
        holding = new boolean[notes.size()];
        holdPaid = new boolean[notes.size()];
        java.util.Arrays.fill(wasDown, false);
        tick = 0;
        score = 0;
        resultTicks = 0;
        ocarinaMode = false;
        active = true;
    }

    /** Server says the clash is over. Stop scoring and show the result for a moment. */
    public static void finish(int clashOutcome, int ownScore, int opponentScore)
    {
        reset();
        outcome = clashOutcome;
        finalOwn = ownScore;
        finalOpponent = opponentScore;
        resultTicks = RESULT_HOLD_TICKS;
    }

    /**
     * The one definition of "no clash in progress": every per-clash field back to its idle value, nothing scoring and
     * nothing drawing. {@link #finish} calls this before it sets the result to show, and the logout handler calls it so a
     * clash abandoned by a disconnect cannot carry its movement lock or overlay into the next world.
     */
    public static void reset()
    {
        active = false;
        ocarinaMode = false;
        seed = 0;
        opponent = "";
        tick = 0;
        score = 0;
        notes = new ArrayList<>();
        consumed = new boolean[0];
        holding = new boolean[0];
        holdPaid = new boolean[0];
        java.util.Arrays.fill(wasDown, false);
        resultTicks = 0;
        outcome = 0;
        finalOwn = 0;
        finalOpponent = 0;
    }

    public static boolean isActive()
    {
        return active;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        // Leaving a server drops any clash still in progress. Without this the movement lock in DashInput, which keys off
        // isActive(), stays on until the self-timer passes DURATION_TICKS and zeroes WASD in whatever world is joined next.
        reset();
    }

    /**
     * One client tick of the minigame: read the keys, score any note they land on, and report at the end.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        clientTick();
    }

    private static void clientTick()
    {
        if (resultTicks > 0 && !active)
        {
            resultTicks--;
        }
        if (!active)
        {
            return;
        }
        readInput();
        tick++;
        if (tick > ClashRhythm.DURATION_TICKS)
        {
            // Our own timer ran out. Report and stop scoring, but leave the panel to the server's end packet so the
            // result shown is the one the server actually decided.
            active = false;
            if (ocarinaMode)
            {
                NetworkUtils.sendToServer(new net.shurui.shuruisutilities.ocarina.PacketOcarina(
                        net.shurui.shuruisutilities.ocarina.PacketOcarina.SCORE, ocarinaSong, score));
                ocarinaMode = false;
            }
            else
            {
                NetworkUtils.sendToServer(new PacketClashScore(score));
            }
        }
    }

    private static void readInput()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null)
        {
            // A screen is open over the fight; treat every key as released so nothing is scored behind it and no
            // press is swallowed on the way back.
            java.util.Arrays.fill(wasDown, false);
            return;
        }
        long window = mc.getWindow().getWindow();
        boolean[] down = new boolean[LANE_COUNT];
        // THE MOVEMENT KEYS *AND* THE ARROWS, either of them. The arrows are what an arrow on screen asks for and
        // what every other rhythm game trains people to reach for, while the movement keys are where the hands
        // already are - players were reaching for the wrong one and finding it dead. Accepting both costs nothing:
        // a lane is pressed if EITHER of its keys is down.
        down[ClashRhythm.DIR_UP] = held(window, mc.options.keyUp.getKey())
                || held(window, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_UP));
        down[ClashRhythm.DIR_LEFT] = held(window, mc.options.keyLeft.getKey())
                || held(window, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT));
        down[ClashRhythm.DIR_DOWN] = held(window, mc.options.keyDown.getKey())
                || held(window, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_DOWN));
        down[ClashRhythm.DIR_RIGHT] = held(window, mc.options.keyRight.getKey())
                || held(window, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT));

        for (int dir = 0; dir < LANE_COUNT; dir++)
        {
            // EDGE, not level: only the tick a key goes down counts, so holding a direction scores one note and not
            // every note in that lane. A sustain is the one case where the LEVEL matters too, and that is handled
            // separately below rather than by scoring the key every tick.
            if (down[dir] && !wasDown[dir])
            {
                press(dir);
            }
            wasDown[dir] = down[dir];
        }
        tickSustains(down);
    }

    /**
     * Pay out or drop the tails of any sustains in progress.
     *
     * <p>A tail pays when the note's release tick arrives with the key still down. Releasing early drops it, with a
     * few ticks of slack so a momentary blip does not cost a hold that was played correctly.
     */
    private static void tickSustains(boolean[] down)
    {
        for (int i = 0; i < notes.size(); i++)
        {
            if (!holding[i] || holdPaid[i])
            {
                continue;
            }
            ClashRhythm.Note note = notes.get(i);
            boolean stillDown = down[note.direction()];
            if (tick >= note.releaseTick())
            {
                if (stillDown)
                {
                    score += note.sustainBonus();
                }
                holdPaid[i] = true;
                holding[i] = false;
            }
            else if (!stillDown && tick > note.hitTick() + ClashRhythm.SUSTAIN_RELEASE_GRACE)
            {
                // Let go early: the press still counted, the tail does not.
                holdPaid[i] = true;
                holding[i] = false;
            }
        }
    }

    private static boolean held(long window, InputConstants.Key key)
    {
        try
        {
            return key.getType() == InputConstants.Type.KEYSYM && key.getValue() != InputConstants.UNKNOWN.getValue()
                    && InputConstants.isKeyDown(window, key.getValue());
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // Score a press against the nearest unconsumed note in that lane.
    private static void press(int direction)
    {
        int best = -1;
        int bestValue = 0;
        int bestOff = Integer.MAX_VALUE;
        for (int i = 0; i < notes.size(); i++)
        {
            if (consumed[i])
            {
                continue;
            }
            ClashRhythm.Note note = notes.get(i);
            int value = ClashRhythm.judge(note, direction, tick);
            if (value <= 0)
            {
                continue;
            }
            int off = Math.abs(tick - note.hitTick());
            if (off < bestOff)
            {
                best = i;
                bestValue = value;
                bestOff = off;
            }
        }
        if (best >= 0)
        {
            consumed[best] = true;
            score += bestValue;
            if (notes.get(best).isSustain())
            {
                holding[best] = true;
            }
            if (ocarinaMode)
            {
                note(direction, bestValue);
            }
        }
    }

    /**
     * The sound of the instrument, one note per lane.
     *
     * <p>Played on the CLIENT that pressed the key, at the moment of the press, because a note that had to go to the
     * server and come back would land a fifth of a second late and the tune would be unplayable. Everyone nearby
     * hears the performance as a whole from the server when it ends.
     *
     * <p>The four lanes are four pitches of the flute note block, which is the closest thing in the game to an
     * ocarina; a cleanly struck note rings a little higher than a scraped one, so a good performance sounds better
     * as well as scoring better.
     */
    private static void note(int direction, int quality)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
        {
            return;
        }
        // A pentatonic run over the four lanes, so any order of presses stays consonant: no chart can produce a
        // sour interval because there is no sour interval available.
        float[] pitches = { 0.7937F, 0.8909F, 1.0F, 1.1892F };
        float pitch = pitches[Math.max(0, Math.min(pitches.length - 1, direction))];
        mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_FLUTE.value(),
                0.8F, quality >= ClashRhythm.PERFECT_SCORE ? pitch : pitch * 0.94F);
    }

    public static final IGuiOverlay HUD = ClashRhythmOverlay::render;

    private static void render(ForgeGui gui, GuiGraphics g, float partial, int width, int height)
    {
        if (!active && resultTicks <= 0)
        {
            return;
        }
        try
        {
            int left = (width - PANEL_W) / 2;
            int top = height * PANEL_Y_NUMERATOR / PANEL_Y_DENOMINATOR;

            g.fill(left, top, left + PANEL_W, top + PANEL_H, COL_PANEL);
            g.fill(left, top, left + PANEL_W, top + 1, COL_BORDER);
            g.fill(left, top + PANEL_H - 1, left + PANEL_W, top + PANEL_H, COL_BORDER);
            g.fill(left, top, left + 1, top + PANEL_H, COL_BORDER);
            g.fill(left + PANEL_W - 1, top, left + PANEL_W, top + PANEL_H, COL_BORDER);

            if (!active)
            {
                drawResult(g, left, top);
                return;
            }

            // The hit line: notes travel right to left and should be pressed as they cross it.
            int lineX = left + HIT_LINE_INSET + NOTE_SIZE;
            g.fill(lineX, top + 4, lineX + 1, top + PANEL_H - 12, COL_HIT_LINE);

            int laneH = (PANEL_H - 16) / LANE_COUNT;
            int travel = PANEL_W - HIT_LINE_INSET - NOTE_SIZE * 2;
            // SMOOTH, not stepped. The chart advances once per client tick, so positioning off the tick counter alone
            // moves every arrow in 20 jumps a second while the screen redraws two or three times as often, which is
            // what read as jumpy. Adding the frame's partial tick makes travel continuous at whatever the frame rate
            // happens to be. Only the drawing interpolates: scoring still happens on whole ticks, so what the eye
            // follows and what the hit window judges stay the same thing.
            float now = tick + partial;
            for (int i = 0; i < notes.size(); i++)
            {
                ClashRhythm.Note note = notes.get(i);
                float ticksAway = note.hitTick() - now;
                // A sustain stays on screen until its tail has passed the line.
                float gone = note.isSustain() ? -(note.holdTicks() + ClashRhythm.GOOD_WINDOW) : -ClashRhythm.GOOD_WINDOW;
                if (ticksAway > ClashRhythm.LEAD_TICKS || ticksAway < gone)
                {
                    continue;
                }
                int y = top + 6 + note.direction() * laneH;
                int size = Math.min(NOTE_SIZE, laneH - 1);
                int headX = arrowX(left, travel, ticksAway);

                // The tail first, so the head draws over it.
                if (note.isSustain())
                {
                    int tailX = arrowX(left, travel, note.releaseTick() - now);
                    int x0 = Math.min(headX, tailX) + size / 2;
                    int x1 = Math.max(headX, tailX) + size / 2;
                    int colour = holdPaid[i] ? COL_NOTE_DEAD : holding[i] ? COL_NOTE_PERFECT : COL_NOTE;
                    g.fill(x0, y + size / 2 - 1, x1, y + size / 2 + 1, colour);
                }

                int colour = consumed[i] ? COL_NOTE_DEAD
                        : Math.abs(ticksAway) <= ClashRhythm.PERFECT_WINDOW ? COL_NOTE_PERFECT : COL_NOTE;
                arrow(g, headX, y, size, note.direction(), colour);
            }

            Minecraft mc = Minecraft.getInstance();
            String left1 = Component.translatable("gui.dmz_ragnarok.clash.score", score).getString();
            g.drawString(mc.font, left1, left + 6, top + PANEL_H - 10, COL_TEXT, false);
            if (!opponent.isEmpty())
            {
                int w = mc.font.width(opponent);
                g.drawString(mc.font, opponent, left + PANEL_W - 6 - w, top + PANEL_H - 10, COL_TEXT, false);
            }
        }
        catch (Throwable t)
        {
            // A HUD fault must never take the game's whole overlay stack down with it; drop this frame instead.
            active = false;
            resultTicks = 0;
        }
    }

    // Where an arrow sits on the panel, given how many ticks until it is due. Linear travel from the right edge to
    // the hit line, so the speed the player reads is exactly ClashRhythm.LEAD_TICKS.
    private static int arrowX(int left, int travel, float ticksAway)
    {
        float progress = 1.0f - ticksAway / ClashRhythm.LEAD_TICKS;
        return left + PANEL_W - NOTE_SIZE - 4 - Math.round(progress * travel);
    }

    /**
     * An actual arrow rather than a square: a triangular head with a short stem behind it, pointing the way the key
     * does. Composed from horizontal and vertical bars because {@code GuiGraphics} only fills rectangles, which is
     * plenty at this size and avoids pulling in a texture for four glyphs.
     */
    private static void arrow(GuiGraphics g, int x, int y, int size, int direction, int colour)
    {
        int half = size / 2;
        int stem = Math.max(2, size / 3);
        switch (direction)
        {
            case ClashRhythm.DIR_UP ->
            {
                for (int r = 0; r < half; r++)
                {
                    g.fill(x + half - r, y + r, x + half + r + 1, y + r + 1, colour);
                }
                g.fill(x + half - stem / 2, y + half, x + half + stem / 2 + 1, y + size, colour);
            }
            case ClashRhythm.DIR_DOWN ->
            {
                for (int r = 0; r < half; r++)
                {
                    g.fill(x + half - r, y + size - r - 1, x + half + r + 1, y + size - r, colour);
                }
                g.fill(x + half - stem / 2, y, x + half + stem / 2 + 1, y + half + 1, colour);
            }
            case ClashRhythm.DIR_LEFT ->
            {
                for (int c = 0; c < half; c++)
                {
                    g.fill(x + c, y + half - c, x + c + 1, y + half + c + 1, colour);
                }
                g.fill(x + half, y + half - stem / 2, x + size, y + half + stem / 2 + 1, colour);
            }
            default ->
            {
                for (int c = 0; c < half; c++)
                {
                    g.fill(x + size - c - 1, y + half - c, x + size - c, y + half + c + 1, colour);
                }
                g.fill(x, y + half - stem / 2, x + half + 1, y + half + stem / 2 + 1, colour);
            }
        }
    }

    private static void drawResult(GuiGraphics g, int left, int top)
    {
        Minecraft mc = Minecraft.getInstance();
        String key = switch (outcome)
        {
            case 1 -> "gui.dmz_ragnarok.clash.won";
            case 2 -> "gui.dmz_ragnarok.clash.drew";
            case 3 -> "gui.dmz_ragnarok.clash.cancelled";
            default -> "gui.dmz_ragnarok.clash.lost";
        };
        Component title = Component.translatable(key).withStyle(
                outcome == 1 ? ChatFormatting.GREEN : outcome == 2 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        int tw = mc.font.width(title);
        g.drawString(mc.font, title, left + (PANEL_W - tw) / 2, top + 14, 0xFFFFFFFF, false);

        String line = Component.translatable("gui.dmz_ragnarok.clash.result", finalOwn, finalOpponent).getString();
        int lw = mc.font.width(line);
        g.drawString(mc.font, line, left + (PANEL_W - lw) / 2, top + 30, COL_TEXT, false);
    }
}
