#!/usr/bin/env python3
"""One-off: build tools/mindustry_names_zh.json from Mindustry's own zh_CN bundle.

Reads the key set from tools/mindustry_names.json and looks up
`unit.<name>.name` / `block.<name>.name` in the Mindustry sources' Chinese bundle.
Entries without a Chinese localization are skipped (the mod falls back to English audio).
"""

import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
EN_NAMES = os.path.join(HERE, "mindustry_names.json")
ZH_BUNDLE = os.path.join(HERE, "..", "..", "Mindustry-master", "core", "assets", "bundles", "bundle_zh_CN.properties")
OUT = os.path.join(HERE, "mindustry_names_zh.json")

#blocks removed from current Mindustry bundles; the v155.4 mod still ships them
FALLBACKS = {
    "block.unit-factory.name": "单位工厂",
    "block.reconstructor.name": "单位重构工厂",
}


def clean(value: str) -> str:
    return re.sub(r"\[[^\]]*\]", "", value).strip()


def main():
    with open(EN_NAMES, encoding="utf-8") as stream:
        en = json.load(stream)

    zh_props = {}
    with open(ZH_BUNDLE, encoding="utf-8") as stream:
        for line in stream:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            zh_props[key.strip()] = value.strip()
    zh_props.update(FALLBACKS)

    out = {}
    for kind, entries in en.items():
        zh_entries = {}
        for name in entries:
            key = f"{kind}.{name}.name"
            if key in zh_props:
                zh_entries[name] = clean(zh_props[key])
            else:
                print(f"missing: {key}")
        out[kind] = zh_entries

    with open(OUT, "w", encoding="utf-8") as stream:
        json.dump(out, stream, ensure_ascii=False, indent=2, sort_keys=True)
    total = sum(len(v) for v in out.values())
    print(f"wrote {OUT}: {total} entries ({', '.join(f'{k}={len(v)}' for k, v in out.items())})")


if __name__ == "__main__":
    main()
