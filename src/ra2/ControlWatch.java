package ra2;

import arc.Core;
import arc.struct.ObjectMap;
import arc.util.Time;
import mindustry.game.EventType.BuildingCommandEvent;
import mindustry.game.EventType.UnitControlEvent;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Building;
import mindustry.gen.Player;
import mindustry.gen.Unit;

import static mindustry.Vars.player;
import static mindustry.Vars.state;

/**
 * 单控(“手动接管单位/炮塔/建筑”)与建筑指挥的播报(自 BattleVoice 合并而来)。
 * v8 里所有接管都走 unitControl 广播,客户端都能收到 UnitControlEvent,
 * 建筑指挥走 BuildingCommandEvent。
 * 语音为“分类台词 + 具体名称”,所以听到的是“敌方步兵营来袭 + 单位名”这样的组合。
 */
final class ControlWatch{
    private static final ObjectMap<String, Long> announcedAt = new ObjectMap<>();

    private ControlWatch(){
    }

    static void reset(){
        announcedAt.clear();
    }

    static void register(){
        arc.Events.on(UnitControlEvent.class, ControlWatch::onControl);
        arc.Events.on(BuildingCommandEvent.class, ControlWatch::onBuildingCommand);
    }

    static void onControl(UnitControlEvent event){
        if(player == null || event.player == null || event.unit == null) return;
        if(event.player == player) return;
        announce(event.player, event.unit);
    }

    static void onBuildingCommand(BuildingCommandEvent event){
        if(player == null || event.player == null || event.building == null) return;
        if(event.player == player) return;
        if(!Core.settings.getBool("ra2ann-building-command", true)) return;

        boolean enemy = event.player.team() != player.team();
        if(enemy && !Core.settings.getBool("ra2ann-control-enemy", true)) return;
        if(!enemy && !Core.settings.getBool("ra2ann-control-friendly", true)) return;

        Building build = event.building;
        if(build.block == null) return;
        if(!cooldownOk("bcmd:" + event.player.id + ":" + build.block.name)) return;

        String name = build.block.localizedName;
        String who = plainName(event.player);
        String message = Core.bundle.format(enemy ? "ra2ann.building.command.enemy.line" : "ra2ann.building.command.friendly.line", who, name);
        EventFeedOverlay.show(message, build.x, build.y, build.block.uiIcon, name,
            enemy ? "ra2ann-control-color" : "ra2ann-control-friendly-color");
        Announcer.chain(Announcer.P_CONTROL, 0L, null,
            enemy ? "ann_control_building" : "ann_control_friendly", "name-block-" + build.block.name);
    }

    static void announce(Player controller, Unit unit){
        if(!state.isGame()) return;

        boolean enemy = controller.team() != player.team();
        if(enemy && !Core.settings.getBool("ra2ann-control-enemy", true)) return;
        if(!enemy && !Core.settings.getBool("ra2ann-control-friendly", true)) return;

        String who = plainName(controller);

        if(unit instanceof BlockUnitc blockUnit && blockUnit.tile() != null){
            //单控炮塔/建筑(含核心与路由器):BlockUnitc#tile() 直接返回被控制的建筑
            Building build = blockUnit.tile();
            if(build.block == null) return;
            if(!cooldownOk("ctrl:" + controller.id + ":block:" + build.block.name)) return;

            String name = build.block.localizedName;
            String message = Core.bundle.format(enemy ? "ra2ann.control.enemy.line" : "ra2ann.control.friendly.line", who, name);
            EventFeedOverlay.show(message, build.x, build.y, build.block.uiIcon, name,
                enemy ? "ra2ann-control-color" : "ra2ann-control-friendly-color");
            Announcer.chain(Announcer.P_CONTROL, 0L, null,
                enemy ? "ann_control_building" : "ann_control_friendly", "name-block-" + build.block.name);
        }else if(unit.type != null){
            if(!cooldownOk("ctrl:" + controller.id + ":" + unit.type.name)) return;

            String name = unit.type.localizedName;
            String message = Core.bundle.format(enemy ? "ra2ann.control.enemy.line" : "ra2ann.control.friendly.line", who, name);
            EventFeedOverlay.show(message, unit.x, unit.y, unit.type.uiIcon, name,
                enemy ? "ra2ann-control-color" : "ra2ann-control-friendly-color");
            Announcer.chain(Announcer.P_CONTROL, 0L, null,
                enemy ? Ra2Announcer.categoryLine(unit.type) : "ann_control_friendly",
                "name-unit-" + unit.type.name);
        }
    }

    private static boolean cooldownOk(String key){
        long cooldown = Math.max(3, Core.settings.getInt("ra2ann-control-cooldown", 15)) * 1000L;
        long now = Time.millis();
        if(now - announcedAt.get(key, 0L) < cooldown) return false;
        announcedAt.put(key, now);
        if(announcedAt.size > 512) announcedAt.clear();
        return true;
    }

    private static String plainName(Player controller){
        try{
            return controller.plainName();
        }catch(Throwable ignored){
            return controller.name;
        }
    }
}
