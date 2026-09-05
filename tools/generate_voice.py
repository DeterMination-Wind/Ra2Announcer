#!/usr/bin/env python3
"""Generate RA2-style English voice announcement ogg files for the Ra2Announcer mod.

Usage:
    python tools/generate_voice.py [voice] [line names...]

Outputs to assets/sounds/ann_*.ogg (mono, Vorbis, loudness-normalized).
Requires: pip install edge-tts ; ffmpeg in PATH.
Pass one or more line names (e.g. ann_enemy_force) to regenerate only those;
without names every line is regenerated.
"""

import asyncio
import os
import shutil
import subprocess
import sys
import json
import re

import edge_tts

DEFAULT_VOICE = "en-US-AriaNeural"

LINES = {
    "ann_wave": "Incoming wave.",
    "ann_wave_warn": "Warning. Enemy forces approaching.",
    "ann_wave_cleared": "Wave cleared.",
    "ann_core_attack": "Our base is under attack!",
    "ann_core_critical": "Base defenses critical.",
    "ann_unit_lost": "Unit lost.",
    "ann_structure_lost": "Structure destroyed.",
    "ann_enemy_base": "Enemy base destroyed.",
    "ann_boss": "Boss detected.",
    "ann_boss_kill": "Boss destroyed.",
    "ann_training": "Training.",
    "ann_unit_ready": "Unit ready.",
    "ann_cancel": "Cancel.",
    "ann_miner_attack": "Ore miner is under attack!",
    "ann_high_value_warning": "Warning! Enemy",
    "ann_detected": "detected!",
    "ann_enemy_force": "Warning! Enemy strike force approaching.",
    "ann_watch_warning": "Warning! Priority enemy target detected.",
    "ann_watch_destroyed": "Priority target destroyed.",
    "ann_research": "New technology acquired.",
    "ann_victory": "Mission accomplished.",
    "ann_defeat": "Mission failed.",
    "ann_sector": "Sector under attack.",
    "ann_sector_captured": "Sector captured.",
    "ann_base": "Base established.",
    "ann_reactor": "Critical containment failure.",
    "ann_low_power": "Low power.",
}

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT_DIR = os.path.join(ROOT_DIR, "assets", "sounds")
NAMES_FILE = os.path.join(os.path.dirname(__file__), "mindustry_names.json")


def safe_name(value: str) -> str:
    value = re.sub(r"[^a-zA-Z0-9._-]+", "-", value.strip().lower())
    return value.strip("-") or "unknown"


def load_name_lines():
    if not os.path.exists(NAMES_FILE):
        return {}
    with open(NAMES_FILE, encoding="utf-8") as stream:
        data = json.load(stream)
    return {
        f"name-{kind}-{safe_name(name)}": text
        for kind, entries in data.items()
        for name, text in entries.items()
    }


async def synth(name: str, text: str, voice: str, tmp_mp3: str) -> None:
    communicate = edge_tts.Communicate(text, voice)
    await communicate.save(tmp_mp3)
    print(f"  {name}: '{text}' -> mp3")


def to_ogg(name: str, tmp_mp3: str, out_ogg: str) -> None:
    subprocess.run(
        [
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", tmp_mp3,
            "-ac", "1", "-ar", "48000",
            "-af", "loudnorm=I=-14:TP=-1.5:LRA=11",
            "-c:a", "libvorbis", "-b:a", "64k",
            out_ogg,
        ],
        check=True,
    )
    print(f"  {name}: {os.path.getsize(out_ogg)} bytes ogg")


async def main() -> None:
    args = sys.argv[1:]
    voice = args[0] if args else DEFAULT_VOICE
    only = set(args[1:])
    os.makedirs(OUT_DIR, exist_ok=True)
    tmp_mp3 = os.path.join(OUT_DIR, "_tmp.mp3")
    print(f"voice: {voice}")
    lines = dict(LINES)
    lines.update(load_name_lines())
    if only:
        unknown = only - lines.keys()
        if unknown:
            sys.exit(f"unknown line names: {', '.join(sorted(unknown))}")
        lines = {name: text for name, text in lines.items() if name in only}
    for name, text in lines.items():
        out_ogg = os.path.join(OUT_DIR, f"{name}.ogg")
        await synth(name, text, voice, tmp_mp3)
        to_ogg(name, tmp_mp3, out_ogg)
    if os.path.exists(tmp_mp3):
        os.remove(tmp_mp3)
    print("done.")


if __name__ == "__main__":
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg not found in PATH")
    asyncio.run(main())
