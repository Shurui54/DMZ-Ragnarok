package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for seeding the ritual forms into DMZ's own saiyan and namekian races. Holds no DMZ imports: it only
 * checks that DMZ is present before touching {@link RitualRaceMergeImpl}, which references {@code ConfigManager}. Follows
 * the optional-dependency pattern.
 *
 * <p>Unlike {@link RaceBundleExtractor} (which writes whole SU-owned race folders), this performs a SEED-IF-ABSENT merge
 * into DMZ's stock race folders: it adds the SSJ5 and Super Saiyan God form group files to saiyan and Primal Namekian to
 * namekian, and patches the shared {@code superforms}/{@code godforms} price arrays so the new rungs are grant-only
 * (SSJ5, Primal Namekian) or purchasable-but-gated (SSG). It never rewrites a form file that already exists and never
 * touches an existing price it did not add, so admin edits survive. Runs at server start, after DMZ has written its own
 * default race files; reloads DMZ once if anything changed.
 */
public final class RitualRaceMerge
{
    private RitualRaceMerge() {}

    public static void seedOnServerStart()
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return;
        RitualRaceMergeImpl.seedAndReload();
    }
}
