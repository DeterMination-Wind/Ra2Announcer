#!/usr/bin/env python3
"""Fetch the original Red Alert 2 advisor voice (Lieutenant Zofia / 索菲亚) used by this mod.

Source: HuggingFace dataset ``kingsznhone/Red-Alert-2-Full-Voice-Data`` ("RA2 Full Voice
Dataset.zip", 610 MB, PCM 16-bit 22050 Hz mono WAV + ``long_character_anno.txt`` transcripts).
The archive is one big blob, so this tool reads the ZIP central directory over HTTP range
requests and downloads only the ~30 clips the mod actually plays (a few MB instead of 610 MB).

    python tools/fetch_ra2_voice.py                  # all clips -> assets/sounds/*.ogg
    python tools/fetch_ra2_voice.py ann_wave ann_base # regenerate single lines
    python tools/fetch_ra2_voice.py --list           # show the mapping without downloading
    python tools/fetch_ra2_voice.py --keep-wav       # also leave the raw wavs in tools/ra2_wav

Requires ffmpeg in PATH. Voice lines stay under the dataset's non-commercial fan-use terms:
they are original Westwood/EA recordings and must not be sold or used commercially.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zlib

DATASET = "https://huggingface.co/datasets/kingsznhone/Red-Alert-2-Full-Voice-Data"
ZIP_URL = DATASET + "/resolve/main/RA2%20Full%20Voice%20Dataset.zip"
ZIP_SIZE = 609_937_601
ANNO_NAME = "RA2 Full Voice Dataset/long_character_anno.txt"
CLIP_PREFIX = "RA2 Full Voice Dataset/segmented_character_voice/zofia/"

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT_DIR = os.path.join(ROOT_DIR, "assets", "sounds")
WAV_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ra2_wav")
MAP_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ra2_voice_lines.json")

# logical sound key -> (source clip, transcript from the dataset annotation file)
CLIPS = {
    "ann_base":             ("zofia_144.wav", "Establishing Battlefield Control. Standby."),
    "ann_wave":             ("zofia_478.wav", "Warning! Enemy forces in your area!"),
    "ann_wave_warn":        ("zofia_259.wav", "Warning! Enemy armor battalion detected!"),
    "ann_wave_cleared":     ("zofia_61.wav",  "Objective complete."),
    "ann_core_attack":      ("zofia_230.wav", "Our base is under attack"),
    "ann_core_critical":    ("zofia_22.wav",  "Base defenses offline"),
    "ann_core_threat":      ("zofia_44.wav",  "It now threatens our base."),
    "ann_reactor":          ("zofia_29.wav",  "Sir, we are extremely vulnerable here."),
    "ann_low_power":        ("zofia_410.wav", "low power"),
    "ann_unit_lost":        ("zofia_19.wav",  "Unit lost."),
    "ann_structure_lost":   ("zofia_229.wav", "Critical structure lost"),
    "ann_unit_attack":      ("zofia_204.wav", "Be warned, comrade general!"),
    "ann_miner_attack":     ("zofia_293.wav", "our miner under attack."),
    "ann_unit_ready":       ("zofia_268.wav", "Unit ready"),
    "ann_training":         ("zofia_540.wav", "training"),
    "ann_cancel":           ("zofia_20.wav",  "Cancelled."),
    "ann_research":         ("zofia_18.wav",  "New technology acquired."),
    "ann_victory":          ("zofia_282.wav", "Mission accomplished."),
    "ann_defeat":           ("zofia_485.wav", "Mission failed."),
    "ann_enemy_base":       ("zofia_21.wav",  "Enemy base powered down"),
    "ann_boss_kill":        ("zofia_597.wav", "Well done, comrade general."),
    "ann_sector_captured":  ("zofia_315.wav", "Good, we have assumed command of this base."),
    "ann_high_value_block": ("zofia_202.wav", "Beacon detected."),
    "ann_watch_destroyed":  ("zofia_16.wav",  "Critical unit lost."),
    "ann_control_friendly": ("zofia_207.wav", "Reinforcements have arrived."),
    "ann_control_building": ("zofia_231.wav", "Structure garrisoned"),
    "ann_force_infantry":   ("zofia_23.wav",  "Warning! enemy infantry battalion detected"),
    "ann_force_air":        ("zofia_417.wav", "Warning! Enemy air armada detected!"),
    "ann_force_naval":      ("zofia_370.wav", "warning enemy fleet detected"),
}


def fetch_range(start: int, end: int, attempts: int = 4) -> bytes:
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(
                ZIP_URL,
                headers={"Range": f"bytes={start}-{end}", "User-Agent": "Mozilla/5.0"},
            )
            with urllib.request.urlopen(request, timeout=180) as response:
                return response.read()
        except Exception as error:  # noqa: BLE001 - retried below
            last_error = error
            print(f"  range {start}-{end}: attempt {attempt}/{attempts} failed: {error}", file=sys.stderr)
    raise RuntimeError(f"range request failed: {last_error}")


def central_directory() -> dict[str, dict]:
    tail = fetch_range(ZIP_SIZE - (1 << 20), ZIP_SIZE - 1)
    index = tail.rfind(b"PK\x05\x06")
    if index < 0:
        raise RuntimeError("ZIP end-of-central-directory record not found")
    _, _, _, _, total, cd_size, cd_offset, _ = struct.unpack_from("<IHHHHIIH", tail, index)
    if total == 0xFFFF or cd_size == 0xFFFFFFFF:
        raise RuntimeError("zip64 archives are not supported by this helper")
    start_in_tail = cd_offset - (ZIP_SIZE - len(tail))
    if start_in_tail >= 0:
        cd = tail[start_in_tail:start_in_tail + cd_size]
    else:
        cd = fetch_range(cd_offset, cd_offset + cd_size - 1)

    entries: dict[str, dict] = {}
    position = 0
    while position + 46 <= len(cd) and cd[position:position + 4] == b"PK\x01\x02":
        (_, _, _, _, method, _, _, _, csize, usize, nlen, elen, clen, _,
         _, _, local_offset) = struct.unpack_from("<IHHHHHHIIIHHHHHII", cd, position)
        name = cd[position + 46:position + 46 + nlen].decode("utf-8", "replace")
        entries[name] = {"method": method, "csize": csize, "usize": usize, "offset": local_offset}
        position += 46 + nlen + elen + clen
    return entries


def read_entry(entry: dict) -> bytes:
    header = fetch_range(entry["offset"], entry["offset"] + 29)
    if header[:4] != b"PK\x03\x04":
        raise RuntimeError("corrupt local file header")
    _, _, _, method, _, _, _, _, _, nlen, elen = struct.unpack_from("<IHHHHHIIIHH", header, 0)
    start = entry["offset"] + 30 + nlen + elen
    payload = fetch_range(start, start + entry["csize"] - 1)
    if method == 0:
        return payload
    if method == 8:
        return zlib.decompress(payload, -15)
    raise RuntimeError(f"unsupported compression method {method}")


def to_ogg(name: str, wav: bytes, out_ogg: str) -> float:
    """Trim silence, normalise loudness and encode mono 48 kHz Vorbis (same recipe as the name packs)."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as handle:
        handle.write(wav)
        tmp_wav = handle.name
    try:
        subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error",
                "-i", tmp_wav,
                "-ac", "1", "-ar", "48000",
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
    finally:
        os.remove(tmp_wav)

    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", out_ogg],
        check=True, capture_output=True, text=True,
    )
    return float(probe.stdout.strip() or 0.0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("keys", nargs="*", help="only regenerate these logical line names")
    parser.add_argument("--list", action="store_true", help="print the mapping and exit")
    parser.add_argument("--keep-wav", action="store_true", help="keep the downloaded source wavs")
    args = parser.parse_args()

    if args.list:
        for key, (clip, text) in CLIPS.items():
            print(f"{key:22s} {clip:16s} {text}")
        return

    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg not found in PATH")

    wanted = args.keys or list(CLIPS)
    unknown = [key for key in wanted if key not in CLIPS]
    if unknown:
        sys.exit(f"unknown line names: {', '.join(sorted(unknown))}")

    os.makedirs(OUT_DIR, exist_ok=True)
    if args.keep_wav:
        os.makedirs(WAV_DIR, exist_ok=True)

    print(f"reading ZIP central directory of {ZIP_URL.rsplit('/', 1)[-1]} ...")
    entries = central_directory()
    print(f"  {len(entries)} entries")

    durations = {}
    for key in wanted:
        clip, text = CLIPS[key]
        entry = entries.get(CLIP_PREFIX + clip)
        if entry is None:
            sys.exit(f"clip {clip} not found in the dataset archive")
        wav = read_entry(entry)
        if args.keep_wav:
            with open(os.path.join(WAV_DIR, clip), "wb") as handle:
                handle.write(wav)
        out_ogg = os.path.join(OUT_DIR, f"{key}.ogg")
        durations[key] = to_ogg(key, wav, out_ogg)
        print(f"  {key:22s} <- {clip:16s} {durations[key]:5.2f}s  \"{text}\"")

    with open(MAP_PATH, "w", encoding="utf-8") as handle:
        json.dump(
            {key: {"clip": clip, "text": text, "seconds": round(durations[key], 2)}
             for key, (clip, text) in CLIPS.items() if key in durations},
            handle, ensure_ascii=False, indent=2,
        )
    print(f"wrote {len(durations)} clips into {os.path.relpath(OUT_DIR, ROOT_DIR)} and {os.path.relpath(MAP_PATH, ROOT_DIR)}")


if __name__ == "__main__":
    main()
