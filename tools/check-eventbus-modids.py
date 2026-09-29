#!/usr/bin/env python3
"""
Build guard for the core-plus-modules split.

Every @Mod.EventBusSubscriber must carry an EXPLICIT modid, because a wrong or missing modid fails
SILENTLY: the handler class is simply never registered (see Forge's AutomaticEventSubscriber, which
defaults a bare modid to whichever container is being injected). In a multi-mod jar (the fat jar) that
means a bare subscriber binds to whatever container is injected at that moment; with explicit modids
each subscriber binds once, to its owning mod, identically in the fat jar and in a standalone module jar.

This scans the source of all five trees and asserts:
  - every @Mod.EventBusSubscriber has an explicit modid, and
  - it is the RIGHT one for the tree the file lives in (core trees -> dmz_ragnarok; each module tree ->
    its own container id).

Exits non-zero (failing the Gradle build) on any violation, and prints the per-tree count.

Run: python3 tools/check-eventbus-modids.py [workspace_root]
"""
import os
import re
import sys

ANN = "@Mod.EventBusSubscriber"

# tree source root (relative to workspace root) -> the modid every subscriber in it must declare
TREE_MODID = {
    "sdu": "dmz_ragnarok",
    "shuruisutilities": "dmz_ragnarok",
    "dmz_ragnarok_space": "dmz_ragnarok_space",
    "shuruis_dmz_dungeons": "dmz_ragnarok_dungeons",
    "shuruis_raid_bosses": "dmz_ragnarok_raids",
    "shuruis_dmz_tournaments": "dmz_ragnarok_tournaments",
    "ragnarok_key": "dmz_ragnarok_key",
}

# modid = "literal"  OR  modid = SomeClass.CONSTANT
MODID_RE = re.compile(r"modid\s*=\s*(?:\"([^\"]*)\"|([\w.]+))")

# Known compile-time constants used as modid, resolved to their value. Core mains expose MODID =
# "dmz_ragnarok"; the module mains expose CONTAINER_ID = their own id (and a MODID = "dmz_ragnarok"
# NAMESPACE that must NOT be used as a subscriber modid, since it would bind to core's container).
CONST_VALUES = {
    "DmzNpc.MODID": "dmz_ragnarok",
    "net.shurui.dev.sdu.DmzNpc.MODID": "dmz_ragnarok",
    "ShuruisUtilities.MODID": "dmz_ragnarok",
    "net.shurui.shuruisutilities.core.ShuruisUtilities.MODID": "dmz_ragnarok",
    "Shuruis_dmz_dungeons.CONTAINER_ID": "dmz_ragnarok_dungeons",
    "Shuruis_raid_bosses.CONTAINER_ID": "dmz_ragnarok_raids",
    "Shuruis_dmz_tournaments.CONTAINER_ID": "dmz_ragnarok_tournaments",
    "DmzRagnarokSpace.CONTAINER_ID": "dmz_ragnarok_space",
    "net.shurui.shuruisutilities.space.DmzRagnarokSpace.CONTAINER_ID": "dmz_ragnarok_space",
    # The Ragnarok Key mod's own container id (it registers no content, but any subscriber it adds later must
    # bind to its own container, never to core's).
    "RagnarokKeyMod.MODID": "dmz_ragnarok_key",
    "net.shurui.ragnarokkey.RagnarokKeyMod.MODID": "dmz_ragnarok_key",
    # The parity devtools register their dump triggers under the core suite id.
    "ParityDump.SUITE_MODID": "dmz_ragnarok",
    "net.shurui.shuruisutilities.devtools.parity.ParityDump.SUITE_MODID": "dmz_ragnarok",
}


def in_comment(text, at):
    line_start = text.rfind("\n", 0, at) + 1
    prefix = text[line_start:at]
    return "//" in prefix or prefix.lstrip().startswith("*")


def in_string(text, at):
    """True if position `at` sits inside a double-quoted string literal on its own line. The scanner would
    otherwise mistake the annotation text embedded in a string (e.g. a dump label) for a bare annotation."""
    line_start = text.rfind("\n", 0, at) + 1
    prefix = text[line_start:at]
    count = 0
    i = 0
    while i < len(prefix):
        c = prefix[i]
        if c == "\\":
            i += 2
            continue
        if c == '"':
            count += 1
        i += 1
    return count % 2 == 1


