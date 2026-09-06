#!/usr/bin/env python3
"""Generate RA2-style English voice announcement ogg files for the Ra2Announcer mod.

Usage:
    python tools/generate_voice.py [voice] [line names... | zh]

Outputs to assets/sounds/ann_*.ogg (mono, Vorbis, loudness-normalized).
Requires: pip install edge-tts ; ffmpeg in PATH.
Pass one or more line names (e.g. ann_enemy_force) to regenerate only those;
without names every line is regenerated. The special filter "zh" regenerates
only the Chinese-language lines (ann_zh_* and name-*-zh-*), intended for a
Chinese edge-tts voice such as zh-CN-YunjianNeural.
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

#Chinese-language variants, played when the in-game voice language is set to Chinese.
#Kept as separate ann_zh_* files so the English pack stays untouched.
LINES_ZH = {
    "ann_zh_wave": "敌方波次来袭。",
    "ann_zh_wave_warn": "警告，敌方部队正在接近。",
    "ann_zh_wave_cleared": "波次已清除。",
    "ann_zh_core_attack": "我们的基地正在遭受攻击！",
    "ann_zh_core_critical": "基地防御处于危急状态。",
    "ann_zh_unit_lost": "单位损失。",
    "ann_zh_structure_lost": "建筑被摧毁。",
    "ann_zh_enemy_base": "敌方基地已摧毁。",
    "ann_zh_boss": "检测到首领单位。",
    "ann_zh_boss_kill": "首领已被摧毁。",
    "ann_zh_training": "训练中。",
    "ann_zh_unit_ready": "单位就绪。",
    "ann_zh_cancel": "取消。",
    "ann_zh_miner_attack": "我方采矿单位正在遭受攻击！",
    "ann_zh_high_value_warning": "警告！发现敌方",
    "ann_zh_detected": "，已锁定。",
    "ann_zh_enemy_force": "警告！敌方大部队正在接近。",
    "ann_zh_watch_warning": "警告！发现优先目标。",
    "ann_zh_watch_destroyed": "优先目标已被摧毁。",
    "ann_zh_research": "获得新科技。",
    "ann_zh_victory": "任务完成。",
    "ann_zh_defeat": "任务失败。",
    "ann_zh_sector": "区块正在遭受攻击。",
    "ann_zh_sector_captured": "区块已占领。",
    "ann_zh_base": "基地已建立。",
    "ann_zh_reactor": "临界约束失效。",
    "ann_zh_low_power": "电力不足。",
}

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT_DIR = os.path.join(ROOT_DIR, "assets", "sounds")
NAMES_FILE = os.path.join(os.path.dirname(__file__), "mindustry_names.json")
NAMES_ZH_FILE = os.path.join(os.path.dirname(__file__), "mindustry_names_zh.json")


def safe_name(value: str) -> str:
    value = re.sub(r"[^a-zA-Z0-9._-]+", "-", value.strip().lower())
    return value.strip("-") or "unknown"


def load_name_lines(path: str, kind_prefix: str = ""):
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as stream:
        data = json.load(stream)
    return {
        f"name-{kind}-{kind_prefix}{safe_name(name)}": text
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
    lines.update(LINES_ZH)
    lines.update(load_name_lines(NAMES_FILE))
    lines.update(load_name_lines(NAMES_ZH_FILE, kind_prefix="zh-"))
    if only:
        if "zh" in only:
            lines = {name: text for name, text in lines.items()
                     if name.startswith("ann_zh_") or "-zh-" in name}
            only.discard("zh")
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
