package ra2;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

import static mindustry.Vars.player;

/**
 * 单位级事件的合并播报:
 * <ul>
 *   <li>生产完成({@code UnitCreateEvent}):同窗口内同型单位合并成一张卡片,并朗读该单位名;</li>
 *   <li>我方单位受袭({@code UnitDamageEvent}):按单位类型去重统计被击中的单位,
 *       合并成一张“受袭:名称×数量”卡片,再朗读受袭最重的那一型(采矿单位走专用台词)。</li>
 * </ul>
 * 伤害事件每帧都可能触发,所以这里先聚合再播报:这是"播报过于频繁"的主要治理点之一。
 */
final class UnitReports{
    /** production and damage windows are short: they only merge one burst, never delay the report */
    private static final float READY_WINDOW = 40f;
    private static final float ATTACK_WINDOW = 45f;
    private static final int MAX_LISTED = 3;

    private static final ObjectMap<String, Pending> ready = new ObjectMap<>();
    private static final ObjectMap<String, Pending> attacked = new ObjectMap<>();
    private static final ObjectMap<String, Long> readyAnnounced = new ObjectMap<>();
    private static final ObjectMap<String, Long> attackAnnounced = new ObjectMap<>();

    private static float readyWindowStart = -1f;
    private static float attackWindowStart = -1f;

    private UnitReports(){
    }

    static void reset(){
        ready.clear();
        attacked.clear();
        readyAnnounced.clear();
        attackAnnounced.clear();
        readyWindowStart = -1f;
        attackWindowStart = -1f;
    }

    /** A friendly unit rolled out of a factory. */
    static void onUnitReady(Unit unit, Building spawner){
        if(player == null || unit == null || unit.type == null || spawner == null) return;
        if(unit.team != player.team()) return;
        if(!(spawner.block instanceof mindustry.world.blocks.units.UnitBlock)) return;

        Pending pending = pendingFor(ready, unit.type, unit);
        if(!unit.isAdded()){
            //兵厂/升级器先 create() 再塞进 UnitPayload,此时单位还没进世界(x=y=0),
            //卡片与连线必须落在产出它的建筑上,否则会指向地图左下角。
            pending.x = spawner.x;
            pending.y = spawner.y;
        }
        pending.lastTick = Time.time;
        if(readyWindowStart < 0f) readyWindowStart = Time.time;
    }

    /** One of our units took a hit; aggregation keeps a bullet storm from becoming a bullet storm of voice lines. */
    static void onUnitDamaged(Unit unit, boolean friendlyFire){
        if(player == null || unit == null || unit.type == null || unit.dead || friendlyFire) return;
        if(unit.team != player.team()) return;
        if(!TypeFilters.attackedUnitAllowed(unit.type)) return;

        Pending pending = pendingFor(attacked, unit.type, unit);
        pending.units.add(unit.id);
        pending.lastTick = Time.time;
        if(attackWindowStart < 0f) attackWindowStart = Time.time;
    }

    static void flush(){
        flushReady();
        flushAttacked();
    }

    private static void flushReady(){
        if(ready.isEmpty()) return;
        if(readyWindowStart < 0f || Time.time - readyWindowStart < READY_WINDOW) return;
        readyWindowStart = -1f;

        Seq<Pending> list = sorted(ready);
        ready.clear();
        if(list.isEmpty()) return;
        if(!Core.settings.getBool("ra2ann-unit-ready", true)) return;

        Pending top = list.first();
        String message = Core.bundle.format("ra2ann.unit.ready.batch", Texts.list(labels(list), Math.max(0, list.size - MAX_LISTED)));
        EventFeedOverlay.show(message, top.x, top.y, top.icon, top.type.localizedName, "ra2ann-unit-ready-color");

        long cooldown = Math.max(3, Core.settings.getInt("ra2ann-ready-cooldown", 12)) * 1000L;
        if(!cooldownOk(readyAnnounced, top.type.name, cooldown)) return;
        Announcer.chain(Announcer.P_INFO, 0L, null, "ann_unit_ready", nameKey(top.type));
    }

    private static void flushAttacked(){
        if(attacked.isEmpty()) return;
        if(attackWindowStart < 0f || Time.time - attackWindowStart < ATTACK_WINDOW) return;
        attackWindowStart = -1f;

        Seq<Pending> list = sorted(attacked);
        attacked.clear();
        if(list.isEmpty()) return;

        Pending top = list.first();
        boolean miner = miner(top.type);
        boolean enabled = miner
            ? Core.settings.getBool("ra2ann-miner-attack", true)
            : Core.settings.getBool("ra2ann-unit-attack", true);

        if(enabled){
            String key = miner ? "ra2ann.miner.attack.batch" : "ra2ann.unit.attack.batch";
            String message = Core.bundle.format(key, Texts.list(labels(list), Math.max(0, list.size - MAX_LISTED)));
            EventFeedOverlay.show(message, top.x, top.y, top.icon, top.type.localizedName,
                miner ? "ra2ann-miner-color" : "ra2ann-attack-color");
        }

        if(!enabled) return;
        long cooldown = Math.max(3, Core.settings.getInt("ra2ann-attack-cooldown", 20)) * 1000L;
        if(!cooldownOk(attackAnnounced, top.type.name, cooldown)) return;
        Announcer.chain(Announcer.P_ALARM, 0L, null, miner ? "ann_miner_attack" : "ann_unit_attack", nameKey(top.type));
    }

    private static Pending pendingFor(ObjectMap<String, Pending> map, UnitType type, Unit unit){
        Pending pending = map.get(type.name);
        if(pending == null){
            pending = new Pending(type);
            map.put(type.name, pending);
        }
        pending.total++;
        pending.units.add(unit.id);
        pending.x = unit.x;
        pending.y = unit.y;
        if(pending.lastTick <= 0f) pending.lastTick = Time.time;
        return pending;
    }

    private static Seq<Pending> sorted(ObjectMap<String, Pending> map){
        Seq<Pending> list = new Seq<>();
        for(Pending pending : map.values()) list.add(pending);
        list.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return list;
    }

    private static Seq<String> labels(Seq<Pending> list){
        Seq<String> parts = new Seq<>();
        for(int i = 0; i < Math.min(list.size, MAX_LISTED); i++){
            Pending pending = list.get(i);
            parts.add(Texts.item(pending.type.localizedName, pending.count()));
        }
        return parts;
    }

    private static boolean cooldownOk(ObjectMap<String, Long> map, String key, long cooldown){
        long now = Time.millis();
        if(now - map.get(key, 0L) < cooldown) return false;
        map.put(key, now);
        if(map.size > 256) map.clear();
        return true;
    }

    static String nameKey(UnitType type){
        return type == null ? null : "name-unit-" + type.name;
    }

    static boolean miner(UnitType type){
        //mineFloor 对所有单位默认为 true(挖地板矿人人可做),不能用来判断矿工。
        //只有显式设置 mineTier > 0 的单位(mono/poly/mega 等)才是真正的采矿/支援单位。
        return type != null && type.mineTier > 0;
    }

    private static class Pending{
        final UnitType type;
        final ObjectSet<Integer> units = new ObjectSet<>();
        final TextureRegion icon;
        int total;
        float x, y;
        float lastTick = -1f;

        Pending(UnitType type){
            this.type = type;
            this.icon = type.uiIcon;
        }

        int count(){
            // distinct units when known (attacked reports), otherwise the raw event count (production)
            return units.size > 0 ? units.size : total;
        }
    }
}
