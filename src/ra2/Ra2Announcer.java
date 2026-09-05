package ra2;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Button;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.Element;
import arc.scene.event.ChangeListener;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Time;
import mindustry.ai.UnitGroup;
import mindustry.ai.types.CommandAI;
import mindustry.content.StatusEffects;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.ConfigEvent;
import mindustry.game.EventType.LoseEvent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.SectorCaptureEvent;
import mindustry.game.EventType.SectorInvasionEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.game.EventType.UnitSpawnEvent;
import mindustry.game.EventType.UnlockEvent;
import mindustry.game.EventType.WaveEvent;
import mindustry.game.EventType.WinEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.SpawnGroup;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Tex;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.type.unit.MissileUnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.gen.Sounds;
import mindustry.mod.Mod;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitBlock;

import static mindustry.Vars.*;

/** RA2-style English voice announcements triggered by game events. Client-side mod. */
public class Ra2Announcer extends Mod{

    private static final long COOLDOWN_LINE = 30_000;
    private static final long COOLDOWN_UNIT = 10_000;
    private static final long COOLDOWN_STRUCTURE = 45_000;
    private static final long COOLDOWN_CORE = 60_000;
    private static final float LOSS_FLUSH_TICKS = 60f;
    private static final float LOSS_MAX_WAIT_TICKS = 120f;
    private static final int LOSS_MAX_TYPES = 4;
    private static final float FORCE_WINDOW_TICKS = 24f;
    private static final int FORCE_MAX_GROUPS = 512;
    private static final String[] colorPresets = {
        "ef3d46", "b51f2a", "ff7e46", "ffb347", "ffd166", "a3e048", "4ce0c8",
        "64a0ff", "7d6bff", "b47fff", "ff7eb6", "e8e8e8", "9aa0a6", "33334d"
    };

    private final ObjectMap<String, Sound> sounds = new ObjectMap<>();
    private final ObjectMap<String, PendingLoss> pendingLosses = new ObjectMap<>();
    private final ObjectMap<String, Long> lastPlayed = new ObjectMap<>();
    private final ObjectMap<String, Long> detectedTargets = new ObjectMap<>();
    private final ObjectSet<PowerGraph> powerGraphs = new ObjectSet<>();
    private final ObjectSet<UnitGroup> processedGroups = new ObjectSet<>();
    private final ObjectMap<Integer, Seq<BatchedUnit>> pendingBatches = new ObjectMap<>();
    private final ObjectMap<Integer, Long> forceAnnouncedAt = new ObjectMap<>();
    private final ObjectMap<String, TextButton> colorSwatches = new ObjectMap<>();
    private final Interval timer = new Interval(3);

    private boolean waveWarned;
    private boolean coreCriticalReported;
    private boolean wasWaiting;
    private float lastCoreHealth = -1f;
    private float lowPowerTicks;
    private long lastCoreAttackAt = Long.MIN_VALUE;
    private float batchWindowStart = -1f;
    private boolean forceVoiceDedicated;
    private boolean watchVoiceDedicated;

    @Override
    public void init(){
        if(headless) return;

        loadSounds();
        AnnouncementOverlay.init();
        registerEvents();
        addSettings();
    }

    private void loadSounds(){
        String[] names = {
            "ann_wave", "ann_wave_warn", "ann_wave_cleared",
            "ann_core_attack", "ann_core_critical",
            "ann_unit_lost", "ann_structure_lost", "ann_enemy_base",
            "ann_boss", "ann_boss_kill",
            "ann_training", "ann_unit_ready", "ann_cancel", "ann_miner_attack",
            "ann_high_value_warning", "ann_detected",
            "ann_research", "ann_victory", "ann_defeat",
            "ann_sector", "ann_sector_captured",
            "ann_base", "ann_reactor", "ann_low_power",
            "ann_enemy_force", "ann_watch_warning", "ann_watch_destroyed"
        };

        for(String name : names){
            loadSound(name);
        }
        for(var type : content.units()) loadSound("name-unit-" + type.name);
        for(var block : content.blocks()) loadSound("name-block-" + block.name);
        if(!sounds.containsKey("ann_boss")) loadSoundAlias("ann_boss", "ann_guardian");
        if(!sounds.containsKey("ann_boss_kill")) loadSoundAlias("ann_boss_kill", "ann_guardian_kill");
        //graceful fallback when the newer TTS lines have not been generated yet
        forceVoiceDedicated = sounds.containsKey("ann_enemy_force");
        watchVoiceDedicated = sounds.containsKey("ann_watch_warning");
        if(!forceVoiceDedicated) loadSoundAlias("ann_enemy_force", "ann_high_value_warning");
        if(!watchVoiceDedicated) loadSoundAlias("ann_watch_warning", "ann_high_value_warning");
    }

    private void loadSound(String name){
        try{
            Sound sound = tree.loadSound(name);
            if(sound != null && sound != Sounds.none) sounds.put(name, sound);
        }catch(Throwable t){
            Log.err("Failed to load announcement sound: @", name);
        }
    }

    private void loadSoundAlias(String name, String source){
        Sound sound = sounds.get(source);
        if(sound != null) sounds.put(name, sound);
    }

