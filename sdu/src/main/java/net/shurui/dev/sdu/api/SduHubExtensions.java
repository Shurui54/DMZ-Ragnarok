package net.shurui.dev.sdu.api;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// public extension point for adding a whole dropdown SECTION to the /rg npc edit hub (SduHub appends flat rows).
// a section renders as one hub row expanding into a dropdown of a sibling addon's editor screens.
// siblings register on the CLIENT (e.g. FMLClientSetupEvent), guarded by isModLoaded("sdu") so sdu stays a
// soft dep. each entry's open runnable runs client-side, usually sending that mod's own "open editor" C2S.
// nothing here touches server state. labels are ready Components, not translation keys, since siblings own
// their own lang (and several hardcode UI strings); sdu ships no lang for them.
public final class SduHubExtensions {

    // one row inside a Section dropdown. id is stable+unique within the section (de-dupes re-registration).
    // open is the client-side action when the row is chosen. tooltip null for none.
    public record Entry(String id, Component label, Component tooltip, Runnable open) {
    }

    // a named dropdown section: hub-row label + ordered entries. id stable+unique.
    public record Section(String id, Component label, List<Entry> entries) {
        public Section {
            entries = List.copyOf(entries);
        }
    }

    public static final class Builder {
        private final String id;
        private final Component label;
        private final List<Entry> entries = new ArrayList<>();

        private Builder(String id, Component label) {
            this.id = id;
            this.label = label;
        }

        public Builder entry(String id, Component label, Component tooltip, Runnable open) {
            if (id != null && label != null && open != null) {
                entries.add(new Entry(id, label, tooltip, open));
            }
            return this;
        }

        public Builder entry(String id, Component label, Runnable open) {
            return entry(id, label, null, open);
        }

        public void register() {
            registerSection(new Section(id, label, entries));
        }
    }

    private static final List<Section> SECTIONS = new ArrayList<>();

    private SduHubExtensions() {
    }

    public static Builder section(String id, Component label) {
        return new Builder(id, label);
    }

    // add or replace (by id) a section. call once during client setup.
    public static synchronized void registerSection(Section section) {
        if (section == null || section.id() == null || section.label() == null) {
            return;
        }
        SECTIONS.removeIf(s -> s.id().equals(section.id()));
        SECTIONS.add(section);
    }

    // registered sections, in registration order.
    public static synchronized List<Section> sections() {
        return new ArrayList<>(SECTIONS);
    }
}
