package ra2;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Time;
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
import mindustry.type.unit.MissileUnitType;
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

    private final ObjectMap<String, Sound> sounds = new ObjectMap<>();
    private final ObjectMap<String, PendingLoss> pendingLosses = new ObjectMap<>();
    private final ObjectMap<String, Long> lastPlayed = new ObjectMap<>();
    private final ObjectMap<String, Long> detectedTargets = new ObjectMap<>();
    private final ObjectSet<PowerGraph> powerGraphs = new ObjectSet<>();
    private final Interval timer = new Interval(3);

    private boolean waveWarned;
    private boolean coreCriticalReported;
    private boolean wasWaiting;
    private float lastCoreHealth = -1f;
    private float lowPowerTicks;
    private long lastCoreAttackAt = Long.MIN_VALUE;

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
            "ann_base", "ann_reactor", "ann_low_power"
        };

        for(String name : names){
            loadSound(name);
        }
        for(var type : content.units()) loadSound("name-unit-" + type.name);
        for(var block : content.blocks()) loadSound("name-block-" + block.name);
        if(!sounds.containsKey("ann_boss")) loadSoundAlias("ann_boss", "ann_guardian");
        if(!sounds.containsKey("ann_boss_kill")) loadSoundAlias("ann_boss_kill", "ann_guardian_kill");
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
            }else if(highValueMatches(e.unit)){
                showHighValue("ra2ann.high.value.unit", e.unit.type == null ? "unknown" : e.unit.type.localizedName,
                    e.unit.type == null ? null : e.unit.type.uiIcon, e.unit.x, e.unit.y);
            }
        });

        Events.on(UnitSpawnEvent.class, e -> announceDetected(e.unit));
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
            pendingLosses.clear();
            detectedTargets.clear();
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

    private void update(){
        if(player == null || !state.isGame() || !timer.get(12f)) return;

        flushPendingLosses(false);

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

        boolean unitSoundPlayed = false;
        boolean buildingSoundPlayed = false;
        for(PendingLoss pending : ready){
            pendingLosses.remove(pending.key());

            String item = Core.bundle.format("ra2ann.loss.item", pending.displayName, pending.count);
            String messageKey = pending.kind == LossKind.UNIT
                ? (pending.count == 1 ? "ra2ann.unit.lost.detail" : "ra2ann.unit.lost.batch")
                : (pending.count == 1 ? "ra2ann.structure.lost.detail" : "ra2ann.structure.lost.batch");
            String owner = Core.bundle.get("ra2ann.ours", "ours");
            String message = Core.bundle.format(messageKey, item, owner);
            float x = pending.count == 0 ? Float.NaN : pending.x / pending.count;
            float y = pending.count == 0 ? Float.NaN : pending.y / pending.count;
            boolean enabled = pending.kind == LossKind.UNIT
                ? Core.settings.getBool("ra2ann-unit-loss", true)
                : Core.settings.getBool("ra2ann-building-loss", true);
            if(!enabled) continue;
            AnnouncementOverlay.show(message, x, y, pending.icon, item,
                pending.kind == LossKind.UNIT ? "ra2ann-unit-loss-color" : "ra2ann-building-loss-color");

            if(pending.kind == LossKind.UNIT && !unitSoundPlayed){
                unitSoundPlayed = true;
                playSound("ann_unit_lost", "ra2ann-combat", COOLDOWN_UNIT);
            }else if(pending.kind == LossKind.BUILDING && !buildingSoundPlayed){
                buildingSoundPlayed = true;
                playSound("ann_structure_lost", "ra2ann-combat", COOLDOWN_STRUCTURE);
            }
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
            t.checkPref("ra2ann-wave", true);
            t.textPref("ra2ann-wave-color", "ef3d46");
            t.checkPref("ra2ann-base", true);
            t.textPref("ra2ann-base-color", "ef3d46");
            t.checkPref("ra2ann-combat", true);
            t.textPref("ra2ann-combat-color", "ef3d46");
            t.checkPref("ra2ann-unit-loss", true);
            t.textPref("ra2ann-unit-loss-color", "ef3d46");
            t.checkPref("ra2ann-building-loss", true);
            t.textPref("ra2ann-building-loss-color", "ef3d46");
            t.checkPref("ra2ann-high-value-detected", true);
            t.textPref("ra2ann-high-value-color", "ffb347");
            t.checkPref("ra2ann-factory-training", true);
            t.textPref("ra2ann-factory-training-color", "64a0ff");
            t.checkPref("ra2ann-factory-cancel", true);
            t.textPref("ra2ann-factory-cancel-color", "ef3d46");
            t.checkPref("ra2ann-unit-ready", true);
            t.textPref("ra2ann-unit-ready-color", "64a0ff");
            t.checkPref("ra2ann-miner-under-attack", true);
            t.textPref("ra2ann-miner-color", "ffd166");
            t.checkPref("ra2ann-tech", true);
            t.textPref("ra2ann-tech-color", "64a0ff");
            t.checkPref("ra2ann-campaign", true);
            t.textPref("ra2ann-campaign-color", "64a0ff");
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
            t.textPref("ra2ann-card-color", "b51f2a");
            t.textPref("ra2ann-accent-color", "ef3d46");
            t.textPref("ra2ann-line-color", "ef3d46");
            t.button(Core.bundle.get("ra2ann.testline", "Test announcement"), () -> {
                var keys = sounds.keys().toSeq();
                if(keys.size > 0){
                    String key = keys.random();
                    playAtCore(key, "ra2ann-wave", messageKey(key), 0);
                }
            }).width(220f);
        });
    }

    private enum LossKind{
        UNIT,
        BUILDING
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