    private void registerEvents(){
        //new wave spawned; reset the pre-wave warning flag and announce
        Events.on(WaveEvent.class, e -> {
            waveWarned = false;
            checkGuardian();
            playAtCore("ann_wave", "ra2ann-wave", "ra2ann.wave.line", COOLDOWN_LINE);
        });

        //map/sector loaded: base established
        Events.on(WorldLoadEvent.class, e -> {
            pendingLosses.clear();
            detectedTargets.clear();
            AnnouncementOverlay.clear();
            if(!state.rules.editor) playAtCore("ann_base", "ra2ann-base", "ra2ann.base.line", COOLDOWN_LINE);
        });

        //core taking damage (host/singleplayer; multiplayer clients get it via health polling below)
        Events.run(Trigger.teamCoreDamage, this::playCoreAttack);

        //reactor overheating
        Events.run(Trigger.thoriumReactorOverheat, () -> playAtCore("ann_reactor", "ra2ann-base", "ra2ann.reactor.line", COOLDOWN_LINE));

        Events.on(UnitDestroyEvent.class, e -> {
            if(player == null || e.unit == null || isMissile(e.unit)) return;

            if(e.unit.team == player.team()){
                queueLoss(LossKind.UNIT, e.unit.type == null ? "unknown" : e.unit.type.name,
                    e.unit.type == null ? "unknown" : e.unit.type.localizedName,
                    e.unit.type == null ? null : e.unit.type.uiIcon,
                    e.unit.team.id, e.unit.team.localized(), e.unit.x, e.unit.y);
            }else if(e.unit.isBoss() && highValueMatches("boss")){
                //enemy boss eliminated
                playAt("ann_boss_kill", "ra2ann-combat", "ra2ann.boss.kill.line", COOLDOWN_LINE, e.unit.x, e.unit.y);
            }else if(watchKill(e.unit)){
                //handled in watchKill
            }else if(highValueMatches(e.unit)){
                showHighValue("ra2ann.high.value.unit", e.unit.type == null ? "unknown" : e.unit.type.localizedName,
                    e.unit.type == null ? null : e.unit.type.uiIcon, e.unit.x, e.unit.y);
            }
        });

        Events.on(UnitSpawnEvent.class, e -> {
            announceDetected(e.unit);
            watchDetected(e.unit);
        });
        Events.on(BlockBuildEndEvent.class, e -> {
            if(player != null && e.tile != null && e.tile.build != null && e.tile.build.team != playerTeam()
            && highValueMatches(e.tile.build)){
                announceDetected(e.tile.build);
            }
        });
        Events.on(UnitCreateEvent.class, e -> {
            if(player != null && e.spawner != null && e.unit != null && e.unit.team == playerTeam()
            && e.spawner.block instanceof UnitBlock){
                playAt("ann_unit_ready", "ra2ann-unit-ready", "ra2ann.unit.ready.line", COOLDOWN_UNIT, e.unit.x, e.unit.y);
            }
            announceDetected(e.unit);
            watchDetected(e.unit);
        });
        Events.on(UnitDamageEvent.class, e -> {
            if(e.unit != null && e.unit.team == playerTeam() && e.unit.mining()){
                playAt("ann_miner_attack", "ra2ann-miner-under-attack", "ra2ann.miner.attack.line", COOLDOWN_UNIT, e.unit.x, e.unit.y);
            }
        });
        Events.on(ConfigEvent.class, e -> {
            if(player == null || e.tile == null || e.tile.team != player.team() || !(e.tile.block instanceof UnitFactory)) return;
            if(e.value instanceof Integer){
                int plan = (Integer)e.value;
                playAtCore(plan >= 0 ? "ann_training" : "ann_cancel", plan >= 0 ? "ra2ann-factory-training" : "ra2ann-factory-cancel", plan >= 0 ? "ra2ann.training.line" : "ra2ann.cancel.line", COOLDOWN_UNIT);
            }
        });

        Events.on(BlockDestroyEvent.class, e -> {
            if(player == null || e.tile == null || e.tile.build == null) return;

            if(e.tile.build instanceof CoreBlock.CoreBuild){
                //enemy core destroyed
                if(e.tile.build.team != player.team() && highValueMatches("core")){
                    playAt("ann_enemy_base", "ra2ann-combat", "ra2ann.enemy.base.line", COOLDOWN_LINE, e.tile.worldx(), e.tile.worldy());
                }
            }else if(e.tile.build.team != player.team() && highValueMatches(e.tile.build)){
                showHighValue("ra2ann.high.value.block", e.tile.build.block == null ? "unknown" : e.tile.build.block.localizedName,
                    e.tile.build.block == null ? null : e.tile.build.block.uiIcon, e.tile.worldx(), e.tile.worldy());
            }else if(e.tile.build.team == player.team()){
                Building build = e.tile.build;
                Block block = build.block;
                queueLoss(LossKind.BUILDING, block == null ? "unknown" : block.name,
                    block == null ? "unknown" : block.localizedName,
                    block == null ? null : block.uiIcon,
                    build.team.id, build.team.localized(), build.x, build.y);
            }
        });

        Events.on(UnlockEvent.class, e -> playAtCore("ann_research", "ra2ann-tech", "ra2ann.research.line", COOLDOWN_LINE));

        Events.on(WinEvent.class, e -> playAtCore("ann_victory", "ra2ann-tech", "ra2ann.victory.line", 0));
        Events.on(LoseEvent.class, e -> playAtCore("ann_defeat", "ra2ann-tech", "ra2ann.defeat.line", 0));

        Events.on(SectorInvasionEvent.class, e -> playAtCore("ann_sector", "ra2ann-campaign", "ra2ann.sector.line", COOLDOWN_LINE));
        Events.on(SectorCaptureEvent.class, e -> playAtCore("ann_sector_captured", "ra2ann-campaign", "ra2ann.sector.captured.line", COOLDOWN_LINE));

        //reset state when leaving the game
        Events.on(ResetEvent.class, e -> {
            lastPlayed.clear();
            waveWarned = false;
            coreCriticalReported = false;
            wasWaiting = false;
            lowPowerTicks = 0f;
            lastCoreHealth = -1f;
            lastCoreAttackAt = Long.MIN_VALUE;
            batchWindowStart = -1f;
            pendingLosses.clear();
            detectedTargets.clear();
            processedGroups.clear();
            pendingBatches.clear();
            forceAnnouncedAt.clear();
            AnnouncementOverlay.clear();
        });

        //periodic checks that have no dedicated event (or whose events do not fire on multiplayer clients)
        Events.run(Trigger.update, this::update);
    }

