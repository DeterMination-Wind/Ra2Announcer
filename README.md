# RA2 Announcer

<h1 align="center">
  <a href="https://github.com/DeterMination-Wind/Ra2Announcer/releases/latest"><img src="https://img.shields.io/github/v/release/DeterMination-Wind/Ra2Announcer?display_name=release&label=Latest%20Release&color=green"></a>
  <a href="https://github.com/DeterMination-Wind/Ra2Announcer/releases"><img src="https://img.shields.io/github/downloads/DeterMination-Wind/Ra2Announcer/total?label=Downloads&color=blue"></a>
  <a href="https://github.com/DeterMination-Wind/Ra2Announcer"><img src="https://img.shields.io/github/stars/DeterMination-Wind/Ra2Announcer?style=flat&label=Star%20this%20mod!&color=yellow"></a>
</h1>

[中文](README_zh.md) | [English](README.md)

> The Red Alert 2 advisor (Zofia) in your Mindustry battles.

A client-side Mindustry mod that announces battlefield events with the **original Red Alert 2 advisor voice (Lieutenant Zofia)**, plus a right-side event feed, world markers and per-category announcement settings.

It is the merged successor of `Ra2Announcer` (high-value targets, watch list, research/campaign/production reports) and `BattleVoice` (weighted voice chains, enemy rally detection, manual-control markers, event cards, type whitelists); this directory is the single project after the merge.

## Announcements

| Category | Trigger | Voice (original Zofia clips, generalised on purpose) | Exact wording in the notification |
|---|---|---|---|
| Waves | Wave starts / warning 5 seconds before / wave cleared | Enemy forces in your area / armor battalion detected / Objective complete | Wave N: enemy forces incoming! / Warning: wave N+1 approaching! / Wave N cleared. |
| Base | Base established / core under attack / core critical / reactor meltdown / sustained low power | Battlefield Control Standby / Our base is under attack / Base defenses offline / extremely vulnerable here / low power | Event description |
| Losses | Our unit or building destroyed | Unit lost / Critical structure lost | Our unit losses / Our buildings destroyed: `name×count, …` |
| Under attack | One of our units is hit | Be warned, comrade general (units that were mining when hit use miner under attack) | Our units under attack / Mining units under attack: `name×count` |
| Production | Factory starts training / production finished / cancelled | training / Unit ready / Cancelled | Training: unit name / Unit ready: name×count / Production cancelled: factory name |
| Rally | Enemy batch control, or a destination pointing at our core | infantry/armor/air/fleet detected (picked by the dominant force) | Dominant unit name + count + highest-threat type |
| Manual control | Enemy or ally manually takes over a unit/turret/building, or commands a building | Category line / Reinforcements have arrived / Structure garrisoned | Enemy player X took control of NAME! / Ally X is moving NAME. |
| High value | A rule-matched enemy unit/building appears or is destroyed | Category line / Beacon detected / Superb, Commander! The Pentagon has been destroyed! | Warning! Enemy NAME detected! / High-value enemy unit NAME destroyed |
| Watch list | A listed enemy unit appears / is killed | Same as above | Warning! Watched target NAME appeared! / Watched target NAME destroyed |
| Research & campaign | Technology unlocked / sector invaded / sector captured | New technology acquired / Enemy forces in your area / assumed command of this base | New technology: content name / Sector N is under attack / Sector N captured |
| Victory & defeat | Victory / defeat | Mission accomplished / Mission failed | Mission complete / Mission failed |
| Bosses | Boss wave warning / boss killed | air armada or armor battalion, depending on the boss | Warning: enemy boss NAME incoming! / Enemy boss NAME destroyed! |
| Enemy core | Enemy core destroyed | Enemy base powered down | Enemy core BLOCK destroyed! |

All fixed lines are original Red Alert 2 recordings (Zofia, 29 clips); unit and building names come from TTS name packs (`zh` Chinese / `en` English), which share the same fixed lines.

## Voice Is Flavour, the Notification Is the Record

**The voice does not have to be accurate; accuracy belongs to the notification.** In practice:

- **Cards and toasts are the single source of truth**: type names, counts, player names, sector names, wave numbers and unlocked content are always written into the card text.
- **Toast fallback when cards are off**: the mod falls back to the vanilla toast (`ui.showInfoToast`) with the same exact text, so accuracy never exists only in speech. The "vanilla toast" setting offers off / when cards are off / every announcement.
- **The voice only sets the mood**: by default it plays the fixed Zofia line and **does not read specific names**, which keeps announcements short; enable "voice reads specific names" to append the name.
- **Rule of thumb**: every announcement must land its card text first and only then consider the voice; anything whose detail can only be heard in the voice is considered a bug. This has already fixed boss warnings, factory cancels and research unlocks, and wave reports now include the wave number.

## Designed Against Announcement Spam

1. **One announcement at a time**: all voice goes through the weighted chains in `Announcer`, with a forced silence gap between chains (the "minimum gap between announcements" setting, default 2 seconds).
2. **No queueing, only preemption**: while a chain is playing, lower-weight announcements are dropped; only a higher-weight announcement at alert level may interrupt.
3. **Aggregate first**: units under attack (a per-bullet event) are de-duplicated per unit type into one card; production, losses and rallies are merged inside short windows.
4. **Per-type cooldowns**: under-attack / production / loss / manual-control / rally each have their own per-type cooldown slider; the destroyed-target voice for high-value and watched units is throttled by the "high-value / watched target destroy interval" and never fires faster than the global minimum gap.
5. **Chain scheduling by real clip length**: each clip schedules the next one using its true duration, so voices never overlap.
6. **Message de-duplication**: the same card message repeated within 1.25 seconds only extends the existing card instead of adding a new one.
7. **Whitelist filters**: separate checkbox whitelists for enemy attack units, our units under attack, our unit losses and our building losses.

