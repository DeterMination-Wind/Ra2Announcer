package ra2;

import arc.Core;
import arc.math.Mathf;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

import java.util.Comparator;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * 敌方大规模控兵/集结检测(自 BattleVoice 合并而来)。
 * 带 CommandAI 且持有同步终点(attackTarget / targetPos)的敌方单位按终点聚类:
 * 终点落在我方核心半径内就升级为红色“快速接近核心”警报。
 * 语音按主力的兵种选台词(步兵/装甲/空军/舰队),再接上该单位名。
 */
final class ThreatScanner{
    private static final float SCAN_INTERVAL = 12f;
    private static final float WINDOW_TICKS = 24f;
    private static final float BUCKET = 8f * tilesize;
    /** a single unit this "expensive" already qualifies as a core threat even below the min count */
    private static final float CORE_THREAT_SCORE = 20_000f;
    /** how many different unit types the card text lists before it appends "N more types" */
    private static final int MAX_LISTED = 3;

    private static final ObjectMap<String, Cluster> clusters = new ObjectMap<>();
    private static final ObjectMap<String, Long> announcedAt = new ObjectMap<>();
    /** unit ids already counted in the current window: the window scans the same unit several times */
    private static final IntSet seen = new IntSet();
    private static final Interval timer = new Interval(1);

    private static float windowStart = -1f;
    private static long lastCoreThreatAt = 0L;

    private ThreatScanner(){
    }

    static void reset(){
        clusters.clear();
        seen.clear();
        announcedAt.clear();
        windowStart = -1f;
        lastCoreThreatAt = 0L;
    }

    static void update(){
        if(player == null || !state.isGame() || state.isMenu()) return;
        if(!timer.get(SCAN_INTERVAL)) return;

        for(Unit unit : Groups.unit){
            if(unit.team == player.team() || Ra2Announcer.isMissile(unit)) continue;
            if(!(unit.controller() instanceof CommandAI ai)) continue;

            float dx, dy;
            if(ai.attackTarget != null){
                dx = ai.attackTarget.getX();
                dy = ai.attackTarget.getY();
            }else if(ai.targetPos != null){
                dx = ai.targetPos.x;
                dy = ai.targetPos.y;
            }else{
                continue;
            }
            if(unit.type == null || unit.type.estimateDps() <= 0f) continue;
            //同一窗口每 12 tick 扫一遍,同一单位会被重复计入;只有通过全部过滤后才标记为已见,
            //否则这一帧还没有终点的单位会被永久漏掉。
            if(!seen.add(unit.id)) continue;

            String key = unit.team.id + ":" + Math.round(dx / BUCKET) + ":" + Math.round(dy / BUCKET);
            Cluster cluster = clusters.get(key);
            if(cluster == null){
                cluster = new Cluster(unit.team.id, dx, dy);
                clusters.put(key, cluster);
            }
            cluster.units.add(new ThreatUnit(unit.id, unit.type));
            if(windowStart < 0f) windowStart = Time.time;
        }

        flush();
    }