    private mindustry.game.Team playerTeam(){
        return player == null ? null : player.team();
    }

    private void announceDetected(mindustry.gen.Unit unit){
        if(player == null || unit == null || unit.team == player.team() || isMissile(unit)
        || !Core.settings.getBool("ra2ann-high-value-detected", true) || !highValueMatches(unit)) return;
        String type = unit.type == null ? "unknown" : unit.type.name;
        long now = Time.millis();
        if(now - detectedTargets.get(type, 0L) < COOLDOWN_LINE) return;
        detectedTargets.put(type, now);
        String name = unit.type == null ? "unknown" : unit.type.localizedName;
        showHighValue("ra2ann.high.value.unit.detected", name, unit.type == null ? null : unit.type.uiIcon, unit.x, unit.y);
        playDetection(type, name, unit.x, unit.y);
    }

    private void announceDetected(Building build){
        if(player == null || build == null || !Core.settings.getBool("ra2ann-high-value-detected", true)
        || !highValueMatches(build)) return;
        String type = build.block == null ? "unknown" : build.block.name;
        long now = Time.millis();
        if(now - detectedTargets.get("block:" + type, 0L) < COOLDOWN_LINE) return;
        detectedTargets.put("block:" + type, now);
        String name = build.block == null ? "unknown" : build.block.localizedName;
        showHighValue("ra2ann.high.value.block.detected", name, build.block == null ? null : build.block.uiIcon, build.x, build.y);
        playDetection("block-" + type, name, build.x, build.y);
    }

    private void playDetection(String type, String displayName, float x, float y){
        if(!Core.settings.getBool("ra2ann-high-value-detected", true)) return;
        playAt("ann_high_value_warning", "ra2ann-high-value-detected", "ra2ann.high.value.warning.line", COOLDOWN_LINE, x, y);
        Sound name = sounds.get("name-" + type.toLowerCase());
        if(name == null && type.toLowerCase().startsWith("block-")) name = sounds.get("name-" + type.toLowerCase());
        if(name == null && !type.toLowerCase().startsWith("block-")) name = sounds.get("name-unit-" + type.toLowerCase());
        Sound detected = sounds.get("ann_detected");
        if(name != null){
            Sound nameSound = name;
            Time.run(35f, nameSound::play);
            if(detected != null){
                Sound detectedSound = detected;
                Time.run(70f, detectedSound::play);
            }
        }
    }

    /**
     * Scans enemy units for freshly-issued command batches. Mindustry's commandUnits packet is
     * forwarded to every client and multi-unit commands put all commanded units of the same
     * physics layer into one CommandAI#group, so a group identity that has not been seen before
     * is exactly "one control action of the enemy" (same data MindustryX uses to draw command lines).
     */
    private void scanCommandGroups(){
        for(Unit unit : Groups.unit){
            if(unit.team == playerTeam() || !(unit.controller() instanceof CommandAI ai) || ai.group == null) continue;
            UnitGroup group = ai.group;
            if(!processedGroups.add(group)) continue;
            if(processedGroups.size > FORCE_MAX_GROUPS) processedGroups.clear();

            Seq<BatchedUnit> batch = pendingBatches.get(unit.team.id);
            if(batch == null){
                batch = new Seq<>();
                pendingBatches.put(unit.team.id, batch);
            }
            for(Unit commanded : group.units){
                if(commanded == null || commanded.team != unit.team || isMissile(commanded)
                || commanded.type == null || commanded.type.estimateDps() <= 0f) continue;
                batch.add(new BatchedUnit(commanded.type, commanded.x, commanded.y));
            }
            if(batchWindowStart < 0f) batchWindowStart = Time.time;
        }
    }

    /** Aggregates command batches over a short window (one command can split into ground/air groups), then reports composition. */
    private void flushForces(boolean force){
        if(pendingBatches.isEmpty()) return;
        if(!force && (batchWindowStart < 0f || Time.time - batchWindowStart < FORCE_WINDOW_TICKS)) return;
        batchWindowStart = -1f;

        long cooldown = Math.max(5, Core.settings.getInt("ra2ann-force-cooldown", 20)) * 1000L;
        int min = Math.max(1, Core.settings.getInt("ra2ann-force-min", 3));

        for(ObjectMap.Entry<Integer, Seq<BatchedUnit>> entry : pendingBatches){
            Seq<BatchedUnit> batch = entry.value;
            if(batch.isEmpty()) continue;

            int total = batch.size;
            ObjectMap<UnitType, Integer> counts = new ObjectMap<>();
            float cx = 0f, cy = 0f, bestThreat = 0f;
            UnitType threat = null;
            for(BatchedUnit b : batch){
                counts.put(b.type, counts.get(b.type, 0) + 1);
                cx += b.x;
                cy += b.y;
                float score = b.type.estimateDps() * b.type.health;
                if(score > bestThreat){
                    bestThreat = score;
                    threat = b.type;
                }
            }
            batch.clear();

            UnitType dominant = null;
            int maxCount = 0, types = counts.size;
            float dominantThreat = -1f;
            for(ObjectMap.Entry<UnitType, Integer> c : counts){
                float score = c.key.estimateDps() * c.key.health;
                if(c.value > maxCount || (c.value == maxCount && score > dominantThreat)){
                    dominant = c.key;
                    maxCount = c.value;
                    dominantThreat = score;
                }
            }
            if(dominant == null) continue;

            long now = Time.millis();
            if(now - forceAnnouncedAt.get(entry.key, 0L) < cooldown) continue;
            forceAnnouncedAt.put(entry.key, now);

            String item = Core.bundle.format("ra2ann.enemy.force.item", dominant.localizedName, maxCount);
            if(types >= 2 && threat != null && threat != dominant){
                item += Core.bundle.get("ra2ann.list.separator", ", ")
                    + Core.bundle.format("ra2ann.enemy.force.threat", threat.localizedName);
            }
            String message = Core.bundle.format("ra2ann.enemy.force.line", item);
            AnnouncementOverlay.show(message, cx / total, cy / total, dominant.uiIcon, item, "ra2ann-enemy-force-color");
            playForceVoice(dominant);
        }
        pendingBatches.clear();
    }

