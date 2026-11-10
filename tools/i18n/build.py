"""Builds shared/i18n/<lang>.json from tr_*.py and merges them into the Xcode string catalog.

    python3 tools/i18n/build.py

shared/i18n/en.json lists the source strings (keys = English text, as extracted from the
macOS app). Each tr_*.py holds translations in the same order. Placeholders (%@, %lld, %%)
must match the English string exactly.
"""
import importlib.util
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
I18N = os.path.join(ROOT, "shared", "i18n")
CATALOG = os.path.join(ROOT, "macos", "A747", "Resources", "Localizable.xcstrings")
PH = re.compile(r"%(?:lld|@|%|g|d)")


def load_translations():
    out = {}
    for fn in sorted(os.listdir(os.path.dirname(__file__))):
        if fn.startswith("tr_") and fn.endswith(".py") and fn != "tr_android.py":
            spec = importlib.util.spec_from_file_location(fn[:-3], os.path.join(os.path.dirname(__file__), fn))
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            out.update(mod.T)
    return out


def main():
    keys = list(json.load(open(os.path.join(I18N, "en.json"))))
    langs = load_translations()
    errors = 0
    for lang, items in langs.items():
        if len(items) != len(keys):
            print(f"{lang}: {len(items)} strings, expected {len(keys)}")
            errors += 1
            continue
        for k, v in zip(keys, items):
            if sorted(PH.findall(k)) != sorted(PH.findall(v)):
                print(f"{lang}: placeholders differ\n  {k}\n  {v}")
                errors += 1
        json.dump(dict(zip(keys, items)), open(os.path.join(I18N, f"{lang}.json"), "w"),
                  ensure_ascii=False, indent=1)
    if errors:
        sys.exit(f"{errors} problems")

    cat = json.load(open(CATALOG))
    for k in keys:
        entry = cat["strings"].setdefault(k, {})
        locs = entry.setdefault("localizations", {})
        for lang, items in langs.items():
            locs[lang] = {"stringUnit": {"state": "translated", "value": items[keys.index(k)]}}
    # strings that need no translation
    for k in cat["strings"]:
        if k not in keys:
            cat["strings"][k]["shouldTranslate"] = False
    json.dump(cat, open(CATALOG, "w"), ensure_ascii=False, indent=2, sort_keys=True)
    print(f"{len(langs)} languages × {len(keys)} strings")


if __name__ == "__main__":
    main()
