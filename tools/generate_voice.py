#!/usr/bin/env python3
"""Generate the unit/building **name** voice packs for RA2 Announcer.

The fixed announcement lines are original Red Alert 2 Sophia (Zofia) recordings and are
fetched by ``tools/fetch_ra2_voice.py``; this script only rebuilds the TTS callout names
that follow them ("Unit ready" + "Dagger").

    python tools/build_names.py                 # rebuild the name vocabulary from vanilla bundles
    python tools/generate_voice.py --lang zh    # Chinese pack  (Xiaoxiao)
    python tools/generate_voice.py --lang en    # English pack  (Aria)
    python tools/generate_voice.py --lang en name-unit-dagger   # single lines

Output: ``assets/sounds/<lang>/name-unit-*.ogg`` and ``name-block-*.ogg``.
Existing files are skipped unless named explicitly. Requires ``pip install edge-tts`` and
ffmpeg in PATH; Edge TTS needs a proxy on some networks: set HTTPS_PROXY (e.g.
http://127.0.0.1:7890).
"""

import argparse
import asyncio
import json
import os
import re
import shutil
import subprocess
import sys

import edge_tts

LANGS = {
    "zh": {
        "voice": "zh-CN-XiaoxiaoNeural",
        "names": "mindustry_names.json",
    },
    "en": {
        "voice": "en-US-AriaNeural",
        "names": "mindustry_names_en.json",
    },
}

RETRIES = 4

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
NAMES_DIR = os.path.dirname(__file__)


def safe_name(value: str) -> str:
    value = re.sub(r"[^a-zA-Z0-9._-]+", "-", value.strip().lower())
    return value.strip("-") or "unknown"


def load_name_lines(names_file: str):
    path = os.path.join(NAMES_DIR, names_file)
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as stream:
        data = json.load(stream)
    return {
        f"name-{kind}-{safe_name(name)}": text
        for kind, entries in data.items()
        for name, text in entries.items()
    }


async def synth(name: str, text: str, voice: str, proxy: str | None, tmp_mp3: str) -> None:
    communicate = edge_tts.Communicate(text, voice, proxy=proxy)
    await communicate.save(tmp_mp3)
    print(f"  {name}: '{text}' -> mp3")


def to_ogg(name: str, tmp_mp3: str, out_ogg: str) -> None:
    subprocess.run(
        [
            "ffmpeg", "-y", "-loglevel", "error",
            "-i", tmp_mp3,
            "-ac", "1", "-ar", "48000",
            # trim only true silence (-55dB tail keeps the natural decay of the last
            # syllable), loudnorm, then pad 150ms of tail so clips never end abruptly
            "-af",
            "silenceremove=start_periods=1:start_threshold=-50dB:start_duration=0.05,"
            "areverse,silenceremove=start_periods=1:start_threshold=-55dB:start_duration=0.05,areverse,"
            "loudnorm=I=-14:TP=-1.5:LRA=11,"
            "apad=pad_dur=0.15",
            "-c:a", "libvorbis", "-b:a", "64k",
            out_ogg,
        ],
        check=True,
    )
    print(f"  {name}: {os.path.getsize(out_ogg)} bytes ogg")


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lang", choices=sorted(LANGS), default="zh")
    parser.add_argument("--voice", default=None, help="edge-tts voice (default per language)")
    parser.add_argument("names", nargs="*", help="regenerate only these line names")
    args = parser.parse_args()

    conf = LANGS[args.lang]
    voice = args.voice or conf["voice"]
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY") or None

    out_dir = os.path.join(ROOT_DIR, "assets", "sounds", args.lang)
    os.makedirs(out_dir, exist_ok=True)
    tmp_mp3 = os.path.join(out_dir, "_tmp.mp3")
    print(f"lang: {args.lang}, voice: {voice}, proxy: {proxy or 'none'}")

    lines = load_name_lines(conf["names"])
    only = set(args.names)
    if only:
        unknown = only - lines.keys()
        if unknown:
            sys.exit(f"unknown line names: {', '.join(sorted(unknown))}")
        lines = {name: text for name, text in lines.items() if name in only}

    failed = []
    for name, text in lines.items():
        out_ogg = os.path.join(out_dir, f"{name}.ogg")
        if os.path.exists(out_ogg) and not only:
            print(f"  {name}: exists, skipped")
            continue
        ok = False
        for attempt in range(1, RETRIES + 1):
            try:
                await synth(name, text, voice, proxy, tmp_mp3)
                to_ogg(name, tmp_mp3, out_ogg)
                ok = True
                break
            except Exception as error:
                print(f"  {name}: attempt {attempt}/{RETRIES} failed: {error}")
                await asyncio.sleep(2 * attempt)
        if not ok:
            failed.append(name)
    if os.path.exists(tmp_mp3):
        os.remove(tmp_mp3)
    if failed:
        print(f"FAILED ({len(failed)}): {' '.join(failed)}")
        sys.exit(1)
    print("done.")


if __name__ == "__main__":
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg not found in PATH")
    asyncio.run(main())