def find_annotations(text):
    """Yield (line_no, args_or_None) for each real @Mod.EventBusSubscriber annotation."""
    i = 0
    n = len(text)
    while True:
        j = text.find(ANN, i)
        if j < 0:
            return
        if in_comment(text, j) or in_string(text, j):
            i = j + len(ANN)
            continue
        line_no = text.count("\n", 0, j) + 1
        k = j + len(ANN)
        m = k
        while m < n and text[m] in " \t\r\n":
            m += 1
        if m < n and text[m] == "(":
            depth = 0
            p = m
            while p < n:
                c = text[p]
                if c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                    if depth == 0:
                        break
                p += 1
            yield line_no, text[m + 1:p]
            i = p + 1
        else:
            yield line_no, None  # bare, no parens -> no modid
            i = k


# Presence audit: a module-presence or old-core-id check must NOT be hardcoded at a call site. Route it
# through net.shurui.dev.sdu.api.ModulePresence (or a dedicated core hook). "sdu"/"shuruisutilities" are the
# old pre-merge core ids and are no longer loaded containers, so a code check against them silently returns
# false. "dmz_ragnarok" itself (the core namespace/container) is allowed anywhere: it is always present.
PRESENCE_RE = re.compile(r"is(?:Mod)?Loaded\(\s*\"([^\"]+)\"\s*\)")
FLAGGED_IDS = {"sdu", "shuruisutilities",
               "dmz_ragnarok_dungeons", "dmz_ragnarok_raids", "dmz_ragnarok_tournaments",
               "dmz_ragnarok_space",
               # The key mod registers no content, so its presence must NEVER be probed with isLoaded: a fake jar
               # could carry the id. "Is private feature X installed" goes through sdu.api.KeyFeatures instead.
               "dmz_ragnarok_key"}
PRESENCE_HELPER = "ModulePresence.java"


def check_presence(root):
    viol = []
    trees = ["sdu", "shuruisutilities", "dmz_ragnarok_space",
             "shuruis_dmz_dungeons", "shuruis_raid_bosses", "shuruis_dmz_tournaments",
             "ragnarok_key"]
    for tree in trees:
        base = os.path.join(root, tree, "src", "main", "java")
        for dp, _, files in os.walk(base):
            for f in files:
                if not f.endswith(".java") or f == PRESENCE_HELPER:
                    continue
                path = os.path.join(dp, f)
                with open(path, encoding="utf-8") as fh:
                    text = fh.read()
                for mm in PRESENCE_RE.finditer(text):
                    if mm.group(1) not in FLAGGED_IDS:
                        continue
                    if in_comment(text, mm.start()):
                        continue
                    line_no = text.count("\n", 0, mm.start()) + 1
                    rel = os.path.relpath(path, root)
                    viol.append("%s:%d  hardcoded presence check \"%s\" (route through ModulePresence / a core hook)"
                                % (rel, line_no, mm.group(1)))
    return viol


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    violations = []
    counts = {}
    for tree, expected in TREE_MODID.items():
        base = os.path.join(root, tree, "src", "main", "java")
        c = 0
        for dp, _, files in os.walk(base):
            for f in files:
                if not f.endswith(".java"):
                    continue
                path = os.path.join(dp, f)
                with open(path, encoding="utf-8") as fh:
                    text = fh.read()
                if ANN not in text:
                    continue
                rel = os.path.relpath(path, root)
                for line_no, args in find_annotations(text):
                    c += 1
                    if args is None:
                        violations.append("%s:%d  MISSING modid (bare annotation)" % (rel, line_no))
                        continue
                    mm = MODID_RE.search(args)
                    if not mm:
                        violations.append("%s:%d  MISSING modid attribute" % (rel, line_no))
                        continue
                    if mm.group(1) is not None:
                        value = mm.group(1)  # string literal
                    else:
                        const = mm.group(2)
                        if const not in CONST_VALUES:
                            violations.append("%s:%d  UNRESOLVED modid constant %s (add it to CONST_VALUES)"
                                              % (rel, line_no, const))
                            continue
                        value = CONST_VALUES[const]
                    if value != expected:
                        violations.append("%s:%d  WRONG modid -> \"%s\" (expected \"%s\")"
                                          % (rel, line_no, value, expected))
        counts[tree] = c

    print("EventBusSubscriber modid audit:")
    for tree, expected in TREE_MODID.items():
        print("  %-24s %3d subscribers -> %s" % (tree, counts.get(tree, 0), expected))

    presence = check_presence(root)

    all_viol = violations + presence
    if all_viol:
        print("\nFAILED: %d violation(s):" % len(all_viol))
        for v in all_viol:
            print("  " + v)
        sys.exit(1)
    print("OK: every @Mod.EventBusSubscriber carries the correct explicit modid.")
    print("OK: no hardcoded sibling/module presence checks outside ModulePresence.")


if __name__ == "__main__":
    main()
