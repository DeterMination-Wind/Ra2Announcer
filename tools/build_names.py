#!/usr/bin/env python3
"""Build tools/mindustry_names{,_en}.json (unit/block display names) from the
vanilla bundles shipped with Mindustry-master: bundle_zh_CN.properties for the
Chinese pack, bundle.properties for the English pack.

Usage: python tools/build_names.py
"""

import json
import os
import re

TOOLS_DIR = os.path.dirname(__file__)
VANILLA_BUNDLES = os.path.abspath(os.path.join(
    TOOLS_DIR, "..", "..", "Mindustry-master", "core", "assets", "bundles"))

LANGS = {
    "zh": ("bundle_zh_CN.properties", "mindustry_names.json"),
    "en": ("bundle.properties", "mindustry_names_en.json"),
}

BLOCK_WHITELIST = {
    # cores
    "core-shard", "core-foundation", "core-nucleus", "core-zone",
    "core-bastion", "core-citadel", "core-acropolis",
    # unit production (v8 commandable buildings)
     "ground-factory", "air-factory", "naval-factory",
    "additive-reconstructor", "multiplicative-reconstructor",
    "exponential-reconstructor", "tetrative-reconstructor",
    "tank-fabricator", "mech-fabricator", "ship-fabricator",
    "tank-refabricator", "mech-refabricator", "ship-refabricator",
    "tank-assembler", "mech-assembler", "ship-assembler", "basic-assembler-module",
    "payload-source", "payload-loader", "payload-unloader",
    "constructor", "large-constructor", "deconstructor",
    "launch-pad", "repair-point", "repair-turret",
    # common turrets (frequent manual-control targets)
    "duo", "scatter", "hail", "wave", "salvo", "lancer", "arc", "parallax",
    "segment", "swarmer", "fuse", "ripple", "cyclone", "foreshadow",
    "spectre", "meltdown", "breach", "afflict", "titan",
    "diffuse", "sublimate", "disperse",  
    # drills and resource extraction
    "mechanical-drill", "pneumatic-drill", "laser-drill", "blast-drill",
    "water-extractor", "cultivator", "oil-extractor",
    "impact-drill", "eruption-drill", "plasma-bore", "large-plasma-bore",
    "cliff-crusher", "vent-condenser",
    # power
    "combustion-generator", "thermal-generator", "steam-generator",
    "differential-generator", "rtg-generator", "solar-panel", "solar-panel-large",
    "thorium-reactor", "impact-reactor", "battery", "battery-large",
    "power-node", "power-node-large", "beam-node", "beam-tower",
    "turbine-condenser", "chemical-combustion-chamber", "pyrolysis-generator",
    "flux-reactor", "neoplasia-reactor", "slag-heater",
    # storage and logistics
    "vault", "container", "unloader",
    "conveyor", "titanium-conveyor", "plastanium-conveyor", "armored-conveyor",
    "junction", "bridge-conveyor", "phase-conveyor",
    "sorter", "inverted-sorter", "router", "distributor",
    "overflow-gate", "underflow-gate", "mass-driver",
    "duct", "armored-duct", "duct-router", "duct-bridge",
    "payload-conveyor", "payload-router", "payload-mass-driver",
    "mechanical-pump", "rotary-pump", "impulse-pump",
    "conduit", "pulse-conduit", "plated-conduit", "bridge-conduit", "phase-conduit",
    "liquid-router", "liquid-container", "liquid-tank", "liquid-junction",
    # walls and support
    "copper-wall", "copper-wall-large", "titanium-wall", "titanium-wall-large",
    "thorium-wall", "thorium-wall-large", "phase-wall", "phase-wall-large",
    "surge-wall", "surge-wall-large", "plastanium-wall", "plastanium-wall-large",
    "door", "door-large",
    "beryllium-wall", "beryllium-wall-large", "tungsten-wall", "tungsten-wall-large",
    "blast-door", "reinforced-surge-wall", "reinforced-surge-wall-large",
    "carbide-wall", "carbide-wall-large",
    "mender", "mend-projector", "overdrive-projector", "overdrive-dome",
    "force-projector", "shield-projector", "large-shield-projector",
    "build-tower", "shock-mine", "radar",
    # remaining Serpulo industry
    "kiln", "graphite-press", "multi-press", "silicon-smelter", "silicon-crucible",
    "phase-weaver", "pulverizer", "cryofluid-mixer", "melter", "incinerator",
    "spore-press", "separator", "coal-centrifuge", "plastanium-compressor",
    "pyratite-mixer", "blast-mixer", "surge-smelter", "disassembler",
    "illuminator", "solar-panel-large", "advanced-launch-pad", "landing-pad",
    "large-payload-mass-driver", "small-deconstructor",
    # remaining Erekir industry and defense
    "silicon-arc-furnace", "electrolyzer", "atmospheric-concentrator",
    "oxidation-chamber", "electric-heater", "phase-heater", "heat-redirector",
    "small-heat-redirector", "heat-router", "slag-incinerator", "carbide-crucible",
    "slag-centrifuge", "surge-crucible", "cyanogen-synthesizer", "phase-synthesizer",
    "heat-reactor", "regen-projector", "shockwave-tower", "shielded-wall",
    "beam-link", "unit-repair-tower", "prime-refabricator", "basic-assembler-module",
    "large-cliff-crusher",
    # remaining Erekir logistics
    "overflow-duct", "underflow-duct", "duct-unloader", "surge-conveyor", "surge-router",
    "unit-cargo-loader", "unit-cargo-unload-point",
    "reinforced-pump", "reinforced-conduit", "reinforced-liquid-junction",
    "reinforced-bridge-conduit", "reinforced-liquid-router",
    "reinforced-liquid-container", "reinforced-liquid-tank",
    "reinforced-container", "reinforced-vault",
    "reinforced-payload-conveyor", "reinforced-payload-router",
    # extra turrets
    "tsunami", "surge-tower", "lustre", "scathe", "smite",
    # buildable scrap walls
    "scrap-wall", "scrap-wall-large", "scrap-wall-huge", "scrap-wall-gigantic",
}

LINE = re.compile(r"^(unit|block)\.([A-Za-z0-9._-]+)\.name\s*=\s*(.+)$")


def extract(bundle_path):
    names = {"unit": {}, "block": {}}
    with open(bundle_path, encoding="utf-8") as stream:
        for raw in stream:
            match = LINE.match(raw.strip())
            if not match:
                continue
            kind, key, value = match.groups()
            if kind == "unit":
                names["unit"][key] = value.strip()
            elif key in BLOCK_WHITELIST:
                names["block"][key] = value.strip()
    return names


def main():
    for lang, (bundle_name, out_name) in LANGS.items():
        names = extract(os.path.join(VANILLA_BUNDLES, bundle_name))
        out_path = os.path.join(TOOLS_DIR, out_name)
        with open(out_path, "w", encoding="utf-8") as stream:
            json.dump(names, stream, ensure_ascii=False, indent=1)
        missing = sorted(BLOCK_WHITELIST - set(names["block"]))
        print(f"[{lang}] units: {len(names['unit'])}, blocks: {len(names['block'])} -> {out_path}")
        if missing:
            print(f"[{lang}] missing blocks:", ", ".join(missing))


if __name__ == "__main__":
    main()