    private void playForceVoice(UnitType dominant){
        if(!Core.settings.getBool("ra2ann-enabled", true)
        || !Core.settings.getBool("ra2ann-enemy-force", true)) return;

        playSound(forceVoiceDedicated ? "ann_enemy_force" : "ann_high_value_warning", "ra2ann-enemy-force", COOLDOWN_LINE);
        Sound name = sounds.get("name-unit-" + dominant.name.toLowerCase());
        if(name != null){
            Sound nameSound = name;
            Time.run(40f, nameSound::play);
            //only the fallback sentence is completed by the generic "detected!"
            if(!forceVoiceDedicated && sounds.containsKey("ann_detected")){
                Sound detected = sounds.get("ann_detected");
                Time.run(75f, detected::play);
            }
        }
    }

    /** Matches a unit type against the player-configured watch list (internal or localized names, comma separated). */
    private boolean watchMatches(UnitType type){
        if(type == null || !Core.settings.getBool("ra2ann-watch-enabled", true)) return false;
        String configured = Core.settings.getString("ra2ann-watch-units", "");
        if(configured == null || configured.trim().isEmpty()) return false;
        String internal = type.name.toLowerCase();
        String local = type.localizedName.toLowerCase();
        for(String item : configured.split("[,，]")){
            String rule = item.trim().toLowerCase();
            if(rule.isEmpty()) continue;
            if(rule.equals(internal) || rule.equals(local)) return true;
        }
        return false;
    }

    private void watchDetected(Unit unit){
        if(player == null || unit == null || unit.team == playerTeam() || isMissile(unit) || !watchMatches(unit.type)) return;
        String type = unit.type.name;
        long now = Time.millis();
        if(now - detectedTargets.get("watch:" + type, 0L) < COOLDOWN_LINE) return;
        detectedTargets.put("watch:" + type, now);
        String name = unit.type.localizedName;
        AnnouncementOverlay.show(Core.bundle.format("ra2ann.watch.unit.detected", name),
            unit.x, unit.y, unit.type.uiIcon, name, "ra2ann-watch-color");
        playWatchVoice("ann_watch_warning", unit.type.name);
    }

    /** @return true if the destroyed unit belongs to the watch list (announcement or throttle). */
    private boolean watchKill(Unit unit){
        if(player == null || unit == null || unit.team == playerTeam() || isMissile(unit) || !watchMatches(unit.type)) return false;
        String type = unit.type.name;
        long now = Time.millis();
        if(now - detectedTargets.get("watchkill:" + type, 0L) < COOLDOWN_LINE) return true;
        detectedTargets.put("watchkill:" + type, now);
        String name = unit.type.localizedName;
        AnnouncementOverlay.show(Core.bundle.format("ra2ann.watch.unit.destroyed", name),
            unit.x, unit.y, unit.type.uiIcon, name, "ra2ann-watch-color");
        playWatchVoice("ann_watch_destroyed", unit.type.name);
        return true;
    }

    private void playWatchVoice(String sound, String typeName){
        if(!Core.settings.getBool("ra2ann-enabled", true)
        || !Core.settings.getBool("ra2ann-watch-enabled", true)) return;

        playSound(sound, "ra2ann-watch-enabled", COOLDOWN_LINE);
        Sound name = sounds.get("name-unit-" + typeName.toLowerCase());
        if(name != null){
            Sound nameSound = name;
            Time.run(35f, nameSound::play);
            //the fallback "Warning! Enemy" sentence is completed by the generic "detected!"
            if(!watchVoiceDedicated && sound.equals("ann_watch_warning") && sounds.containsKey("ann_detected")){
                Sound detected = sounds.get("ann_detected");
                Time.run(70f, detected::play);
            }
        }
    }