## Install

See the **Latest Release** badge at the top for the current version. It requires **Mindustry v8 (build 159) or later** (desktop or Android).

Download `Ra2Announcer-v<version>.jar` from [Releases](https://github.com/DeterMination-Wind/Ra2Announcer/releases), drop it into `<game>/config/mods/`, restart the game and enable it in the mod list. This is a client-side mod: in multiplayer, every client that wants the announcements installs it. The mod is not listed in the in-game mod browser, so install it from the release JAR.

## Settings

Settings → **RA2 Announcer (Sophia)**:

- **Voice**: name-voice language (Chinese/English; follows the game language by default, the button locks an explicit choice), voice reads specific names (off by default), voice volume, minimum gap between announcements.
- **Announcements**: waves, base, core under attack (with interval), unit losses, building losses, our units under attack, mining units under attack (only while actually mining), production finished, factory training, enemy core, boss, enemy rally (minimum count / interval / core radius), manual control (enemy and ally separated), building command, research, campaign, victory/defeat.
- **Targets & filters**: high-value rules (`core,boss,t4,t5,unit:reign,block:foreshadow`, `t1`-`t5` supported), high-value / watched target destroy interval, watch list, four type-filter dialogs.
- **Panel & notifications**: event cards, world markers, connection lines, vanilla toast mode (off / when cards are off / every announcement) and duration, card width / scale / opacity / duration / max entries / spacing / offset, line width and opacity.
- **Colors**: card background, default accent, and a per-category accent (waves / base / attack / core threat / manual control / losses / high value / factory / unit ready / mining / info) editable via the swatch button on the right.

## Voice Assets

```powershell
# Fixed lines: original RA2 Zofia recordings (HTTP-Range download of just the 29 used clips, a few MB)
python tools/fetch_ra2_voice.py            # all
python tools/fetch_ra2_voice.py --list     # show key -> source clip -> transcript
python tools/fetch_ra2_voice.py ann_wave   # regenerate a single line

# Unit/building names: rebuild the vocabulary from the vanilla bundles, then generate with edge-tts
python tools/build_names.py
python tools/generate_voice.py --lang zh   # Chinese (Xiaoxiao)
python tools/generate_voice.py --lang en   # English (Aria)

# Static checks: bilingual bundle keys, settings keys, message keys and audio coverage
python tools/verify_pack.py
```

Source: [HuggingFace `kingsznhone/Red-Alert-2-Full-Voice-Data`](https://huggingface.co/datasets/kingsznhone/Red-Alert-2-Full-Voice-Data) (full RA2/YR voice dataset, including `long_character_anno.txt` transcripts).

> [!note]
> The dataset is declared **non-commercial**, and the Zofia lines are original Westwood/EA recordings: do not use them commercially or ship them in a paid build.

## Build from Source

Prerequisites: **Java 17+**; the workspace Mindustry sources (or the JitPack fallback) for compilation; for the Android side a local D8 (`D8_PATH`, `ANDROID_SDK_ROOT` or `ANDROID_HOME`).

```powershell
./gradlew classes     # quick compile check
./gradlew deploy      # build/libs/Ra2Announcer.jar + dist/Ra2Announcer.jar (desktop + Android, with classes.dex)
./gradlew build       # additionally produces 构建/Ra2Announcer/Ra2Announcer-dev.jar
python tools/verify_pack.py
```

- Dependencies prefer the workspace source builds (`../Mindustry-master/core/build/classes/java/main` + `../Arc/arc-core/build/libs/arc-core-1.0.jar`) and fall back to JitPack `MindustryJitpack:core:v159`.
- Android dexing uses D8, looked up via `D8_PATH` → `ANDROID_SDK_ROOT`/`ANDROID_HOME` → the workspace `commandlinetools-win-*` folder.
- Java 17 (`--release 17`), sources in UTF-8.

## Known Limitations

- "Mining units under attack" is decided by whether the unit was actually mining when hit (`Unit.mining()`): mono/poly/mega repairing, fighting or travelling are reported as ordinary units, and **only units that are mining at that moment use the miner line and card**.
- The `t1`-`t5` tier rules cover the regular units of both vanilla planets (7 Serpulo lines + 3 Erekir lines); v8's `UnitType` has no tier field, so the table matches internal names — for modded or custom units use the `unit:<internal name>` rule instead.
- Client-side only: `headless` returns immediately; in multiplayer only events visible to the client are announced (core health and power are polled; power is host/single-player only).
- Damage events are simulated locally: our units under attack are visible to the local client, but on a bad network they can be delayed or missed.
- Vanilla has no separate event for "a player takes over a turret", but v8 routes turret control through `BlockUnit`, which is covered; pure processor logic control is not synced and is out of scope.
- Name packs look up audio by internal name: missing names are skipped silently (the card still shows the name); run `python tools/verify_pack.py` to check coverage.
- Both languages share the same original RA2 fixed lines; the language setting only switches the spoken unit/building names, which follow the game language (zh_CN / zh_TW → Chinese pack, anything else → English pack) until the player picks one explicitly.
- UI text ships as English, Simplified Chinese and Traditional Chinese (the Traditional bundle is generated from the Simplified one with OpenCC); every other game locale falls back to English.

## License

Code: MIT. The fixed lines are original Red Alert 2 recordings (personal local use only, no commercial use); name voice packs are generated with Microsoft Edge TTS.
