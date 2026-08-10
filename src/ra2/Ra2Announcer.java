package ra2;

import arc.Core;
import arc.Events;
import arc.audio.Sound;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Time;
import mindustry.content.StatusEffects;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.LoseEvent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.SectorCaptureEvent;
import mindustry.game.EventType.SectorInvasionEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.UnlockEvent;
import mindustry.game.EventType.WaveEvent;
import mindustry.game.EventType.WinEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.SpawnGroup;
import mindustry.gen.Building;
import mindustry.gen.Sounds;
import mindustry.mod.Mod;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.*;

/** RA2-style English voice announcements triggered by game events. Client-side mod. */
public class Ra2Announcer extends Mod{

    private static final long COOLDOWN_LINE = 30_000;
    private static final long COOLDOWN_UNIT = 10_000;
    private static final long COOLDOWN_STRUCTURE = 45_000;
    private static final long COOLDOWN_CORE = 60_000;

    private final ObjectMap<String, Sound> sounds = new ObjectMap<>();
    private final ObjectMap<String, Long> lastPlayed = new ObjectMap<>();
    private final ObjectSet<PowerGraph> powerGraphs = new ObjectSet<>();
    private final Interval timer = new Interval(3);

    private boolean waveWarned;
    private boolean coreCriticalReported;
    private boolean wasWaiting;
    private float lastCoreHealth = -1f;
    private float lowPowerTicks;

    @Override
    public void init(){
        if(headless) return;

        loadSounds();
        registerEvents();
        addSettings();
    }

    private void loadSounds(){
        String[] names = {
            "ann_wave", "ann_wave_warn", "ann_wave_cleared",
            "ann_core_attack", "ann_core_critical",
            "ann_unit_lost", "ann_structure_lost", "ann_enemy_base",
            "ann_guardian", "ann_guardian_kill",
            "ann_research", "ann_victory", "ann_defeat",
            "ann_sector", "ann_sector_captured",
            "ann_base", "ann_reactor", "ann_low_power"
        };

        for(String name : names){
            try{
                Sound sound = tree.loadSound(name);
                if(sound != null && sound != Sounds.none){
                    sounds.put(name, sound);
                }
            }catch(Throwable t){
                //missing file must not break the mod
                Log.err("Failed to load announcement sound: @", name);
            }
        }
    }

    private void registerEvents(){
        //new wave spawned; reset the pre-wave warning flag and announce
        Events.on(WaveEvent.class, e -> {
            waveWarned = false;
            checkGuardian();
            play("ann_wave", "ra2ann-wave", COOLDOWN_LINE);
        });

        //map/sector loaded: base established
        Events.on(WorldLoadEvent.class, e -> {
            if(!state.rules.editor) play("ann_base", "ra2ann-base", COOLDOWN_LINE);
        });

        //core taking damage (host/singleplayer; multiplayer clients get it via health polling below)
        Events.run(Trigger.teamCoreDamage, () -> play("ann_core_attack", "ra2ann-base", COOLDOWN_CORE));

        //reactor overheating
        Events.run(Trigger.thoriumReactorOverheat, () -> play("ann_reactor", "ra2ann-base", COOLDOWN_LINE));

        Events.on(UnitDestroyEvent.class, e -> {
            if(player == null) return;

            if(e.unit.team == player.team()){
                play("ann_unit_lost", "ra2ann-combat", COOLDOWN_UNIT);
            }else if(e.unit.isBoss()){
                //enemy guardian eliminated
                play("ann_guardian_kill", "ra2ann-combat", COOLDOWN_LINE);
            }
        });

        Events.on(BlockDestroyEvent.class, e -> {
            if(player == null || e.tile.build == null) return;

            if(e.tile.build instanceof CoreBlock.CoreBuild){
                //enemy core destroyed
                if(e.tile.build.team != player.team()){
                    play("ann_enemy_base", "ra2ann-combat", COOLDOWN_LINE);
                }
            }else if(e.tile.build.team == player.team()){
                play("ann_structure_lost", "ra2ann-combat", COOLDOWN_STRUCTURE);
            }
        });

        Events.on(UnlockEvent.class, e -> play("ann_research", "ra2ann-tech", COOLDOWN_LINE));

        Events.on(WinEvent.class, e -> play("ann_victory", "ra2ann-tech", 0));
        Events.on(LoseEvent.class, e -> play("ann_defeat", "ra2ann-tech", 0));

        Events.on(SectorInvasionEvent.class, e -> play("ann_sector", "ra2ann-campaign", COOLDOWN_LINE));
        Events.on(SectorCaptureEvent.class, e -> play("ann_sector_captured", "ra2ann-campaign", COOLDOWN_LINE));

        //reset state when leaving the game
        Events.on(ResetEvent.class, e -> {
            lastPlayed.clear();
            waveWarned = false;
            coreCriticalReported = false;
            wasWaiting = false;
            lowPowerTicks = 0f;
            lastCoreHealth = -1f;
        });

        //periodic checks that have no dedicated event (or whose events do not fire on multiplayer clients)
        Events.run(Trigger.update, this::update);
    }

    private void update(){
        if(player == null || !state.isGame() || !timer.get(12f)) return;

        //pre-wave warning, 5 seconds before the next wave
        if(state.rules.waves && state.rules.waveTimer && !state.gameOver && !waveWarned
        && state.wavetime > 0f && state.wavetime <= 5f * 60f){
            waveWarned = true;
            play("ann_wave_warn", "ra2ann-wave", COOLDOWN_LINE);
        }

        //wave cleared: enemies all eliminated, timer resumed
        boolean waiting = logic.isWaitingWave();
        if(wasWaiting && !waiting && state.rules.waves){
            play("ann_wave_cleared", "ra2ann-wave", COOLDOWN_LINE);
        }
        wasWaiting = waiting;

        //core health monitoring: works in multiplayer too, unlike Trigger.teamCoreDamage
        var core = player.team().core();
        if(core != null){
            float hp = core.healthf();

            if(lastCoreHealth >= 0f && hp < lastCoreHealth - 0.02f){
                play("ann_core_attack", "ra2ann-base", COOLDOWN_CORE);
            }
            lastCoreHealth = hp;

            if(!coreCriticalReported && hp < 0.5f){
                coreCriticalReported = true;
                play("ann_core_critical", "ra2ann-base", COOLDOWN_LINE);
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
                play("ann_low_power", "ra2ann-base", COOLDOWN_LINE);
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
                        play("ann_guardian", "ra2ann-wave", COOLDOWN_LINE);
                    }
                    return;
                }
            }
        }
    }

    private void play(String name, String setting, long cooldown){
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
            t.checkPref("ra2ann-base", true);
            t.checkPref("ra2ann-combat", true);
            t.checkPref("ra2ann-tech", true);
            t.checkPref("ra2ann-campaign", true);
            t.button(Core.bundle.get("ra2ann.testline", "Test announcement"), () -> {
                var keys = sounds.keys().toSeq();
                if(keys.size > 0){
                    play(keys.random(), "ra2ann-wave", 0);
                }
            }).width(220f);
        });
    }
}
