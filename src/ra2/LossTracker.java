package ra2;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.gen.Unit;

import static mindustry.Vars.player;

/**
 * 我方损失的合并播报(自 BattleVoice 合并而来)。
 * 同型损失在短窗口内累加,合并成一张卡片;语音为“损失台词 + 该类型名称”,
 * 让“被摧毁的是哪一类”既能看也能听。
 */
final class LossTracker{
    private static final float FLUSH_TICKS = 60f;
    private static final float MAX_WAIT_TICKS = 120f;
    private static final int MAX_TYPES = 4;

    private static final ObjectMap<String, PendingLoss> pending = new ObjectMap<>();

    private LossTracker(){
    }

    static void reset(){
        pending.clear();
    }

    static void onUnitDestroy(Unit unit){
        if(player == null || unit == null || unit.team != player.team()) return;
        if(unit.type == null || Ra2Announcer.isMissile(unit)) return;
        if(!TypeFilters.lossUnitAllowed(unit)) return;
        queue(LossKind.UNIT, unit.type.name, unit.type.localizedName, unit.type.uiIcon, unit.x, unit.y);
    }

    static void onBlockDestroy(Building build){
        if(player == null || build == null || build.team != player.team()) return;
        if(build.block == null) return;
        if(!TypeFilters.lossBlockAllowed(build)) return;
        queue(LossKind.BUILDING, build.block.name, build.block.localizedName, build.block.uiIcon, build.x, build.y);
    }

    private static void queue(LossKind kind, String typeKey, String displayName, TextureRegion icon, float x, float y){
        if(typeKey == null || typeKey.isEmpty()) typeKey = "unknown";
        if(displayName == null || displayName.isEmpty()) displayName = typeKey;

        String key = kind.name() + ":" + typeKey;
        PendingLoss loss = pending.get(key);
        if(loss == null){
            pending.put(key, new PendingLoss(kind, typeKey, displayName, icon, x, y));
        }else{
            loss.count++;
            loss.x += x;
            loss.y += y;
            loss.lastTick = Time.time;
        }
    }

    static void flush(){
        flush(false);
    }

    static void flush(boolean force){
        if(pending.isEmpty()) return;

        Seq<PendingLoss> ready = new Seq<>();
        for(ObjectMap.Entry<String, PendingLoss> entry : pending){
            PendingLoss loss = entry.value;
            float age = Time.time - loss.lastTick;
            float totalAge = Time.time - loss.firstTick;
            if(force || age >= FLUSH_TICKS || totalAge >= MAX_WAIT_TICKS){
                ready.add(loss);
            }
        }
        if(ready.isEmpty()) return;

        for(LossKind kind : LossKind.values()){
            Seq<PendingLoss> kindReady = ready.select(loss -> loss.kind == kind);
            if(kindReady.isEmpty()) continue;

            boolean enabled = kind == LossKind.UNIT
                ? Core.settings.getBool("ra2ann-loss-units", true)
                : Core.settings.getBool("ra2ann-loss-blocks", true);
            if(!enabled){
                for(PendingLoss loss : kindReady) pending.remove(loss.key());
                continue;
            }

            kindReady.sort((a, b) -> Integer.compare(b.count, a.count));
            int total = 0;
            float x = 0f, y = 0f;
            for(PendingLoss loss : kindReady){
                total += loss.count;
                x += loss.x * loss.count;
                y += loss.y * loss.count;
            }

            Seq<String> parts = new Seq<>();
            int listed = Math.min(kindReady.size, MAX_TYPES);
            for(int i = 0; i < listed; i++){
                PendingLoss loss = kindReady.get(i);
                parts.add(Texts.item(loss.displayName, loss.count));
            }

            String message = Core.bundle.get(kind == LossKind.UNIT ? "ra2ann.unit.lost.batch" : "ra2ann.structure.lost.batch", "?")
                + Texts.list(parts, Math.max(0, kindReady.size - listed));
            PendingLoss top = kindReady.first();
            EventFeedOverlay.show(message,
                total == 0 ? Float.NaN : x / total, total == 0 ? Float.NaN : y / total,
                top.icon, top.displayName,
                kind == LossKind.UNIT ? "ra2ann-loss-unit-color" : "ra2ann-loss-block-color");

            // 详细类别:损失台词后面接上损失最重的那一型的名称
            if(kind == LossKind.UNIT){
                long cooldown = Math.max(3, Core.settings.getInt("ra2ann-loss-cooldown", 15)) * 1000L;
                Announcer.chain(Announcer.P_INFO, cooldown, "ann_unit_lost", "ann_unit_lost", "name-unit-" + top.typeKey);
            }else{
                long cooldown = Math.max(3, Core.settings.getInt("ra2ann-loss-block-cooldown", 45)) * 1000L;
                Announcer.chain(Announcer.P_INFO, cooldown, "ann_structure_lost", "ann_structure_lost", "name-block-" + top.typeKey);
            }

            for(PendingLoss loss : kindReady) pending.remove(loss.key());
        }
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
        final float firstTick;
        int count = 1;
        float lastTick;
        float x;
        float y;

        PendingLoss(LossKind kind, String typeKey, String displayName, TextureRegion icon, float x, float y){
            this.kind = kind;
            this.typeKey = typeKey;
            this.displayName = displayName;
            this.icon = icon;
            this.firstTick = Time.time;
            this.lastTick = firstTick;
            this.x = x;
            this.y = y;
        }

        String key(){
            return kind.name() + ":" + typeKey;
        }
    }
}