    private void update(){
        if(player == null || !state.isGame() || !timer.get(12f)) return;

        flushPendingLosses(false);
        scanCommandGroups();
        flushForces(false);

        //pre-wave warning, 5 seconds before the next wave
        if(state.rules.waves && state.rules.waveTimer && !state.gameOver && !waveWarned
        && state.wavetime > 0f && state.wavetime <= 5f * 60f){
            waveWarned = true;
            playAtCore("ann_wave_warn", "ra2ann-wave", "ra2ann.wave.warn.line", COOLDOWN_LINE);
        }

        //wave cleared: enemies all eliminated, timer resumed
        boolean waiting = logic.isWaitingWave();
        if(wasWaiting && !waiting && state.rules.waves){
            playAtCore("ann_wave_cleared", "ra2ann-wave", "ra2ann.wave.cleared.line", COOLDOWN_LINE);
        }
        wasWaiting = waiting;

        //core health monitoring: works in multiplayer too, unlike Trigger.teamCoreDamage
        var core = player.team().core();
        if(core != null){
            float hp = core.healthf();

            if(lastCoreHealth >= 0f && hp < lastCoreHealth - 0.02f){
                playCoreAttack();
            }
            lastCoreHealth = hp;

            if(!coreCriticalReported && hp < 0.5f){
                coreCriticalReported = true;
                playAtCore("ann_core_critical", "ra2ann-base", "ra2ann.core.critical.line", COOLDOWN_LINE);
            }else if(coreCriticalReported && hp > 0.6f){
                coreCriticalReported = false;
            }
        }else{
            lastCoreHealth = -1f;
            coreCriticalReported = false;
        }

        //low power: team-wide production deficit for a sustained period (host/singleplayer only, as power is simulated locally)
        var data = player.team().data();
        float deficit = 0f;
        if(data != null){
            powerGraphs.clear();
            for(Building b : data.buildings){
                if(b.power != null && b.power.graph != null){
                    powerGraphs.add(b.power.graph);
                }
            }
            for(PowerGraph graph : powerGraphs){
                deficit += graph.getPowerNeeded() - graph.getPowerProduced();
            }
        }

        if(deficit > 2f){
            lowPowerTicks += 12f;
            if(lowPowerTicks > 20f * 60f){
                lowPowerTicks = 0f;
                playAtCore("ann_low_power", "ra2ann-base", "ra2ann.low.power.line", COOLDOWN_LINE);
            }
        }else{
            lowPowerTicks = 0f;
        }
    }

    /** Announces when the next wave (or the one after) is a guardian wave, mirroring vanilla HudFragment logic. */
    private void checkGuardian(){
        int max = 10;
        int winWave = state.rules.winWave > 0 ? state.rules.winWave : Integer.MAX_VALUE;

        for(int i = state.wave - 1; i <= Math.min(state.wave + max, winWave - 2); i++){
            for(SpawnGroup group : state.rules.spawns){
                if(group.effect == StatusEffects.boss && group.getSpawned(i) > 0){
                    int diff = (i + 2) - state.wave;

                    //guardian arrives with the next wave
                    if(diff == 1){
                        playAtCore("ann_boss", "ra2ann-wave", "ra2ann.boss.line", COOLDOWN_LINE);
                    }
                    return;
                }
            }
        }
    }

    private void queueLoss(LossKind kind, String typeKey, String displayName, TextureRegion icon, int teamId, String teamName, float worldX, float worldY){
        if(player == null || player.team() == null || teamId != player.team().id) return;
        if(typeKey == null || typeKey.isEmpty()) typeKey = "unknown";
        if(displayName == null || displayName.isEmpty()) displayName = typeKey;

        String key = kind.name() + ":" + teamId + ":" + typeKey;
        PendingLoss pending = pendingLosses.get(key);
        if(pending == null){
            pending = new PendingLoss(kind, typeKey, displayName, icon, teamId, teamName, worldX, worldY);
            pendingLosses.put(key, pending);
        }else{
            pending.count++;
            pending.x += worldX;
            pending.y += worldY;
            pending.lastTick = Time.time;
        }
    }

    private void flushPendingLosses(boolean force){
        if(pendingLosses.isEmpty()) return;
        Seq<PendingLoss> ready = new Seq<>();
        for(ObjectMap.Entry<String, PendingLoss> entry : pendingLosses){
            PendingLoss pending = entry.value;
            float age = Time.time - pending.lastTick;
            float totalAge = Time.time - pending.firstTick;
            if(force || age >= LOSS_FLUSH_TICKS || totalAge >= LOSS_MAX_WAIT_TICKS){
                ready.add(pending);
            }
        }
        if(ready.isEmpty()) return;

        //merge same-kind losses into one card listing up to LOSS_MAX_TYPES entries
        for(LossKind kind : LossKind.values()){
            Seq<PendingLoss> kindReady = ready.select(pending -> pending.kind == kind);
            if(kindReady.isEmpty()) continue;

            boolean enabled = kind == LossKind.UNIT
                ? Core.settings.getBool("ra2ann-unit-loss", true)
                : Core.settings.getBool("ra2ann-building-loss", true);
            if(!enabled){
                for(PendingLoss pending : kindReady) pendingLosses.remove(pending.key());
                continue;
            }

            kindReady.sort((a, b) -> Integer.compare(b.count, a.count));
            int total = 0;
            float x = 0f, y = 0f;
            for(PendingLoss pending : kindReady){
                total += pending.count;
                x += pending.x * pending.count;
                y += pending.y * pending.count;
            }

            StringBuilder items = new StringBuilder();
            int listed = Math.min(kindReady.size, LOSS_MAX_TYPES);
            for(int i = 0; i < listed; i++){
                if(i > 0) items.append(Core.bundle.get("ra2ann.list.separator", ", "));
                PendingLoss pending = kindReady.get(i);
                items.append(Core.bundle.format("ra2ann.loss.item", pending.displayName, pending.count));
            }
            if(kindReady.size > listed){
                items.append(Core.bundle.get("ra2ann.list.separator", ", "));
                items.append(Core.bundle.format("ra2ann.other.types", kindReady.size - listed));
            }

            String messageKey = kind == LossKind.UNIT ? "ra2ann.unit.lost.batch" : "ra2ann.structure.lost.batch";
            String owner = Core.bundle.get("ra2ann.ours", "ours");
            String message = Core.bundle.format(messageKey, items.toString(), owner);
            PendingLoss top = kindReady.first();
            AnnouncementOverlay.show(message,
                total == 0 ? Float.NaN : x / total, total == 0 ? Float.NaN : y / total,
                top.icon, top.displayName,
                kind == LossKind.UNIT ? "ra2ann-unit-loss-color" : "ra2ann-building-loss-color");

            if(kind == LossKind.UNIT) playSound("ann_unit_lost", "ra2ann-combat", COOLDOWN_UNIT);
            else playSound("ann_structure_lost", "ra2ann-combat", COOLDOWN_STRUCTURE);

            for(PendingLoss pending : kindReady) pendingLosses.remove(pending.key());
        }
    }

