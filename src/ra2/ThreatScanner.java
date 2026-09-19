package ra2;

import arc.Core;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Time;
import mindustry.ai.types.CommandAI;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

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

    private static final ObjectMap<String, Cluster> clusters = new ObjectMap<>();
    private static final ObjectMap<String, Long> announcedAt = new ObjectMap<>();
    private static final Interval timer = new Interval(1);

    private static float windowStart = -1f;
    private static long lastCoreThreatAt = Long.MIN_VALUE;

    private ThreatScanner(){
    }

    static void reset(){
        clusters.clear();
        announcedAt.clear();
        windowStart = -1f;
        lastCoreThreatAt = Long.MIN_VALUE;
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

            String key = unit.team.id + ":" + Math.round(dx / BUCKET) + ":" + Math.round(dy / BUCKET);
            Cluster cluster = clusters.get(key);
            if(cluster == null){
                cluster = new Cluster(unit.team.id, dx, dy);
                clusters.put(key, cluster);
            }
            cluster.units.add(new ThreatUnit(unit.type, unit.x, unit.y));
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

        for(ObjectMap.Entry<String, Cluster> entry : clusters){
            Cluster cluster = entry.value;
            if(cluster.units.isEmpty()) continue;

            int total = cluster.units.size;
            ObjectMap<UnitType, Integer> counts = new ObjectMap<>();
            float cx = 0f, cy = 0f, bestThreat = 0f;
            UnitType threat = null;
            for(ThreatUnit threatUnit : cluster.units){
                counts.put(threatUnit.type, counts.get(threatUnit.type, 0) + 1);
                cx += threatUnit.x;
                cy += threatUnit.y;
                float score = quality(threatUnit.type);
                if(score > bestThreat){
                    bestThreat = score;
                    threat = threatUnit.type;
                }
            }

            UnitType dominant = null;
            int maxCount = 0;
            float dominantThreat = -1f;
            for(ObjectMap.Entry<UnitType, Integer> countEntry : counts){
                float score = quality(countEntry.key);
                if(countEntry.value > maxCount || (countEntry.value == maxCount && score > dominantThreat)){
                    dominant = countEntry.key;
                    maxCount = countEntry.value;
                    dominantThreat = score;
                }
            }
            if(dominant == null) continue;

            boolean towardCore = core != null
                && (Mathf.dst(cluster.dx, cluster.dy, core.x, core.y) <= coreRadius
                || (bestThreat >= CORE_THREAT_SCORE && Mathf.dst(cx / total, cy / total, core.x, core.y) <= coreRadius * 2f));

            if(!Ra2Announcer.forceEnabled()) continue;
            if(!TypeFilters.attackUnitAllowed(dominant)) continue;

            if(now - announcedAt.get(cluster.team + ":" + dominant.name, 0L) < cooldown) continue;

            if(towardCore){
                if(now - lastCoreThreatAt < cooldown) continue;
                lastCoreThreatAt = now;
                announcedAt.put(cluster.team + ":" + dominant.name, now);
                String item = Core.bundle.format("ra2ann.enemy.force.item", dominant.localizedName, total);
                String message = Core.bundle.format("ra2ann.core.threat.line", item);
                EventFeedOverlay.show(message, cx / total, cy / total, dominant.uiIcon, dominant.localizedName, "ra2ann-core-threat-color");
                Announcer.chain(Announcer.P_ATTACK, 0L, null,
                    Ra2Announcer.categoryLine(dominant), "name-unit-" + dominant.name, "ann_core_threat");
            }else{
                if(total < minCount) continue;
                announcedAt.put(cluster.team + ":" + dominant.name, now);
                String item = Core.bundle.format("ra2ann.enemy.force.item", dominant.localizedName, maxCount);
                if(counts.size >= 2 && threat != null && threat != dominant){
                    item += Core.bundle.get("ra2ann.list.separator", ", ")
                        + Core.bundle.format("ra2ann.enemy.force.threat", threat.localizedName);
                }
                String message = Core.bundle.format("ra2ann.enemy.force.line", item);
                EventFeedOverlay.show(message, cx / total, cy / total, dominant.uiIcon, dominant.localizedName, "ra2ann-attack-color");
                Announcer.chain(Announcer.P_ATTACK, 0L, null,
                    Ra2Announcer.categoryLine(dominant), "name-unit-" + dominant.name);
            }
        }
        clusters.clear();
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
        final UnitType type;
        final float x;
        final float y;

        ThreatUnit(UnitType type, float x, float y){
            this.type = type;
            this.x = x;
            this.y = y;
        }
    }
}
