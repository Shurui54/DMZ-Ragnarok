package net.shurui.dev.sdu.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Client-side source of DMZ quest-NPC ids for the side-quest editor's quest-giver / turn-in dropdowns. DMZ
// has no runtime registry of quest NPCs (a QuestNPCEntity just carries a free-form npcId string), so we
// offer DMZ's canonical story NPCs plus any ids already referenced by loaded quests, so custom ids the user
// typed elsewhere still show. Dropdowns stay searchable/editable.
public final class DmzQuestNpcs {

    // DMZ's canonical story NPC ids (from SideQuestDefaults.GOOD_PATH_NPCS)
    public static final List<String> CANONICAL = List.of(
            "goku", "roshi", "karin", "guru", "dende", "popo", "kingkai", "bulma",
            "krillin", "yamcha", "tien", "chiaotzu", "gohan", "trunks", "chi_chi",
            "videl", "namek_elder");

    private DmzQuestNpcs() {
    }

    // canonical ids first, then any non-blank extra ids not already present (order preserved)
    public static List<String> npcIds(Iterable<String> extra) {
        Set<String> ids = new LinkedHashSet<>(CANONICAL);
        if (extra != null) {
            for (String s : extra) {
                if (s != null && !s.isBlank()) {
                    ids.add(s.trim());
                }
            }
        }
        return new ArrayList<>(ids);
    }
}