    private void playCoreAttack(){
        if(!Core.settings.getBool("ra2ann-core-damage", true)) return;
        long cooldown = Math.max(5, Core.settings.getInt("ra2ann-core-damage-cooldown", 60)) * 1000L;
        long now = Time.millis();
        if(now - lastCoreAttackAt < cooldown) return;
        lastCoreAttackAt = now;
        playAt("ann_core_attack", "ra2ann-base", "ra2ann.core.attack.line", 0, coreWorldX(), coreWorldY());
    }

    private float coreWorldX(){
        if(player != null && player.team() != null && player.team().core() != null) return player.team().core().x;
        return Float.NaN;
    }

    private float coreWorldY(){
        if(player != null && player.team() != null && player.team().core() != null) return player.team().core().y;
        return Float.NaN;
    }

    private boolean isMissile(mindustry.gen.Unit unit){
        return unit != null && (unit.isMissile() || unit.type instanceof MissileUnitType);
    }

    private void play(String name, String setting, String messageKey, long cooldown){
        playAt(name, setting, messageKey, cooldown, Float.NaN, Float.NaN);
    }

    private void playAtCore(String name, String setting, String messageKey, long cooldown){
        if(player != null && player.team() != null){
            var core = player.team().core();
            if(core != null){
                playAt(name, setting, messageKey, cooldown, core.x, core.y);
                return;
            }
        }
        playAt(name, setting, messageKey, cooldown, Float.NaN, Float.NaN);
    }

    private void playAt(String name, String setting, String messageKey, long cooldown, float worldX, float worldY){
        if(!Core.settings.getBool("ra2ann-enabled", true)) return;
        if(!Core.settings.getBool(setting, true)) return;
        if(!state.isGame()) return;

        String message = Core.bundle.get(messageKey, name);
        AnnouncementOverlay.show(message, worldX, worldY, null, null, colorKeyForSetting(setting));
        playSound(name, setting, cooldown);
    }

    private void showHighValue(String key, String name, TextureRegion icon, float x, float y){
        if(!Core.settings.getBool("ra2ann-high-value-enabled", true)) return;
        String message = Core.bundle.format(key, name);
        AnnouncementOverlay.show(message, x, y, icon, name, "ra2ann-high-value-color");
    }

    private boolean highValueMatches(String rule){
        if(!Core.settings.getBool("ra2ann-high-value-enabled", true)) return false;
        String configured = Core.settings.getString("ra2ann-high-value-targets", "core,boss,t5");
        if(configured == null) return false;
        for(String item : configured.split(",")){
            if(item.trim().equalsIgnoreCase(rule)) return true;
        }
        return false;
    }

    private boolean highValueMatches(mindustry.gen.Unit unit){
        if(unit == null || unit.type == null) return false;
        String configured = Core.settings.getString("ra2ann-high-value-targets", "core,boss,t5");
        if(configured == null) return false;
        for(String item : configured.split(",")){
            String rule = item.trim().toLowerCase();
            if(rule.equals("boss") && unit.isBoss()) return true;
            if(rule.equals("t5") && isT5(unit.type)) return true;
            if(rule.equals("unit:" + unit.type.name.toLowerCase())) return true;
        }
        return false;
    }

    private String colorKeyForSetting(String setting){
        if(setting == null) return "ra2ann-accent-color";
        if(setting.equals("ra2ann-wave")) return "ra2ann-wave-color";
        if(setting.equals("ra2ann-base")) return "ra2ann-base-color";
        if(setting.equals("ra2ann-combat")) return "ra2ann-combat-color";
        if(setting.equals("ra2ann-tech")) return "ra2ann-tech-color";
        if(setting.equals("ra2ann-campaign")) return "ra2ann-campaign-color";
        if(setting.equals("ra2ann-unit-loss")) return "ra2ann-unit-loss-color";
        if(setting.equals("ra2ann-building-loss")) return "ra2ann-building-loss-color";
        if(setting.equals("ra2ann-high-value-detected")) return "ra2ann-high-value-color";
        if(setting.equals("ra2ann-factory-training")) return "ra2ann-factory-training-color";
        if(setting.equals("ra2ann-factory-cancel")) return "ra2ann-factory-cancel-color";
        if(setting.equals("ra2ann-unit-ready")) return "ra2ann-unit-ready-color";
        if(setting.equals("ra2ann-miner-under-attack")) return "ra2ann-miner-color";
        return "ra2ann-accent-color";
    }

    private boolean highValueMatches(Building build){
        if(build == null || build.block == null) return false;
        if(build instanceof CoreBlock.CoreBuild && highValueMatches("core")) return true;
        String configured = Core.settings.getString("ra2ann-high-value-targets", "core,boss,t5");
        if(configured == null) return false;
        String name = build.block.name.toLowerCase();
        for(String item : configured.split(",")){
            if(item.trim().toLowerCase().equals("block:" + name)) return true;
        }
        return false;
    }

    private boolean isT5(mindustry.type.UnitType type){
        String name = type.name.toLowerCase();
        return name.equals("omura") || name.equals("reign") || name.equals("toxopid")
            || name.equals("eclipse") || name.equals("oct") || name.equals("corvus");
    }

    private void playSound(String name, String setting, long cooldown){
        if(!Core.settings.getBool("ra2ann-enabled", true)) return;
        if(!Core.settings.getBool(setting, true)) return;
        if(!state.isGame()) return;

        Sound sound = sounds.get(name);
        if(sound == null || sound == Sounds.none) return;

        long now = Time.millis();
        if(now - lastPlayed.get(name, 0L) < cooldown) return;
        lastPlayed.put(name, now);
        sound.play();
    }

