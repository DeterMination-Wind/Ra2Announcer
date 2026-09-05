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
| | "Warning! Enemy strike force approaching." | A multi-unit enemy command batch is detected (see Enemy force reports) |
| | "Warning! Priority enemy target detected." | A watched enemy unit from the custom unit watch list appears |
| | "Priority target destroyed." | A watched enemy unit is destroyed |
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
- **Per-alert colors** — Wave, Base, Combat, Unit loss, Building loss, High-value target, Tech/results, Campaign, Factory, Ore miner, Enemy force, and Custom unit watch alerts each have an inline color swatch button on the right of their toggle row. Click it to edit the color with a hex field, preset swatches, and a live preview. The color controls that alert's card accent bar, world marker, and connecting line. Use `b51f2a`, `#b51f2a`, or `RRGGBBAA`; invalid values fall back to the category default. Stored settings keys are unchanged, so existing colors carry over.
- **Card / accent color** — the global card background and accent fallback colors as swatch rows.
- **Enemy force reports** — announces the composition of a multi-unit enemy control action: the most numerous unit type, plus the highest-threat type (estimated DPS × health) for mixed groups. Uses the same client-visible command data MindustryX draws its command lines from, so it works in multiplayer. `Min force size` (2–10) and `report interval` (5–120 s per team) control spam; non-combat and missile units are ignored.
- **Custom unit watch** — a comma-separated list of unit internal or localized names (case-insensitive, English or Chinese commas both work). Watched enemy units trigger a detection announcement when they appear and a destruction confirmation when they are killed, each with its own per-type cooldown.
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
python tools/generate_voice.py "en-US-ChristopherNeural" ann_enemy_force   # regenerate single lines
```

Requires `ffmpeg` in PATH. Outputs loudness-normalized mono OGG files into `assets/sounds/`.

## Build from source

```powershell
./gradlew jar
# artifact: build/libs/Ra2AnnouncerDesktop.jar
```

## Notes

- Cooldowns prevent announcement spam; each line has an independent cooldown.
- Friendly unit and building losses within about one second are merged into a single card that lists up to four types (for example, `T-4 x3, Poly x2, and 2 other types`); the voice line remains rate-limited separately.
- Turning the side announcement panel off also hides the connecting lines (they anchor onto the hidden cards); world target markers keep working on their own.
- Missile units are ignored by destruction announcements, including custom `MissileUnitType` units.
- Servers are unaffected: the mod no-ops when `headless`.
- Core health and low power are polled, so multiplayer clients still hear base alerts even though damage/power simulation runs on the host.
- Multiplayer-only server events (unit/building destruction on remote clients) play on the host or in singleplayer; remote clients hear synced events (waves, sector, win/lose).

## License

Code: MIT. Voice lines: generated via Microsoft Edge TTS, no RA2 assets included.