    private static void flush(){
        if(clusters.isEmpty()) return;
        if(windowStart < 0f || Time.time - windowStart < WINDOW_TICKS) return;
        windowStart = -1f;

        var core = player.team().core();
        float coreRadius = Math.max(1, Core.settings.getInt("ra2ann-core-radius", 40)) * tilesize;
        int minCount = Math.max(1, Core.settings.getInt("ra2ann-force-min", 3));
        long cooldown = Math.max(5, Core.settings.getInt("ra2ann-force-cooldown", 30)) * 1000L;
        long now = Time.millis();
        boolean mapped = Groups.unit.mappingEnabled();

        for(ObjectMap.Entry<String, Cluster> entry : clusters){
            Cluster cluster = entry.value;
            if(cluster.units.isEmpty()) continue;

            //扫描后到 flush 之间单位可能已阵亡/消失/换型:只统计仍然在场的单位,并重新算质心。
            ObjectMap<UnitType, Integer> counts = new ObjectMap<>();
            float cx = 0f, cy = 0f, bestThreat = 0f;
            int total = 0;
            for(ThreatUnit threatUnit : cluster.units){
                Unit live = mapped ? Groups.unit.getByID(threatUnit.id) : null;
                if(live == null || live.dead || live.type != threatUnit.type) continue;
                total++;
                counts.put(live.type, counts.get(live.type, 0) + 1);
                cx += live.x;
                cy += live.y;
                bestThreat = Math.max(bestThreat, quality(live.type));
            }
            if(total == 0) continue;

            Seq<TypeCount> ranked = new Seq<>(counts.size);
            for(ObjectMap.Entry<UnitType, Integer> countEntry : counts){
                ranked.add(new TypeCount(countEntry.key, countEntry.value, quality(countEntry.key)));
            }
            //数量降序 → 威胁降序 → 名称升序,保证同一局面每次播报的顺序一致
            ranked.sort((Comparator<TypeCount>)(a, b) -> {
                if(a.count != b.count) return Integer.compare(b.count, a.count);
                if(a.quality != b.quality) return Float.compare(b.quality, a.quality);
                return a.type.name.compareTo(b.type.name);
            });
            UnitType dominant = ranked.first().type;

            boolean towardCore = core != null
                && (Mathf.dst(cluster.dx, cluster.dy, core.x, core.y) <= coreRadius
                || (bestThreat >= CORE_THREAT_SCORE && Mathf.dst(cx / total, cy / total, core.x, core.y) <= coreRadius * 2f));

            if(!Ra2Announcer.forceEnabled()) continue;
            if(!TypeFilters.attackUnitAllowed(dominant)) continue;

            if(now - announcedAt.get(cluster.team + ":" + dominant.name, 0L) < cooldown) continue;

            //混合兵种时按“名字×数量”依次列出,不再用主力兵种的名字配全体总数
            Seq<String> parts = new Seq<>();
            for(int i = 0; i < Math.min(ranked.size, MAX_LISTED); i++){
                TypeCount typeCount = ranked.get(i);
                parts.add(Texts.item(typeCount.type.localizedName, typeCount.count));
            }
            String item = Texts.list(parts, Math.max(0, ranked.size - MAX_LISTED));

            if(towardCore){
                if(now - lastCoreThreatAt < cooldown) continue;
                lastCoreThreatAt = now;
                announcedAt.put(cluster.team + ":" + dominant.name, now);
                String message = Core.bundle.format("ra2ann.core.threat.line", item);
                EventFeedOverlay.show(message, cx / total, cy / total, dominant.uiIcon, dominant.localizedName, "ra2ann-core-threat-color");
                Announcer.chain(Announcer.P_ATTACK, 0L, null,
                    Ra2Announcer.categoryLine(dominant), "name-unit-" + dominant.name, "ann_core_threat");
            }else{
                if(total < minCount) continue;
                announcedAt.put(cluster.team + ":" + dominant.name, now);
                String message = Core.bundle.format("ra2ann.enemy.force.line", item);
                EventFeedOverlay.show(message, cx / total, cy / total, dominant.uiIcon, dominant.localizedName, "ra2ann-attack-color");
                Announcer.chain(Announcer.P_ATTACK, 0L, null,
                    Ra2Announcer.categoryLine(dominant), "name-unit-" + dominant.name);
            }
        }
        clusters.clear();
        seen.clear();
    }

    private static float quality(UnitType type){
        return type.estimateDps() * type.health;
    }

    private static class Cluster{
        final int team;
        final float dx;
        final float dy;
        final Seq<ThreatUnit> units = new Seq<>();

        Cluster(int team, float dx, float dy){
            this.team = team;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static class ThreatUnit{
        final int id;
        final UnitType type;

        ThreatUnit(int id, UnitType type){
            this.id = id;
            this.type = type;
        }
    }

    private static class TypeCount{
        final UnitType type;
        final int count;
        final float quality;

        TypeCount(UnitType type, int count, float quality){
            this.type = type;
            this.count = count;
            this.quality = quality;
        }
    }
}
