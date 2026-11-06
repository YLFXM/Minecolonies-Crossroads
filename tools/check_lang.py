"""Report lang-key coverage and hardcoded UI text in this mod.

Two checks, both used by the release checklist:
  * every key referenced from Java/XML exists in both lang files;
  * no GUI xml carries a hardcoded (non-``$(key)``) label.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LANG = ROOT / "src/main/resources/assets/rp_crossroads/lang"
GUI = ROOT / "src/main/resources/assets/rp_crossroads/gui"


def load(name):
    return json.loads((LANG / name).read_text(encoding="utf-8"))


def keys_used():
    used = set()
    for path in (ROOT / "src/main/java").rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        used |= set(re.findall(r'"(rp_crossroads\.[A-Za-z0-9_.]+)"', text))
    for path in GUI.glob("*.xml"):
        used |= set(re.findall(r"\$\(([^)]+)\)", path.read_text(encoding="utf-8")))
    return used


def hardcoded_labels():
    found = []
    for path in GUI.glob("*.xml"):
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r'label="([^"]*)"', text):
            value = match.group(1)
            # ``$(key)``, pure symbols and placeholder numbers carry no language.
            if value.startswith("$(") or re.fullmatch(r"[-+\[\]x \d]*", value):
                continue
            found.append((path.name, value))
    return found


def is_dynamic(key):
    """Keys built by concatenation in Java end in '.' or '_' and are not literal."""
    return key.endswith(".") or key.endswith("_") or not key.rsplit(".", 1)[-1]


def main():
    zh, en = load("zh_cn.json"), load("en_us.json")
    used = keys_used()
    fail = False

    for bad in (sorted(set(zh) - set(en)), sorted(set(en) - set(zh))):
        if bad:
            fail = True
            print("language mismatch:", bad)
    for key in zh:
        # A different number of %s renders the wrong sentence or throws while the
        # client builds the component, so the two files must agree exactly.
        if zh[key].count("%s") != en[key].count("%s"):
            fail = True
            print(f"placeholder count differs: {key}")
    missing = sorted(k for k in used
                     if k.startswith("rp_crossroads.") and not is_dynamic(k) and k not in zh)
    if missing:
        fail = True
        print("referenced but missing in zh_cn:", missing)
    foreign = sorted(k for k in used if not k.startswith("rp_crossroads."))
    if foreign:
        fail = True
        print("gui labels using another mod's key:", foreign)
    for name, value in hardcoded_labels():
        fail = True
        print(f"hardcoded label in {name}: {value!r}")
    print(f"{len(zh)} zh keys / {len(en)} en keys / {len(used)} referenced")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
