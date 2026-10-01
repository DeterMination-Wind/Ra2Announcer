package ra2;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.Element;
import arc.scene.event.ChangeListener;
import arc.scene.ui.Image;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Interval;
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
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.UnitSpawnEvent;
import mindustry.game.EventType.UnlockEvent;
import mindustry.game.EventType.WaveEvent;
import mindustry.game.EventType.WinEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.SpawnGroup;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.type.unit.MissileUnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.world.Block;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.mod.Mod;

import static mindustry.Vars.*;

/**
 * RA2 战场播报(索菲亚语音)。
 *
 * <p>由 {@code BattleVoice}(权重语音链、集结检测、单控标点、事件卡片)与
 * {@code Ra2Announcer}(高价值目标、自选单位、科技/战役/生产播报)合并而来,
 * 固定台词统一换成红警2原版副官索菲亚(Zofia)的录音,单位与建筑名沿用 TTS 名称包。</p>
 *
 * <p>三条设计约束:</p>
 * <ol>
 *   <li><b>限流</b>:所有语音都经 {@link Announcer} 的权重链,链与链之间强制静音间隔,
 *       高频事件(单位受袭、生产、损失、集结)先聚合再播报。</li>
 *   <li><b>详细类别</b>:被摧毁的建筑、受袭的单位、生产的单位都会朗读具体类型名,
 *       卡片文字同样带上“名称×数量”。</li>
 *   <li><b>客户端-only</b>:headless 直接返回;多人下只播客户端能看到的事件。</li>
 * </ol>
 */
public class Ra2Announcer extends Mod{

    /** Neon aggregation hook: keeps settings available when absorbed into a bundle. */
    public static boolean bekBundled = false;

    private static final long COOLDOWN_ALERT = 30_000L;
    private static final long COOLDOWN_DETECT = 30_000L;

    private static final String[] colorPresets = {
        "ef3d46", "b51f2a", "ff7e46", "ffb347", "ffd166", "a3e048", "4ce0c8",
        "64a0ff", "7d6bff", "b47fff", "ff7eb6", "e8e8e8", "9aa0a6", "33334d"
    };

    private final ObjectSet<PowerGraph> powerGraphs = new ObjectSet<>();
    private final ObjectMap<String, Long> detectedAt = new ObjectMap<>();
    private final ObjectMap<String, TextButton> colorSwatches = new ObjectMap<>();
    private final Interval timer = new Interval(3);

    private TextButton nameLangButton;

    private boolean waveWarned;
    private boolean coreCriticalReported;
    private boolean wasWaiting;
    private float lastCoreHealth = -1f;
    private float lowPowerTicks;
    private long lastCoreAttackAt = Long.MIN_VALUE;

    @Override
    public void init(){
        if(headless) return;

        Announcer.load();
        EventFeedOverlay.init();
        ControlWatch.register();
        registerEvents();
        if(!bekBundled){
            ui.settings.addCategory(Core.bundle.get("ra2ann.settings.name", "RA2 Announcer"), this::bekBuildSettings);
        }
    }

    /** Re-resolves every clip after the name-pack language changed. */
    public static void reloadVoicePack(){
        Announcer.reload();
    }

    //region events