    private void addSettings(){
        ui.settings.addCategory("RA2 Announcer", t -> {
            t.checkPref("ra2ann-enabled", true);
            categoryRow(t, "ra2ann-wave", "ra2ann-wave-color", "ef3d46");
            categoryRow(t, "ra2ann-base", "ra2ann-base-color", "ef3d46");
            categoryRow(t, "ra2ann-combat", "ra2ann-combat-color", "ef3d46");
            categoryRow(t, "ra2ann-unit-loss", "ra2ann-unit-loss-color", "ef3d46");
            categoryRow(t, "ra2ann-building-loss", "ra2ann-building-loss-color", "ef3d46");
            categoryRow(t, "ra2ann-high-value-detected", "ra2ann-high-value-color", "ffb347");
            categoryRow(t, "ra2ann-factory-training", "ra2ann-factory-training-color", "64a0ff");
            categoryRow(t, "ra2ann-factory-cancel", "ra2ann-factory-cancel-color", "ef3d46");
            categoryRow(t, "ra2ann-unit-ready", "ra2ann-unit-ready-color", "64a0ff");
            categoryRow(t, "ra2ann-miner-under-attack", "ra2ann-miner-color", "ffd166");
            categoryRow(t, "ra2ann-tech", "ra2ann-tech-color", "64a0ff");
            categoryRow(t, "ra2ann-campaign", "ra2ann-campaign-color", "64a0ff");
            categoryRow(t, "ra2ann-enemy-force", "ra2ann-enemy-force-color", "ff7e46");
            t.sliderPref("ra2ann-force-min", 3, 2, 10, 1, value -> Integer.toString(value));
            t.sliderPref("ra2ann-force-cooldown", 20, 5, 120, 5, value -> value + "s");
            categoryRow(t, "ra2ann-watch-enabled", "ra2ann-watch-color", "b47fff");
            t.textPref("ra2ann-watch-units", "");
            t.checkPref("ra2ann-ui-enabled", true);
            t.checkPref("ra2ann-marker-enabled", true);
            t.sliderPref("ra2ann-ui-width", 320, 240, 560, 20, value -> value + "px");
            t.sliderPref("ra2ann-ui-scale", 100, 80, 160, 5, value -> value + "%");
            t.sliderPref("ra2ann-ui-duration", 4, 1, 12, 1, value -> value + "s");
            t.sliderPref("ra2ann-marker-duration", 8, 2, 20, 1, value -> value + "s");
            t.sliderPref("ra2ann-ui-max-entries", 6, 3, 10, 1, value -> Integer.toString(value));
            t.checkPref("ra2ann-line-enabled", true);
            t.sliderPref("ra2ann-ui-spacing", 4, 0, 12, 1, value -> value + "px");
            t.sliderPref("ra2ann-ui-offset-x", 12, 0, 3840, 4, value -> value + "px");
            t.sliderPref("ra2ann-ui-offset-y", 72, 0, 2160, 4, value -> value + "px");
            t.checkPref("ra2ann-core-damage", true);
            t.sliderPref("ra2ann-core-damage-cooldown", 60, 5, 300, 5, value -> value + "s");
            t.checkPref("ra2ann-high-value-enabled", true);
            t.textPref("ra2ann-high-value-targets", "core,boss,t5");
            t.sliderPref("ra2ann-line-width", 3, 1, 6, 1, value -> value + "px");
            t.sliderPref("ra2ann-line-alpha", 95, 35, 100, 5, value -> value + "%");
            t.checkPref("ra2ann-line-solid", true);
            colorOnlyRow(t, "ra2ann-card-color", "b51f2a");
            colorOnlyRow(t, "ra2ann-accent-color", "ef3d46");
            t.button(Core.bundle.get("ra2ann.testline", "Test announcement"), () -> {
                var keys = sounds.keys().toSeq();
                if(keys.size > 0){
                    String key = keys.random();
                    playAtCore(key, "ra2ann-wave", messageKey(key), 0);
                }
            }).width(220f);
        });
    }

