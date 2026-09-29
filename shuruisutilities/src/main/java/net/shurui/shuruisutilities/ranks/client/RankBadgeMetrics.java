package net.shurui.shuruisutilities.ranks.client;

/**
 * The rank badge's geometry, in ONE place, because it is shared by four things that must agree and has already
 * nearly drifted apart twice.
 *
 * <p>The four are: the generator that writes {@code ranks.json} / {@code ranks_small.json}
 * ({@code tools/rank-badges-rebuild.py}), and the chat, tab-list and nameplate mixins that each draw a bar
 * around the badge. Every one of them previously carried its own copy of the height and its own hard-coded
 * assumption about the ascent, so changing the badge size meant editing four files and silently breaking any
 * you missed.</p>
 *
 * <h2>How a bitmap glyph sits</h2>
 * A ranks-font glyph's TOP lands at {@code drawY + (7 - ascent)}. That is the whole relationship, and
 * {@link #GLYPH_TOP_OFFSET} is it. Everything else here follows from it:
 *
 * <ul>
 *   <li>{@link #HEIGHT} is the badge's drawn height and the free number: raise it to make badges bigger.</li>
 *   <li>{@link #ASCENT} decides where the glyph sits relative to the text baseline. It is chosen so the BADGE
 *       lands with exactly one pixel of bar above and below while the ordinary TEXT sits as near the middle of
 *       that bar as an odd remainder allows. It MUST match the ascent the generator writes.</li>
 *   <li>{@link #BAR_HEIGHT} is badge + 2, which is what makes the 1px clearance true.</li>
 * </ul>
 *
 * <h2>Why 12 and 9, and why the text stays centred</h2>
 * HEIGHT must be EVEN and ASCENT is then fixed at {@code HEIGHT / 2 + 3}. Ordinary text is 8 tall, the bar is
 * {@code HEIGHT + 2}, and the text gap is {@code (HEIGHT + 2) - 8 = HEIGHT - 6}, which only splits evenly above
 * and below when HEIGHT is even. The ascent then follows from wanting the badge and the text centred at once:
 *
 * <ul>
 *   <li>text centred wants {@code drawY = barCentre - 4};</li>
 *   <li>badge centred wants {@code drawY = barCentre - HEIGHT/2 - GLYPH_TOP_OFFSET = barCentre - HEIGHT + ASCENT - 7};</li>
 *   <li>equate them and {@code ASCENT = HEIGHT / 2 + 3}.</li>
 * </ul>
 *
 * <p>It was 16 / 11 (bar 18, text 5 and 5), which the server owner found too big: a 16px badge is twice the 8px
 * chat line. It is now 12 / 9 (bar 14, text 3 and 3), a badge at 1.5x the text that reads clearly without
 * dominating the line. With those, the badge is 12 in a 14 bar (exactly 1 above and 1 below) and the text is 8 in
 * a 14 bar (exactly 3 and 3). Changing either number alone breaks one of the two, so keep them to the rule
 * above and equal to the generator's {@code FONT_VARIANTS}.
 *
 * <p>Client-only.</p>
 */
public final class RankBadgeMetrics
{
    private RankBadgeMetrics() {}

    /** Drawn badge height. Keep equal to the generator's {@code height}. Must be EVEN. */
    public static final int HEIGHT = 12;

    /** Font ascent. Keep equal to the generator's {@code ascent}, which is {@code HEIGHT / 2 + 3}. */
    public static final int ASCENT = 9;

    /**
     * EVERY badge is drawn at {@link #HEIGHT}, owner included, and there is no per-rank exception any more.
     *
     * <p>There used to be one. The generator cropped the whole set to a single union content box, which kept each
     * badge at the size the artist drew it, and the artist did not draw them at one size: the ink ran from 30 rows
     * to 43, so a Newbie badge drew under 10px tall beside an Owner badge that filled the bar. Owner carried a
     * height override purely to fill that band. The generator now crops each badge to its OWN ink, so all of them
     * fill it by construction and one height covers the set.</p>
     *
     * <p>Consequence worth knowing: badge WIDTH now varies, because a bitmap provider keeps the aspect and a wide
     * short wordmark scaled to the shared height comes out wider than a tall one. Nothing here needs to know the
     * width, but a bar drawn around a badge does, and it must measure rather than assume.</p>
     */

    /** Bar height: one pixel of clearance above and below the badge. */
    public static final int BAR_HEIGHT = HEIGHT + 2;

    /** Clearance on each side. */
    public static final int PAD = (BAR_HEIGHT - HEIGHT) / 2;

    /**
     * How far a badge's top sits from the y a string is drawn at. Negative means above.
     *
     * <p>At the old ascent of 8 this was -1, which is the {@code textY - 1} that used to be written out by hand
     * in each mixin. Derive it rather than repeating the literal.</p>
     */
    public static final int GLYPH_TOP_OFFSET = 7 - ASCENT;

    /**
     * The y to draw a line at so its BADGE is centred in a bar whose centre is {@code barCentre}.
     *
     * <p>Solves {@code drawY + GLYPH_TOP_OFFSET + HEIGHT/2 == barCentre} for {@code drawY}.</p>
     */
    public static int drawYForBarCentre(float barCentre)
    {
        return Math.round(barCentre - GLYPH_TOP_OFFSET - HEIGHT / 2.0F);
    }
}
