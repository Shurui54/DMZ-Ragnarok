#!/usr/bin/env python3
"""
Build guard for the "private UI is not rendered without the key" rule.

Client code must NEVER read the SERVER-side key/tier gates. On a physical client the key mod is not present,
so RagnarokKey / KeyGate.unlocked / KeyGate.present / PublicContent all evaluate the CLIENT's
(absent) key and answer wrong: a keyed server's private UI would vanish, and a keyless server's would flash in
until a sync corrected it. The one correct client answer is net.shurui.dev.sdu.api.ClientGate, fed by the login
key sync. Likewise ZSoulManager.enabled / WardrobeManager.active / ArgentCurrency.active fold a server-side key
read into a convenience method, so they are forbidden on the client too.

A file is treated as CLIENT-SCOPED when any of these holds:
  - its path is under a /client/ directory, or under /mixin/client/, or
  - its TOP-LEVEL class is annotated value = Dist.CLIENT or @OnlyIn(Dist.CLIENT), or
  - it is on the explicit list below (client mixins that live in a bare /mixin/ dir).

Exits non-zero (failing the Gradle build) on any client read of a forbidden gate.

Run: python3 tools/check-client-key-reads.py [workspace_root]
"""
import os
import re
import sys

# ragnarok_key is listed too: it must never hold a client-scoped file at all (check_key_no_client below is the
# stricter rule), but scanning it here as well means a client-scoped file that slips in is ALSO checked for gate reads.
TREES = ["sdu", "shuruisutilities", "dmz_ragnarok_space",
         "shuruis_dmz_dungeons", "shuruis_raid_bosses", "shuruis_dmz_tournaments",
         "ragnarok_key"]

# Client mixins that do not live under a /mixin/client/ directory but ARE client-only and must be checked.
EXPLICIT_CLIENT = {"ZSoulStatsScreenMixin.java"}

FORBIDDEN = [
    (re.compile(r"\bRagnarokKey\s*\."), "RagnarokKey.*"),
    (re.compile(r"\bKeyGate\s*\.\s*unlocked\b"), "KeyGate.unlocked"),
    (re.compile(r"\bKeyGate\s*\.\s*present\b"), "KeyGate.present"),
    (re.compile(r"\bPublicContent\s*\."), "PublicContent.*"),
    (re.compile(r"\bZSoulManager\s*\.\s*enabled\b"), "ZSoulManager.enabled"),
    (re.compile(r"\bWardrobeManager\s*\.\s*active\b"), "WardrobeManager.active"),
    (re.compile(r"\bArgentCurrency\s*\.\s*active\b"), "ArgentCurrency.active"),
    # KeyFeatures answers the SERVER's installed-feature set; the key mod is server-only, so on a client it is
    # always empty. Client UI must gate on ClientGate.feature(id)/features(), fed by the login KeyFeatureSyncPacket.
    (re.compile(r"\bKeyFeatures\s*\.\s*installed\b"), "KeyFeatures.installed"),
    (re.compile(r"\bKeyFeatures\s*\.\s*any\b"), "KeyFeatures.any"),
    (re.compile(r"\bKeyFeatures\s*\.\s*ids\b"), "KeyFeatures.ids"),
]

# First TOP-LEVEL type declaration (column 0, so nested/indented types are skipped).
TOP_LEVEL_TYPE = re.compile(
    r"^(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*(?:class|interface|enum|record)\s+\w",
    re.M)


def in_comment(text, at):
    line_start = text.rfind("\n", 0, at) + 1
    prefix = text[line_start:at]
    return "//" in prefix or prefix.lstrip().startswith("*")


def in_string(text, at):
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


def is_client_scoped(rel_path, filename, text):
    p = rel_path.replace(os.sep, "/")
    if "/client/" in p or "/mixin/client/" in p:
        return True
    if filename in EXPLICIT_CLIENT:
        return True
    m = TOP_LEVEL_TYPE.search(text)
    head = text[:m.start()] if m else text
    return "value = Dist.CLIENT" in head or "@OnlyIn(Dist.CLIENT)" in head


# The Ragnarok Key (ragnarok_key/) is a SERVER-only mod: it has NO client code at all. Any client import or a
# Dist.CLIENT reference there is a mistake (client behaviour belongs in core, gated by ClientGate). This is a
# stricter rule than the client-scope rule above, so the key tree gets its own scan.
KEY_TREE = "ragnarok_key"
KEY_CLIENT_IMPORT = re.compile(r"(?m)^\s*import\s+net\.minecraft\.client\.")
KEY_DIST_CLIENT = re.compile(r"\bDist\s*\.\s*CLIENT\b")


def check_key_no_client(root):
    """FAIL on any net.minecraft.client import or Dist.CLIENT inside the server-only key tree."""
    violations = []
    scanned = 0
    base = os.path.join(root, KEY_TREE, "src", "main", "java")
    for dp, _, files in os.walk(base):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(dp, f)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            scanned += 1
            rel = os.path.relpath(path, root)
            for mm in KEY_CLIENT_IMPORT.finditer(text):
                if in_comment(text, mm.start()):
                    continue
                line_no = text.count("\n", 0, mm.start()) + 1
                violations.append("%s:%d  client import in the server-only key tree "
                                  "(client code belongs in core, gated by ClientGate)" % (rel, line_no))
            for mm in KEY_DIST_CLIENT.finditer(text):
                if in_comment(text, mm.start()) or in_string(text, mm.start()):
                    continue
                line_no = text.count("\n", 0, mm.start()) + 1
                violations.append("%s:%d  Dist.CLIENT in the server-only key tree "
                                  "(the key jar has no client code)" % (rel, line_no))
    return scanned, violations


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    violations = []
    scanned = 0
    for tree in TREES:
        base = os.path.join(root, tree, "src", "main", "java")
        for dp, _, files in os.walk(base):
            for f in files:
                if not f.endswith(".java"):
                    continue
                path = os.path.join(dp, f)
                with open(path, encoding="utf-8") as fh:
                    text = fh.read()
                rel = os.path.relpath(path, root)
                if not is_client_scoped(rel, f, text):
                    continue
                scanned += 1
                for regex, label in FORBIDDEN:
                    for mm in regex.finditer(text):
                        if in_comment(text, mm.start()) or in_string(text, mm.start()):
                            continue
                        line_no = text.count("\n", 0, mm.start()) + 1
                        violations.append("%s:%d  client read of server-side gate %s (route through ClientGate)"
                                           % (rel, line_no, label))

    key_scanned, key_violations = check_key_no_client(root)
    violations += key_violations

    print("Client key-read audit: scanned %d client-scoped file(s)." % scanned)
    print("Key server-purity audit: scanned %d key-tree file(s)." % key_scanned)
    if violations:
        print("\nFAILED: %d violation(s):" % len(violations))
        for v in violations:
            print("  " + v)
        sys.exit(1)
    print("OK: no client-side reads of the server-side key/tier gates.")
    print("OK: the server-only key tree has no client imports or Dist.CLIENT.")


if __name__ == "__main__":
    main()