    /** One settings row: category toggle filling the left side, inline color swatch on the right. */
    private void categoryRow(Table settings, String checkKey, String colorKey, String colorDefault){
        Table row = new Table();
        //vanilla-style checkbox row (mindustry.ui.Elems does not exist in v155.4)
        Button check = new Button(Styles.grayt);
        check.background(Styles.grayPanel);
        check.margin(10f);
        check.add(new Image()).update(i -> i.setDrawable(check.isOver()
            ? (check.isChecked() ? Tex.checkOnOver : Tex.checkOver)
            : check.isChecked() ? Tex.checkOn : Tex.checkOff))
            .size(32f).padRight(8f).padLeft(-4f);
        check.add(Core.bundle.get("setting." + checkKey + ".name"));
        check.setChecked(Core.settings.getBool(checkKey, true));
        check.clicked(() -> Core.settings.put(checkKey, check.isChecked()));
        check.left();
        row.add(check).growX().height(45f).left();
        addColorSwatch(row, colorKey, colorDefault);
        settings.add(row).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f)))
            .fillX().height(45f).left().padTop(7f);
        settings.row();
    }

    private void colorOnlyRow(Table settings, String colorKey, String colorDefault){
        Table row = new Table();
        row.add(Core.bundle.get("setting." + colorKey + ".name")).growX().height(45f).left().padLeft(10f);
        addColorSwatch(row, colorKey, colorDefault);
        settings.add(row).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f)))
            .fillX().height(45f).left().padTop(7f);
        settings.row();
    }

    private void addColorSwatch(Table row, String colorKey, String colorDefault){
        String value = Core.settings.getString(colorKey, colorDefault);
        TextButton swatch = row.button(value, Styles.flatTogglet, () -> showColorEditor(colorKey, colorDefault))
            .width(112f).height(45f).padLeft(6f).get();
        swatch.getLabel().setColor(parseHexOr(value, colorDefault));
        colorSwatches.put(colorKey, swatch);
    }

    private void showColorEditor(String key, String colorDefault){
        String current = Core.settings.getString(key, colorDefault);
        BaseDialog dialog = new BaseDialog(Core.bundle.get("ra2ann.color.title", "Announcement color"));
        dialog.cont.margin(16f);

        Table top = new Table();
        Image preview = new Image(Tex.whiteui);
        preview.setColor(parseHexOr(current, colorDefault));
        TextField field = new TextField(current == null ? "" : current, Styles.defaultField);
        field.setMessageText(Core.bundle.get("ra2ann.color.hint", "ef3d46"));
        field.addListener(new ChangeListener(){
            @Override
            public void changed(ChangeListener.ChangeEvent event, Element actor){
                preview.setColor(parseHexOr(field.getText(), colorDefault));
            }
        });
        top.add(preview).size(48f).padRight(12f);
        top.add(field).width(200f).height(48f);
        dialog.cont.add(top).row();

        Table grid = new Table();
        for(int i = 0; i < colorPresets.length; i++){
            String hex = colorPresets[i];
            TextButton preset = grid.button(hex, Styles.flatTogglet, () -> field.setText(hex)).size(76f, 40f).get();
            preset.getLabel().setColor(Color.valueOf(hex));
            if((i + 1) % 7 == 0) grid.row();
        }
        dialog.cont.add(grid).padTop(10f).row();

        Label invalid = new Label(Core.bundle.get("ra2ann.color.invalid", "Invalid hex color"), Styles.outlineLabel);
        invalid.setColor(Color.scarlet);
        invalid.visible = false;
        dialog.cont.add(invalid).padTop(6f).row();

        dialog.buttons.defaults().width(120f);
        dialog.buttons.button("@ok", () -> {
            String value = field.getText().trim().replace("#", "").toLowerCase();
            if(value.matches("[0-9a-f]{6}([0-9a-f]{2})?")){
                Core.settings.put(key, value);
                TextButton swatch = colorSwatches.get(key);
                if(swatch != null){
                    swatch.setText(value);
                    swatch.getLabel().setColor(parseHexOr(value, colorDefault));
                }
                dialog.hide();
            }else{
                invalid.visible = true;
            }
        });
        dialog.buttons.button("@cancel", dialog::hide);
        dialog.show();
    }

    private static Color parseHexOr(String value, String fallback){
        if(value != null){
            String clean = value.trim().replace("#", "");
            if(clean.length() == 6 || clean.length() == 8){
                try{
                    return Color.valueOf(clean);
                }catch(Throwable ignored){
                }
            }
        }
        return Color.valueOf(fallback);
    }

    private enum LossKind{
        UNIT,
        BUILDING
    }

    /** Immutable snapshot of one commanded unit, taken at scan time (units may die before the window flushes). */
    private static class BatchedUnit{
        final UnitType type;
        final float x;
        final float y;

        BatchedUnit(UnitType type, float x, float y){
            this.type = type;
            this.x = x;
            this.y = y;
        }
    }

    private static class PendingLoss{
        final LossKind kind;
        final String typeKey;
        final String displayName;
        final TextureRegion icon;
        final int teamId;
        final String teamName;
        final float firstTick;
        int count = 1;
        float lastTick;
        float x;
        float y;

        PendingLoss(LossKind kind, String typeKey, String displayName, TextureRegion icon, int teamId, String teamName, float x, float y){
            this.kind = kind;
            this.typeKey = typeKey;
            this.displayName = displayName;
            this.icon = icon;
            this.teamId = teamId;
            this.teamName = teamName;
            this.firstTick = Time.time;
            this.lastTick = firstTick;
            this.x = x;
            this.y = y;
        }

        String key(){
            return kind.name() + ":" + teamId + ":" + typeKey;
        }
    }

    private String messageKey(String soundName){
        switch(soundName){
            case "ann_wave": return "ra2ann.wave.line";
            case "ann_wave_warn": return "ra2ann.wave.warn.line";
            case "ann_wave_cleared": return "ra2ann.wave.cleared.line";
            case "ann_core_attack": return "ra2ann.core.attack.line";
            case "ann_core_critical": return "ra2ann.core.critical.line";
            case "ann_unit_lost": return "ra2ann.unit.lost.line";
            case "ann_structure_lost": return "ra2ann.structure.lost.line";
            case "ann_enemy_base": return "ra2ann.enemy.base.line";
            case "ann_boss": return "ra2ann.boss.line";
            case "ann_boss_kill": return "ra2ann.boss.kill.line";
            case "ann_training": return "ra2ann.training.line";
            case "ann_unit_ready": return "ra2ann.unit.ready.line";
            case "ann_cancel": return "ra2ann.cancel.line";
            case "ann_miner_attack": return "ra2ann.miner.attack.line";
            case "ann_high_value_warning": return "ra2ann.high.value.warning.line";
            case "ann_detected": return "ra2ann.detected.line";
            case "ann_research": return "ra2ann.research.line";
            case "ann_victory": return "ra2ann.victory.line";
            case "ann_defeat": return "ra2ann.defeat.line";
            case "ann_sector": return "ra2ann.sector.line";
            case "ann_sector_captured": return "ra2ann.sector.captured.line";
            case "ann_base": return "ra2ann.base.line";
            case "ann_reactor": return "ra2ann.reactor.line";
            case "ann_low_power": return "ra2ann.low.power.line";
            default: return "ra2ann.base.line";
        }
    }
}