    private void registerEvents(){
        Events.on(WaveEvent.class, e -> {
            waveWarned = false;
            checkGuardian();
            if(!Core.settings.getBool("ra2ann-wave", true)) return;
            announceAtCore(Core.bundle.format("ra2ann.wave.line", state.wave), 30_000L, "ann_wave", "ra2ann-wave-color", "ann_wave");
        });

        Events.on(WorldLoadEvent.class, e -> {
            resetRound();
            if(state.rules.editor) return;
            if(!Core.settings.getBool("ra2ann-base", true)) return;
            showAtCore("ra2ann.base.line", COOLDOWN_ALERT, "ann_base", "ra2ann-base-color", "ann_base");
        });

        //核心受击(主机/单人;多人客户端靠血量轮询)
        Events.run(Trigger.teamCoreDamage, this::playCoreAttack);

        //反应堆过热
        Events.run(Trigger.thoriumReactorOverheat, () -> {
            if(!Core.settings.getBool("ra2ann-base", true)) return;
            showAtCore("ra2ann.reactor.line", COOLDOWN_ALERT, "ann_reactor", "ra2ann-base-color", "ann_reactor");
        });

        Events.on(UnitDestroyEvent.class, e -> {
            if(e.unit == null) return;
            LossTracker.onUnitDestroy(e.unit);

            if(player == null || e.unit.team == player.team() || isMissile(e.unit) || e.unit.type == null) return;

            if(e.unit.isBoss() && Core.settings.getBool("ra2ann-boss", true)){
                String name = e.unit.type.localizedName;
                showCard(Core.bundle.format("ra2ann.boss.kill.line", name), e.unit.x, e.unit.y, e.unit.type.uiIcon, name, "ra2ann-attack-color");
                Announcer.chain(Announcer.P_ALARM, COOLDOWN_ALERT, "ann_boss_kill", "ann_boss_kill", nameKey(e.unit.type));
                return;
            }
            if(watchKill(e.unit)) return;
            if(highValueMatches(e.unit)){
                String name = e.unit.type.localizedName;
                showCard(Core.bundle.format("ra2ann.high.value.unit", name), e.unit.x, e.unit.y, e.unit.type.uiIcon, name, "ra2ann-high-value-color");
                Announcer.chain(Announcer.P_ALARM, 0L, null, "ann_watch_destroyed", nameKey(e.unit.type));
            }
        });

        //敌方单位出现:高价值目标 / 自选单位
        Events.on(UnitSpawnEvent.class, e -> detect(e.unit));
        Events.on(UnitCreateEvent.class, e -> {
            UnitReports.onUnitReady(e.unit, e.spawner);
            detect(e.unit, e.spawner);
        });

        //我方单位受袭:聚合后播报,并朗读具体单位名
        Events.on(UnitDamageEvent.class, e -> {
            if(e.unit == null) return;
            boolean friendlyFire = e.bullet != null && e.bullet.team == e.unit.team;
            UnitReports.onUnitDamaged(e.unit, friendlyFire);
        });

        //敌方高价值建筑落成
        Events.on(BlockBuildEndEvent.class, e -> {
            if(player == null || e.tile == null || e.tile.build == null || e.breaking) return;
            Building build = e.tile.build;
            if(build.team == player.team() || build.block == null) return;
            if(!highValueMatches(build)) return;
            if(!cooldownOk("block:" + build.block.name)) return;
            String name = build.block.localizedName;
            showCard(Core.bundle.format("ra2ann.high.value.block.detected", name), build.x, build.y, build.block.uiIcon, name, "ra2ann-high-value-color");
            Announcer.chain(Announcer.P_ALARM, 0L, null, "ann_high_value_block", blockKey(build.block));
        });

        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile == null || e.tile.build == null) return;
            Building build = e.tile.build;
            LossTracker.onBlockDestroy(build);
            if(player == null || build.team == player.team() || build.block == null) return;

