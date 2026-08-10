#!/usr/bin/env python3
"""Generate RA2-style English voice announcement ogg files for the Ra2Announcer mod.

Usage:
    python tools/generate_voice.py [voice]

Outputs to assets/sounds/ann_*.ogg (mono, Vorbis, loudness-normalized).
Requires: pip install edge-tts ; ffmpeg in PATH.
"""

import asyncio
import os
import shutil
import subprocess
import sys

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
    "ann_guardian": "Guardian detected.",
    "ann_guardian_kill": "Guardian destroyed.",
    "ann_research": "New technology acquired.",
    "ann_victory": "Mission accomplished.",
    "ann_defeat": "Mission failed.",
    "ann_sector": "Sector under attack.",
    "ann_sector_captured": "Sector captured.",
    "ann_base": "Base established.",
    "ann_reactor": "Critical containment failure.",
    "ann_low_power": "Low power.",
}

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "assets", "sounds")


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
    voice = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_VOICE
    os.makedirs(OUT_DIR, exist_ok=True)
    tmp_mp3 = os.path.join(OUT_DIR, "_tmp.mp3")
    print(f"voice: {voice}")
    for name, text in LINES.items():
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
