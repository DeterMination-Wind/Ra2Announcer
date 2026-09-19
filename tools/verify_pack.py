#!/usr/bin/env python3
"""Static checks for the RA2 Announcer asset packs.

* bundle.properties / bundle_zh_CN.properties must define exactly the same keys
* every ``ra2ann-*`` settings key used in src/ra2/*.java needs ``setting.<key>.name``
* every ``ra2ann.*`` message key used in src/ra2/*.java must exist in both bundles
* every fixed ``ann_*`` line used in src/ra2/*.java must ship in assets/sounds/
* --- the name packs must cover the same unit/block vocabulary in both languages

    python tools/verify_pack.py
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC_DIR = os.path.join(ROOT, "src", "ra2")
BUNDLE_DIR = os.path.join(ROOT, "assets", "bundles")
SOUND_DIR = os.path.join(ROOT, "assets", "sounds")
LANGS = ("zh", "en")

# keys that are intentionally only referenced from the settings UI helpers
IGNORED_SETTINGS = {
    "ra2ann-name-lang",
    # filter whitelists are storage-only: their buttons carry their own bundle labels
    "ra2ann-filter-attack-units",
    "ra2ann-filter-attacked-units",
    "ra2ann-filter-loss-units",
    "ra2ann-filter-loss-blocks",
}


def read_bundle(path: str) -> dict[str, str]:
    entries: dict[str, str] = {}
    with open(path, encoding="utf-8") as stream:
        for raw in stream:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            entries[key.strip()] = value.strip()
    return entries


def sources() -> str:
    chunks = []
    for name in sorted(os.listdir(SRC_DIR)):
        if name.endswith(".java"):
            with open(os.path.join(SRC_DIR, name), encoding="utf-8") as stream:
                chunks.append(stream.read())
    return "\n".join(chunks)


def main() -> int:
    problems: list[str] = []

    en = read_bundle(os.path.join(BUNDLE_DIR, "bundle.properties"))
    zh = read_bundle(os.path.join(BUNDLE_DIR, "bundle_zh_CN.properties"))

    for key in sorted(set(en) - set(zh)):
        problems.append(f"bundle_zh_CN.properties is missing: {key}")
    for key in sorted(set(zh) - set(en)):
        problems.append(f"bundle.properties is missing: {key}")

    code = sources()

    settings_keys = set(re.findall(r'"(ra2ann-[a-z0-9-]+)"', code)) - IGNORED_SETTINGS
    message_keys = set(re.findall(r'"(ra2ann\.[a-z0-9.]+)"', code))
    for key in sorted(settings_keys):
        if f"setting.{key}.name" not in en:
            problems.append(f"no setting entry for {key} (setting.{key}.name)")
    for key in sorted(message_keys):
        if key not in en:
            problems.append(f"message key not in bundle.properties: {key}")

    # every fixed-line clip that the code chains must exist; alias keys resolve to another clip at load time
    alias_source = ""
    announcer_path = os.path.join(SRC_DIR, "Announcer.java")
    if os.path.exists(announcer_path):
        with open(announcer_path, encoding="utf-8") as stream:
            alias_source = stream.read()
    alias_pairs = re.findall(r'\{"(ann_[a-z_]+)",\s*"(ann_[a-z_]+)"\}', alias_source)
    aliases = {alias for alias, _ in alias_pairs}
    alias_map = dict(alias_pairs)

    fixed_lines = set(re.findall(r'"(ann_[a-z_]+)"', code))
    fixed_lines |= {"ann_wave"}  # alias fallback target
    for key in sorted(fixed_lines):
        if key in aliases:
            continue
        if not os.path.exists(os.path.join(SOUND_DIR, f"{key}.ogg")):
            problems.append(f"missing fixed-line clip: assets/sounds/{key}.ogg")
    for alias in sorted(aliases):
        target, hops = alias_map[alias], 0
        while target in alias_map and hops < 8:
            target, hops = alias_map[target], hops + 1
        if not os.path.exists(os.path.join(SOUND_DIR, f"{target}.ogg")):
            problems.append(f"alias {alias} resolves to missing clip assets/sounds/{target}.ogg")

    # name packs: same vocabulary per language, and covering the shipped name list
    counts = {}
    for lang in LANGS:
        lang_dir = os.path.join(SOUND_DIR, lang)
        files = {name for name in os.listdir(lang_dir) if name.endswith(".ogg")} if os.path.isdir(lang_dir) else set()
        counts[lang] = files
        if not files:
            problems.append(f"name pack is empty: assets/sounds/{lang}")
    if counts.get("zh") and counts.get("en") and counts["zh"] != counts["en"]:
        only_zh = sorted(counts["zh"] - counts["en"])[:5]
        only_en = sorted(counts["en"] - counts["zh"])[:5]
        problems.append(f"name packs differ (zh-only: {only_zh}, en-only: {only_en})")

    # the vanilla vocabulary the settings dialogs offer should be speakable when possible
    for lang in LANGS:
        lang_dir = os.path.join(SOUND_DIR, lang)
        if not os.path.isdir(lang_dir):
            continue
        units = sorted(name[len("name-unit-"):-len(".ogg")] for name in counts[lang] if name.startswith("name-unit-"))
        blocks = sorted(name[len("name-block-"):-len(".ogg")] for name in counts[lang] if name.startswith("name-block-"))
        print(f"[{lang}] unit name clips: {len(units)}, block name clips: {len(blocks)}")

    fixed = sorted(name for name in os.listdir(SOUND_DIR) if name.startswith("ann_") and name.endswith(".ogg"))
    print(f"[ra2] fixed Sophia clips: {len(fixed)}")
    print(f"bundle keys: {len(en)} (en) / {len(zh)} (zh)")

    if problems:
        print("\nPROBLEMS:")
        for problem in problems:
            print(" -", problem)
        return 1
    print("\nall checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