            if(build instanceof CoreBlock.CoreBuild && Core.settings.getBool("ra2ann-enemy-base", true)){
                String name = build.block.localizedName;
                showCard(Core.bundle.format("ra2ann.enemy.base.line", name), e.tile.worldx(), e.tile.worldy(), build.block.uiIcon, name, "ra2ann-attack-color");
                Announcer.chain(Announcer.P_ALARM, COOLDOWN_ALERT, "ann_enemy_base", "ann_enemy_base", blockKey(build.block));
                return;
            }
            if(highValueMatches(build)){
                String name = build.block.localizedName;
                showCard(Core.bundle.format("ra2ann.high.value.block", name), e.tile.worldx(), e.tile.worldy(), build.block.uiIcon, name, "ra2ann-high-value-color");
                Announcer.chain(Announcer.P_ALARM, 0L, null, "ann_watch_destroyed", blockKey(build.block));
            }
        });

        //兵厂训练/取消:带具体单位名(ConfigEvent#tile 实际是 Building)
        Events.on(ConfigEvent.class, e -> {
            if(player == null || e.tile == null || e.tile.block == null) return;
            Building build = e.tile;
            if(build.team != player.team() || !(e.value instanceof Integer)) return;
            if(!(build.block instanceof UnitFactory factory)) return;
            if(!Core.settings.getBool("ra2ann-factory", true)) return;

            int plan = (Integer)e.value;
            UnitType unit = plan >= 0 && plan < factory.plans.size ? factory.plans.get(plan).unit : null;

            if(unit == null){
                String factoryName = build.block.localizedName;
                showCard(Core.bundle.format("ra2ann.cancel.line", factoryName), build.x, build.y, build.block.uiIcon, factoryName, "ra2ann-factory-color");
                Announcer.play(Announcer.P_INFO, 6_000L, "ann_cancel", "ann_cancel");
                return;
            }
            String name = unit.localizedName;
            showCard(Core.bundle.format("ra2ann.training.line", name), build.x, build.y, unit.uiIcon, name, "ra2ann-factory-color");
            Announcer.chain(Announcer.P_INFO, 6_000L, "ann_training", "ann_training", nameKey(unit));
        });

        Events.on(UnlockEvent.class, e -> {
            if(!Core.settings.getBool("ra2ann-research", true)) return;
            String name = e.content == null ? Core.bundle.get("ra2ann.unknown.target", "?") : e.content.localizedName;
            announceAtCore(Core.bundle.format("ra2ann.research.line", name), COOLDOWN_ALERT, "ann_research", "ra2ann-info-color", "ann_research");
        });

        Events.on(WinEvent.class, e -> {
            if(!Core.settings.getBool("ra2ann-outcome", true)) return;
            showAtCore("ra2ann.victory.line", 0L, "ann_victory", "ra2ann-info-color", "ann_victory");
        });
        Events.on(LoseEvent.class, e -> {
            if(!Core.settings.getBool("ra2ann-outcome", true)) return;
            showAtCore("ra2ann.defeat.line", 0L, "ann_defeat", "ra2ann-info-color", "ann_defeat");
        });

        Events.on(SectorInvasionEvent.class, e -> {
            if(!Core.settings.getBool("ra2ann-campaign", true)) return;
            String name = sectorName(e.sector);
            showCard(Core.bundle.format("ra2ann.sector.line", name), Float.NaN, Float.NaN, null, name, "ra2ann-info-color");
            Announcer.play(Announcer.P_ALARM, COOLDOWN_ALERT, "ann_sector", "ann_sector");
        });
        Events.on(SectorCaptureEvent.class, e -> {
            if(!Core.settings.getBool("ra2ann-campaign", true)) return;
            String name = sectorName(e.sector);
            showCard(Core.bundle.format("ra2ann.sector.captured.line", name), Float.NaN, Float.NaN, null, name, "ra2ann-info-color");
            Announcer.play(Announcer.P_ALARM, COOLDOWN_ALERT, "ann_sector_captured", "ann_sector_captured");
        });

        Events.on(ResetEvent.class, e -> resetRound());

        Events.run(Trigger.update, this::update);
    }

    private void update(){
        Announcer.update();
        if(player == null || !state.isGame()) return;
        if(!timer.get(12f)) return;

        LossTracker.flush();
        UnitReports.flush();
        ThreatScanner.update();

        //下一波前 5 秒预警
        if(state.rules.waves && state.rules.waveTimer && !state.gameOver && !waveWarned
        && state.wavetime > 0f && state.wavetime <= 5f * 60f){
            waveWarned = true;
            if(Core.settings.getBool("ra2ann-wave", true)){
                announceAtCore(Core.bundle.format("ra2ann.wave.warn.line", state.wave + 1), COOLDOWN_ALERT, nextWaveLine(), "ra2ann-wave-color", "ann_wave_warn");
            }
        }

        //波次清空
        boolean waiting = logic.isWaitingWave();
        if(wasWaiting && !waiting && state.rules.waves && Core.settings.getBool("ra2ann-wave", true)){
            announceAtCore(Core.bundle.format("ra2ann.wave.cleared.line", state.wave), COOLDOWN_ALERT, "ann_wave_cleared", "ra2ann-wave-color", "ann_wave_cleared");
        }
        wasWaiting = waiting;

        //核心血量监测:多人客户端也能触发,不像 Trigger.teamCoreDamage
        var core = player.team().core();
        if(core != null){
            float hp = core.healthf();
            if(lastCoreHealth >= 0f && hp < lastCoreHealth - 0.02f){
                playCoreAttack();
            }
            lastCoreHealth = hp;

            if(!coreCriticalReported && hp < 0.5f){
                coreCriticalReported = true;
                if(Core.settings.getBool("ra2ann-base", true)){
                    showAtCore("ra2ann.core.critical.line", COOLDOWN_ALERT, "ann_core_critical", "ra2ann-base-color", "ann_core_critical");
                }
            }else if(coreCriticalReported && hp > 0.6f){
                coreCriticalReported = false;
            }
        }else{
            lastCoreHealth = -1f;
            coreCriticalReported = false;
        }

        //电力不足:持续缺口(仅主机/单人,电力只在本地模拟)
        if(Core.settings.getBool("ra2ann-base", true)){
            float deficit = 0f;
            var data = player.team().data();
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
                    showAtCore("ra2ann.low.power.line", COOLDOWN_ALERT, "ann_low_power", "ra2ann-base-color", "ann_low_power");
                }
            }else{
                lowPowerTicks = 0f;
            }
        }
    }

    private void playCoreAttack(){
        if(!Core.settings.getBool("ra2ann-base", true) || !Core.settings.getBool("ra2ann-core-damage", true)) return;
        long cooldown = Math.max(5, Core.settings.getInt("ra2ann-core-damage-cooldown", 60)) * 1000L;
        long now = Time.millis();
        if(now - lastCoreAttackAt < cooldown) return;
        lastCoreAttackAt = now;
        showAtCore("ra2ann.core.attack.line", 0L, "ann_core_attack", "ra2ann-base-color", "ann_core_attack");
    }

    /** 首领波预警:按首领是空中还是地面单位选“空军来袭/装甲营来袭”,再接单位名。 */
    private void checkGuardian(){
        if(!Core.settings.getBool("ra2ann-boss", true)) return;
        int max = 10;
        int winWave = state.rules.winWave > 0 ? state.rules.winWave : Integer.MAX_VALUE;

        for(int i = state.wave - 1; i <= Math.min(state.wave + max, winWave - 2); i++){
            for(SpawnGroup group : state.rules.spawns){
                if(group.effect == StatusEffects.boss && group.getSpawned(i) > 0){
                    if((i + 2) - state.wave == 1){
                        UnitType type = group.type;
                        String line = type != null && type.flying ? "ann_force_air" : "ann_boss";
                        String name = type == null ? Core.bundle.get("ra2ann.unknown.target", "?") : type.localizedName;
                        announceAtCore(Core.bundle.format("ra2ann.boss.line", name), COOLDOWN_ALERT, line,
                            "ra2ann-attack-color", "ann_boss", nameKey(type));
                    }
                    return;
                }
            }
        }
    }

    private void resetRound(){
        Announcer.reset();
        ThreatScanner.reset();
        ControlWatch.reset();
        LossTracker.reset();
        UnitReports.reset();
        detectedAt.clear();
        waveWarned = false;
        coreCriticalReported = false;
        wasWaiting = false;
        lowPowerTicks = 0f;
        lastCoreHealth = -1f;
        lastCoreAttackAt = Long.MIN_VALUE;
        EventFeedOverlay.clear();
    }

    //endregion

    //region detection (high value + watch list)

    private void detect(Unit unit){
        detect(unit, null);
    }

    private void detect(Unit unit, Building spawner){
        if(player == null || unit == null || unit.type == null || unit.team == player.team() || isMissile(unit)) return;
        boolean inWorld = unit.isAdded();
        //兵厂/升级器产出的单位在 payload 里就触发 UnitCreateEvent(x=y=0),用产出的建筑兜底定位。
        float x = !inWorld && spawner != null ? spawner.x : unit.x;
        float y = !inWorld && spawner != null ? spawner.y : unit.y;
        if(watchDetected(unit, x, y)) return;
        if(!highValueMatches(unit)) return;
        if(!cooldownOk("unit:" + unit.type.name)) return;

        String name = unit.type.localizedName;
        showCard(Core.bundle.format("ra2ann.high.value.unit.detected", name), x, y, unit.type.uiIcon, name, "ra2ann-high-value-color");
        Announcer.chain(Announcer.P_ALARM, 0L, null, categoryLine(unit.type), nameKey(unit.type));
    }

    /** @return true when the unit is on the watch list and the detection was handled. */
    private boolean watchDetected(Unit unit, float x, float y){
        if(!watchMatches(unit.type)) return false;
        if(!cooldownOk("watch:" + unit.type.name)) return true;

        String name = unit.type.localizedName;
        showCard(Core.bundle.format("ra2ann.watch.unit.detected", name), x, y, unit.type.uiIcon, name, "ra2ann-high-value-color");
        Announcer.chain(Announcer.P_ALARM, 0L, null, categoryLine(unit.type), nameKey(unit.type));
        return true;
    }

    /** @return true when the destroyed unit was on the watch list. */
    private boolean watchKill(Unit unit){
        if(!watchMatches(unit.type)) return false;
        if(!cooldownOk("watchkill:" + unit.type.name)) return true;

        String name = unit.type.localizedName;
        showCard(Core.bundle.format("ra2ann.watch.unit.destroyed", name), unit.x, unit.y, unit.type.uiIcon, name, "ra2ann-high-value-color");
        Announcer.chain(Announcer.P_ALARM, COOLDOWN_ALERT, "ann_watch_destroyed", "ann_watch_destroyed", nameKey(unit.type));
        return true;
    }

    private boolean cooldownOk(String key){
        long now = Time.millis();
        if(now - detectedAt.get(key, 0L) < COOLDOWN_DETECT) return false;
        detectedAt.put(key, now);
        if(detectedAt.size > 256) detectedAt.clear();
        return true;
    }

    /** Matches a unit against the player-configured watch list (internal or localized names). */
    private boolean watchMatches(UnitType type){
        if(type == null || !Core.settings.getBool("ra2ann-watch", true)) return false;
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

    private boolean highValueMatches(Unit unit){
        if(!Core.settings.getBool("ra2ann-high-value", true)) return false;
        if(unit == null || unit.type == null) return false;
        for(String item : highValueRules()){
            String rule = item.trim().toLowerCase();
            if(rule.equals("boss") && unit.isBoss()) return true;
            if(rule.equals("t5") && isT5(unit.type)) return true;
            if(rule.equals("unit:" + unit.type.name.toLowerCase())) return true;
        }
        return false;
    }

    private boolean highValueMatches(Building build){
        if(!Core.settings.getBool("ra2ann-high-value", true)) return false;
        if(build == null || build.block == null) return false;
        String name = build.block.name.toLowerCase();
        for(String item : highValueRules()){
            String rule = item.trim().toLowerCase();
            if(rule.equals("core") && build instanceof CoreBlock.CoreBuild) return true;
            if(rule.equals("block:" + name)) return true;
        }
        return false;
    }

    private String[] highValueRules(){
        String configured = Core.settings.getString("ra2ann-high-value-targets", "core,boss,t5");
        return configured == null ? new String[0] : configured.split(",");
    }

    private static boolean isT5(UnitType type){
        String name = type.name.toLowerCase();
        return name.equals("omura") || name.equals("reign") || name.equals("toxopid")
            || name.equals("eclipse") || name.equals("oct") || name.equals("corvus");
    }

    //endregion

    //region announcement plumbing

    static boolean forceEnabled(){
        return Announcer.enabled() && Core.settings.getBool("ra2ann-force", true);
    }

    static boolean isMissile(Unit unit){
        return unit != null && (unit.isMissile() || unit.type instanceof MissileUnitType);
    }

    static String nameKey(UnitType type){
        return type == null ? null : "name-unit-" + type.name;
    }

    static String blockKey(Block block){
        return block == null ? null : "name-block-" + block.name;
    }

    /**
     * Picks the RA2 category line for a unit: the original advisor announces infantry / armor /
     * air / naval separately, which is what gives every report its "详细类别".
     */
    static String categoryLine(UnitType type){
        if(type == null) return "ann_force_armor";
        if(type.naval) return "ann_force_naval";
        if(type.flying) return "ann_force_air";
        return type.health >= 1000f ? "ann_force_armor" : "ann_force_infantry";
    }

    /**
     * Category line for the wave that is about to start, taken from the actual spawn composition.
     * 语音不要求准确,但也不该说反 —— 空中波次不该播“装甲营”。准确信息仍在卡片(波次号)里。
     */
    private String nextWaveLine(){
        int ground = 0, air = 0, naval = 0;
        for(SpawnGroup group : state.rules.spawns){
            if(group.type == null) continue;
            int count = group.getSpawned(state.wave); // 下一个波次的 0 基索引就是当前波号
            if(count <= 0) continue;
            if(group.type.naval) naval += count;
            else if(group.type.flying) air += count;
            else ground += count;
        }
        if(naval > ground && naval >= air) return "ann_force_naval";
        if(air > ground && air > naval) return "ann_force_air";
        return ground > 0 ? "ann_force_armor" : "ann_wave_warn";
    }

    private String sectorName(mindustry.type.Sector sector){
        try{
            String name = sector == null ? null : sector.name();
            return name == null || name.isEmpty() ? Core.bundle.get("ra2ann.unknown.sector", "?") : name;
        }catch(Throwable ignored){
            return Core.bundle.get("ra2ann.unknown.sector", "?");
        }
    }

    private void showAtCore(String messageKey, long cooldown, String sound, String colorKey, String cooldownKey){
        announceAtCore(Core.bundle.get(messageKey, sound), cooldown, sound, colorKey, cooldownKey);
    }

    /**
     * 卡片文字 + 语音一起播报:卡片负责“准确播报”(具体名称、数量、波及对象),
     * 语音只播索菲亚的固定台词,名称片段由 {@code ra2ann-voice-names} 决定是否附上。
     */
    private void announceAtCore(String message, long cooldown, String sound, String colorKey, String cooldownKey, String... nameClips){
        float x = Float.NaN, y = Float.NaN;
        if(player != null && player.team() != null && player.team().core() != null){
            x = player.team().core().x;
            y = player.team().core().y;
        }
        announceAt(message, x, y, cooldown, sound, colorKey, cooldownKey, nameClips);
    }

    private void announceAt(String message, float x, float y, long cooldown, String sound, String colorKey, String cooldownKey, String... nameClips){
        showCard(message, x, y, null, null, colorKey);
        Seq<String> keys = new Seq<>();
        keys.add(sound);
        for(String clip : nameClips){
            if(clip != null) keys.add(clip);
        }
        Announcer.chain(Announcer.P_ALARM, cooldown, cooldownKey, keys.toArray(String.class));
    }

    private void showCard(String message, float x, float y, TextureRegion icon, String markerText, String colorKey){
        if(!Announcer.enabled() || !state.isGame()) return;
        EventFeedOverlay.show(message, x, y, icon, markerText, colorKey);
    }

    //endregion

    //region settings

    /** Neon aggregation contract: the settings page is built here so Neon can absorb it. */
    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table){
        table.checkPref("ra2ann-enabled", true);

        rowTitle(table, "ra2ann.category.voice");
        nameLangRow(table);
        table.checkPref("ra2ann-voice-names", false);
        table.sliderPref("ra2ann-voice-volume", 100, 0, 100, 5, value -> value + "%");
        table.sliderPref("ra2ann-voice-gap", 2, 0, 8, 1, value -> Core.bundle.format("ra2ann.seconds", value));

        rowTitle(table, "ra2ann.category.announcements");
        table.checkPref("ra2ann-wave", true);
        table.checkPref("ra2ann-base", true);
        table.checkPref("ra2ann-core-damage", true);
        table.sliderPref("ra2ann-core-damage-cooldown", 60, 5, 300, 5, value -> Core.bundle.format("ra2ann.seconds", value));
        table.checkPref("ra2ann-loss-units", true);
        table.checkPref("ra2ann-loss-blocks", true);
        table.sliderPref("ra2ann-loss-cooldown", 15, 3, 120, 3, value -> Core.bundle.format("ra2ann.seconds", value));
        table.sliderPref("ra2ann-loss-block-cooldown", 45, 5, 300, 5, value -> Core.bundle.format("ra2ann.seconds", value));
        table.checkPref("ra2ann-unit-attack", true);
        table.checkPref("ra2ann-miner-attack", true);
        table.sliderPref("ra2ann-attack-cooldown", 20, 3, 120, 1, value -> Core.bundle.format("ra2ann.seconds", value));
        table.checkPref("ra2ann-unit-ready", true);
        table.sliderPref("ra2ann-ready-cooldown", 12, 3, 120, 1, value -> Core.bundle.format("ra2ann.seconds", value));
        table.checkPref("ra2ann-factory", true);
        table.checkPref("ra2ann-enemy-base", true);
        table.checkPref("ra2ann-boss", true);
        table.checkPref("ra2ann-force", true);
        table.sliderPref("ra2ann-force-min", 3, 1, 20, 1, value -> Integer.toString(value));
        table.sliderPref("ra2ann-force-cooldown", 30, 5, 180, 5, value -> Core.bundle.format("ra2ann.seconds", value));
        table.sliderPref("ra2ann-core-radius", 40, 10, 120, 5, value -> value + "t");
        table.checkPref("ra2ann-control-enemy", true);
        table.checkPref("ra2ann-control-friendly", true);
        table.checkPref("ra2ann-building-command", true);
        table.sliderPref("ra2ann-control-cooldown", 15, 3, 120, 3, value -> Core.bundle.format("ra2ann.seconds", value));
        table.checkPref("ra2ann-research", true);
        table.checkPref("ra2ann-campaign", true);
        table.checkPref("ra2ann-outcome", true);

        rowTitle(table, "ra2ann.category.targets");
        table.checkPref("ra2ann-high-value", true);
        table.textPref("ra2ann-high-value-targets", "core,boss,t5");
        table.checkPref("ra2ann-watch", true);
        table.textPref("ra2ann-watch-units", "");
        TypeFilters.addFilterButtons(table);

        rowTitle(table, "ra2ann.category.ui");
        table.checkPref("ra2ann-ui-enabled", true);
        table.checkPref("ra2ann-marker-enabled", true);
        table.checkPref("ra2ann-line-enabled", true);
        table.checkPref("ra2ann-line-solid", true);
        table.sliderPref("ra2ann-toast-mode", 1, 0, 2, 1, this::toastModeLabel);
        table.sliderPref("ra2ann-toast-duration", 5, 2, 12, 1, value -> Core.bundle.format("ra2ann.seconds", value));
        table.sliderPref("ra2ann-ui-width", 320, 240, 560, 20, value -> value + "px");
        table.sliderPref("ra2ann-ui-scale", 100, 80, 160, 5, value -> value + "%");
        table.sliderPref("ra2ann-ui-opacity", 85, 30, 100, 5, value -> value + "%");
        table.sliderPref("ra2ann-ui-duration", 4, 1, 12, 1, value -> Core.bundle.format("ra2ann.seconds", value));
        table.sliderPref("ra2ann-marker-duration", 8, 2, 20, 1, value -> Core.bundle.format("ra2ann.seconds", value));
        table.sliderPref("ra2ann-ui-max-entries", 6, 3, 10, 1, value -> Integer.toString(value));
        table.sliderPref("ra2ann-ui-spacing", 4, 0, 12, 1, value -> value + "px");
        table.sliderPref("ra2ann-ui-offset-x", 12, 0, 1000, 4, value -> value + "px");
        table.sliderPref("ra2ann-ui-offset-y", 72, 0, 600, 4, value -> value + "px");
        table.sliderPref("ra2ann-line-width", 3, 1, 6, 1, value -> value + "px");
        table.sliderPref("ra2ann-line-alpha", 95, 35, 100, 5, value -> value + "%");

        rowTitle(table, "ra2ann.category.colors");
        colorRow(table, "ra2ann-card-color", "b51f2a");
        colorRow(table, "ra2ann-accent-color", "ef3d46");
        colorRow(table, "ra2ann-wave-color", "ffd166");
        colorRow(table, "ra2ann-base-color", "ef3d46");
        colorRow(table, "ra2ann-attack-color", "ff5a3c");
        colorRow(table, "ra2ann-core-threat-color", "ff2a2a");
        colorRow(table, "ra2ann-control-color", "ff7e46");
        colorRow(table, "ra2ann-control-friendly-color", "4ce0c8");
        colorRow(table, "ra2ann-loss-unit-color", "b51f2a");
        colorRow(table, "ra2ann-loss-block-color", "9aa0a6");
        colorRow(table, "ra2ann-high-value-color", "ffb347");
        colorRow(table, "ra2ann-factory-color", "64a0ff");
        colorRow(table, "ra2ann-unit-ready-color", "64a0ff");
        colorRow(table, "ra2ann-miner-color", "ffd166");
        colorRow(table, "ra2ann-info-color", "64a0ff");

        table.button(Core.bundle.get("ra2ann.test", "Test announcement"), () -> {
            String[] keys = Announcer.FIXED_LINES;
            String key = keys[(int)(Math.random() * keys.length)];
            showCard(Core.bundle.get("ra2ann.test.line", key), Float.NaN, Float.NaN, null, null, null);
            Announcer.play(Announcer.P_INFO, 0L, null, key);
        }).width(220f).padTop(8f).row();
    }

    /** Name-pack language toggle: 中文/English unit & building names (RA2 lines are shared). */
    private void nameLangRow(SettingsMenuDialog.SettingsTable table){
        nameLangButton = table.button("", Styles.flatTogglet, () -> {
            Core.settings.put("ra2ann-name-lang", "en".equals(Announcer.nameLang()) ? "zh" : "en");
            reloadVoicePack();
            refreshNameLangButton();
        }).width(320f).height(45f).padTop(7f).get();
        refreshNameLangButton();
        table.row();
    }

    private void refreshNameLangButton(){
        if(nameLangButton == null) return;
        String current = Core.bundle.get("en".equals(Announcer.nameLang()) ? "ra2ann.lang.en" : "ra2ann.lang.zh", "?");
        nameLangButton.setText(Core.bundle.format("ra2ann.lang.current", current));
    }

    /** 原版提示框模式:0 关闭 / 1 卡片关闭时兜底 / 2 每次播报都弹。 */
    private String toastModeLabel(int value){
        if(value <= 0) return Core.bundle.get("ra2ann.toast.off", "off");
        return Core.bundle.get(value == 1 ? "ra2ann.toast.fallback" : "ra2ann.toast.always", "?");
    }

    private void rowTitle(Table table, String bundleKey){
        table.add(Core.bundle.get(bundleKey, bundleKey)).colspan(2).growX().left().padTop(12f).padBottom(4f)
            .color(Color.valueOf("ffd166")).row();
    }

    private void colorRow(Table table, String colorKey, String colorDefault){
        Table row = new Table();
        row.add(Core.bundle.get("setting." + colorKey + ".name", colorKey)).growX().height(45f).left().padLeft(10f);
        String value = Core.settings.getString(colorKey, colorDefault);
        TextButton swatch = row.button(value, Styles.flatTogglet, () -> showColorEditor(colorKey, colorDefault))
            .width(112f).height(45f).padLeft(6f).get();
        swatch.getLabel().setColor(parseHexOr(value, colorDefault));
        colorSwatches.put(colorKey, swatch);
        table.add(row).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f)))
            .fillX().height(45f).left().padTop(7f);
        table.row();
    }

    private void showColorEditor(String key, String colorDefault){
        String current = Core.settings.getString(key, colorDefault);
        BaseDialog dialog = new BaseDialog(Core.bundle.get("ra2ann.color.title", "Color"));
        dialog.cont.margin(16f);

        Table top = new Table();
        Image preview = new Image(Tex.whiteui);
        preview.setColor(parseHexOr(current, colorDefault));
        TextField field = new TextField(current == null ? "" : current, Styles.defaultField);
        field.setMessageText("ef3d46");
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

    //endregion
}
