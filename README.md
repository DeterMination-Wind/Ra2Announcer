# RA2 Announcer

RA2-style English voice announcements for Mindustry game events. Plays short voiced lines when waves hit, your base is attacked, units are lost, research completes, and more — a client-side mod with no server requirement.

## Install

1. Download `Ra2Announcer.jar` from the [latest release](https://github.com/DeterMination-Wind/Ra2Announcer/releases).
2. Drop it into `<game>/config/mods/`.
3. Restart the game.

Requires game build **154+** (Java mod). In multiplayer, install it on every client that wants to hear announcements — playback is client-side, exactly like vanilla wave sounds.

## Announcements

The fixed event lines include wave, base, combat, factory, mining, Boss, and high-value-target alerts. Factory actions include `Training`, `Unit ready`, and `Cancel`; mining units trigger `Ore miner is under attack!`. Boss is used instead of Guardian in all user-facing text.

| Category | Line | Trigger |
|---|---|---|
| Waves | "Incoming wave." | New wave starts |
| | "Warning. Enemy forces approaching." | 5 s before the next wave |
| | "Wave cleared." | All enemies eliminated |
| | "Boss detected." | Boss wave imminent |
| | "Boss destroyed." | Enemy boss eliminated |
| Base | "Base established." | Map / sector loaded |
| | "Our base is under attack!" | Core taking damage |
| | "Base defenses critical." | Core below 50% health |
| | "Critical containment failure." | Reactor overheating |
| | "Low power." | Sustained power deficit |
| Combat | "Unit lost." | Friendly unit destroyed; the side panel shows the unit type and count |
| | "Structure destroyed." | Friendly building destroyed; the side panel shows the block type and count |
| | "Enemy base destroyed." | Enemy core destroyed |
| | "Warning! Enemy xxx detected!" | A configured high-value enemy unit or building appears |
| | "Training." | A unit is selected in a friendly unit factory |
| | "Unit ready." | A friendly unit finishes production |
| | "Cancel." | A unit factory selection is cleared |
| | "Ore miner is under attack!" | A friendly unit currently mining is hit |
| Tech & results | "New technology acquired." | Research unlocked |
| | "Mission accomplished." | Victory |
| | "Mission failed." | Defeat |
| Campaign | "Sector under attack." | Sector invaded |
| | "Sector captured." | Sector captured |

## Settings

Settings > **RA2 Announcer**:

- **Enabled** — master switch.
- Per-category toggles: Wave alerts, Base alerts, Combat alerts, Tech & results, Campaign alerts.
- **Test announcement** — plays a random line so you can check volume.
- **Side announcement panel** — keeps several local alert cards on the left side instead of replacing every message immediately.
- **World target marker** — highlights each event location, or your team core when an event has no location.
- **Connecting lines** — links each alert card to its corresponding world target and follows camera movement.
- **Panel width / scale** — adjusts the card size and text scale.
- **Panel duration / marker duration** — controls how long each card and target remain visible.
- **Maximum visible cards / spacing / offsets** — controls queue size, card spacing, and placement around the vanilla HUD. The offset ranges cover up to 4K-sized layouts.
- **Core damage alerts / interval** — independently enables core-hit alerts and limits their card, marker, line, and voice frequency (5–300 seconds).
- **Per-alert colors** — Wave, Base, Combat, Unit loss, Building loss, High-value target, Tech/results, Campaign, Factory, and Ore miner alerts each have their own Hex color field. The color controls that alert's card accent bar, world marker, and connecting line. Use `b51f2a`, `#b51f2a`, or `RRGGBBAA`; invalid values fall back to the category default.
- **Card / accent / line color** — the global card background, legacy accent, and line fields remain as compatibility fallbacks.
- **Line width / opacity / solid mode** — makes connections easier to see on bright maps and supports solid or dashed lines.
- **High-value target alerts** — rules are comma-separated internal names. `core,boss,t5` means enemy cores, every `Unit.isBoss()` unit, and the six vanilla T5 units. For custom content, use `boss,t5,unit:reign,unit:my-custom-unit,block:foreshadow,block:my-custom-block`. `unit:` and `block:` use internal names, not localized display names; matching is case-insensitive.
- **Independent event toggles** — unit loss, building loss, high-value detection, factory training/cancel, unit ready, and ore-miner attack alerts can be disabled separately.
- **Mindustry unit/building names** — `tools/mindustry_names.json` is the TTS vocabulary. Generated `name-unit-*.ogg` and `name-block-*.ogg` files are used to speak detected target names; missing name clips safely fall back to the generic warning.

The panel and marker are local to the current player. They do not send network messages or require CW3, MindustryX, or a server-side script.

## Customize the voice

Regenerate all audio with a different [edge-tts](https://github.com/rany2/edge-tts) voice:

```powershell
pip install edge-tts
python tools/generate_voice.py "en-US-ChristopherNeural"   # or any edge-tts voice
```

Requires `ffmpeg` in PATH. Outputs loudness-normalized mono OGG files into `assets/sounds/`.

## Build from source

```powershell
./gradlew jar
# artifact: build/libs/Ra2AnnouncerDesktop.jar
```

## Notes

- Cooldowns prevent announcement spam; each line has an independent cooldown.
- Friendly unit and building losses are grouped for about one second. The side panel reports the concrete type and count (for example, `T-4 x3, Poly x2`), while the voice line remains rate-limited separately.
- Missile units are ignored by destruction announcements, including custom `MissileUnitType` units.
- Servers are unaffected: the mod no-ops when `headless`.
- Core health and low power are polled, so multiplayer clients still hear base alerts even though damage/power simulation runs on the host.
- Multiplayer-only server events (unit/building destruction on remote clients) play on the host or in singleplayer; remote clients hear synced events (waves, sector, win/lose).

## License

Code: MIT. Voice lines: generated via Microsoft Edge TTS, no RA2 assets included.
