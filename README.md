# RA2 Announcer

RA2-style English voice announcements for Mindustry game events. Plays short voiced lines when waves hit, your base is attacked, units are lost, research completes, and more — a client-side mod with no server requirement.

## Install

1. Download `Ra2Announcer.jar` from the [latest release](https://github.com/DeterMination-Wind/Ra2Announcer/releases).
2. Drop it into `<game>/config/mods/`.
3. Restart the game.

Requires game build **154+** (Java mod). In multiplayer, install it on every client that wants to hear announcements — playback is client-side, exactly like vanilla wave sounds.

## Announcements

18 lines in an en-US neural voice (Aria):

| Category | Line | Trigger |
|---|---|---|
| Waves | "Incoming wave." | New wave starts |
| | "Warning. Enemy forces approaching." | 5 s before the next wave |
| | "Wave cleared." | All enemies eliminated |
| | "Guardian detected." | Boss wave imminent |
| | "Guardian destroyed." | Enemy boss eliminated |
| Base | "Base established." | Map / sector loaded |
| | "Our base is under attack!" | Core taking damage |
| | "Base defenses critical." | Core below 50% health |
| | "Critical containment failure." | Reactor overheating |
| | "Low power." | Sustained power deficit |
| Combat | "Unit lost." | Friendly unit destroyed |
| | "Structure destroyed." | Friendly building destroyed |
| | "Enemy base destroyed." | Enemy core destroyed |
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
- Servers are unaffected: the mod no-ops when `headless`.
- Core health and low power are polled, so multiplayer clients still hear base alerts even though damage/power simulation runs on the host.
- Multiplayer-only server events (unit/building destruction on remote clients) play on the host or in singleplayer; remote clients hear synced events (waves, sector, win/lose).

## License

Code: MIT. Voice lines: generated via Microsoft Edge TTS, no RA2 assets included.
