package ra2;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.ui.Styles;
import mindustry.ui.fragments.PlacementFragment;

import java.lang.reflect.Field;

import static mindustry.Vars.*;

/**
 * 指挥模式“监控此单位”。
 *
 * <p>在右下角指挥模式菜单里追加一行“监控此单位 / 取消监控”,记录被监控单位最近被哪些玩家接管过
 * (每帧轮询 {@code unit.controller()},客户端也能看到其他玩家接管队友单位后的控制器变化)。
 * 若该单位在被玩家操控时被摧毁,则用横幅 + 事件卡片 + 索菲亚台词播报最近两名操控者;
 * 倒数第二名玩家后面带上他停止操控至今的时间,例如 {@code 玩家2(30s前)->玩家1}。</p>
 *
 * <p>纯本地功能:不上报、不同步,只读取本地客户端已有的实体状态。</p>
 */
final class UnitWatch{
    private static final String rowName = "ra2-monitor-row";
    private static final int MAX_HISTORY = 12;

    private static final Seq<Session> history = new Seq<>();
    private static Unit watched;
    private static int watchedId = -1;
    private static Session active;
    private static boolean deathLatched;
    private static boolean diedControlled;
    private static long deathAt;
    private static float deathX, deathY;

    private static Table installedTable;
    private static Field commandTableField, rebuildCommandField;
    private static boolean installFailed;

    private UnitWatch(){
    }

    static void init(){
        ensureInstalled();
    }

    static void reset(){
        clear();
        MonitorBanner.clear();
    }

    /** 每帧调用:记录控制者切换,并顺手修复被 PlacementFragment 重建掉的菜单行。 */
    static void update(){
        ensureInstalled();
        if(watched == null) return;
        if(watched.id != watchedId || !watched.isAdded() || watched.type == null){
            clear();
            return;
        }
        //空中单位被击落后可能先变成 dead,真正 destroy()/UnitDestroyEvent 要等坠地,
        //所以要在“死亡”发生的第一帧就把操控状态锁存下来,之后玩家复活清空控制器也不影响判定。
        if(watched.dead()){
            if(!deathLatched){
                deathLatched = true;
                diedControlled = watched.controller() instanceof Player;
                deathAt = Time.millis();
                deathX = watched.x;
                deathY = watched.y;
                if(diedControlled && active != null && active.endMs < 0) active.endMs = deathAt;
            }
            return;
        }
        poll(Time.millis());
    }

    /** 单位被摧毁:只有“被玩家操控时死亡”才播报最近两名操控者。 */
    static void onUnitDestroy(Unit unit){
        if(watched == null || unit == null || unit != watched || unit.id != watchedId) return;

        long now = Time.millis();
        boolean controlled;
        long at;
        float x, y;
        if(deathLatched){
            controlled = diedControlled;
            at = deathAt;
            x = deathX;
            y = deathY;
        }else{
            //非空中单位通常在 killed() 里立刻 destroy(),走不到上面的锁存分支
            poll(now);
            controlled = unit.controller() instanceof Player;
            at = now;
            x = unit.x;
            y = unit.y;
            if(controlled && active != null && active.endMs < 0) active.endMs = at;
        }

        if(controlled && Announcer.enabled()){
            announce(unit, at, x, y);
        }
        clear();
    }

    /** 指挥模式菜单里的“监控此单位”。 */
    static void watchSelected(){
        Seq<Unit> selected = control == null ? null : control.input.selectedUnits;
        if(selected == null || selected.size != 1){
            info(Core.bundle.get("ra2ann.monitor.select.one", "Select exactly one unit."));
            return;
        }
        Unit unit = selected.first();
        if(unit == null || unit.type == null || unit.dead() || !unit.isAdded()) return;
        if(unit == watched) return;

        watched = unit;
        watchedId = unit.id;
        active = null;
        history.clear();
        deathLatched = false;
        diedControlled = false;
        deathAt = 0L;
        //选中时已经被接管的情况也要计入,否则第一个操控者会丢
        poll(Time.millis());
    }

    static void clear(){
        watched = null;
        watchedId = -1;
        active = null;
        history.clear();
        deathLatched = false;
        diedControlled = false;
        deathAt = 0L;
    }

    static boolean isWatching(){
        return watched != null;
    }

    static String watchedName(){
        Unit unit = watched;
        return unit == null || unit.type == null ? "" : unit.type.localizedName;
    }

    //region control history

    private static void poll(long now){
        if(watched == null) return;
        Player controller = watched.controller() instanceof Player p ? p : null;

        if(controller != null){
            if(active == null || active.playerId != controller.id){
                closeActive(now);
                active = new Session(controller.id, plainName(controller), now);
                history.add(active);
                while(history.size > MAX_HISTORY) history.remove(0);
            }
        }else{
            closeActive(now);
        }
    }

    private static void closeActive(long now){
        if(active == null) return;
        if(active.endMs < 0) active.endMs = now;
        active = null;
    }

    private static void announce(Unit unit, long now, float x, float y){
        Player controller = unit.controller() instanceof Player p ? p : null;
        Session last = null, second = null;
        for(int i = history.size - 1; i >= 0; i--){
            Session session = history.get(i);
            if(last == null){
                last = session;
            }else if(session.playerId != last.playerId){
                second = session;
                break;
            }
        }

        String lastName = controller != null ? plainName(controller) : (last == null ? unknownPlayer() : last.name);

        String line;
        if(second == null){
            line = Core.bundle.format("ra2ann.monitor.line.single", shorten(lastName));
        }else{
            long ended = second.endMs >= 0 ? second.endMs : second.startMs;
            String secondText = Core.bundle.format("ra2ann.monitor.entry", shorten(second.name), ago(now - ended));
            line = Core.bundle.format("ra2ann.monitor.line", secondText, shorten(lastName));
        }

        String unitName = unit.type == null ? Core.bundle.get("ra2ann.unknown.target", "?") : unit.type.localizedName;
        TextureRegion icon = unit.type == null ? null : unit.type.uiIcon;
        String title = Core.bundle.get("ra2ann.monitor.title", "Watched unit destroyed");

        MonitorBanner.show(title, line, icon);
        EventFeedOverlay.show(Core.bundle.format("ra2ann.monitor.card", unitName, line),
            x, y, icon, unitName, "ra2ann-attack-color");
        Announcer.play(Announcer.P_ALARM, 0L, null, "ann_unit_lost");
    }

    /** Truncates a nickname by code points so emoji / CJK still count as one character. */
    private static String shorten(String name){
        String clean = name == null ? "" : name.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if(clean.isEmpty()) clean = unknownPlayer();
        int max = Math.max(3, Math.min(32, Core.settings.getInt("ra2ann-monitor-name-length", 12)));
        int count = clean.codePointCount(0, clean.length());
        if(count <= max) return clean;
        int end = clean.offsetByCodePoints(0, Math.max(1, max - 1));
        return clean.substring(0, end) + "…";
    }

    private static String ago(long millis){
        long seconds = Math.max(0L, millis / 1000L);
        String duration;
        if(seconds < 60L){
            duration = Core.bundle.format("ra2ann.monitor.time.sec", seconds);
        }else if(seconds < 3600L){
            duration = Core.bundle.format("ra2ann.monitor.time.min", seconds / 60L, seconds % 60L);
        }else{
            duration = Core.bundle.format("ra2ann.monitor.time.hour", seconds / 3600L, (seconds % 3600L) / 60L);
        }
        return Core.bundle.format("ra2ann.monitor.ago", duration);
    }

    private static String unknownPlayer(){
        return Core.bundle.get("ra2ann.monitor.unknown.player", "?");
    }

    private static String plainName(Player player){
        try{
            return player.plainName();
        }catch(Throwable ignored){
            return player.name;
        }
    }

    private static class Session{
        final int playerId;
        final String name;
        final long startMs;
        long endMs = -1L;

        Session(int playerId, String name, long startMs){
            this.playerId = playerId;
            this.name = name == null ? "" : name;
            this.startMs = startMs;
        }
    }

    //endregion

    //region command menu row

    /**
     * 把菜单行挂进 PlacementFragment 的指挥表。
     *
     * <p>原版在 {@code PlacementFragment.build()} 里创建 commandTable,并在
     * {@code rebuildCommand} 中 {@code u.clearChildren()} 重建内容;MindustryX 的 ARC 补丁也是在
     * 同一张表里追加“低血量单位”等行。这里包一层 rebuildCommand,在原版重建完成后再追加本行,
     * 因此两种客户端上的层级/位置一致。字段是包级私有,只能反射读取。</p>
     */
    private static void ensureInstalled(){
        if(installFailed) return;
        try{
            if(ui == null || ui.hudfrag == null || ui.hudfrag.blockfrag == null) return;
            PlacementFragment fragment = ui.hudfrag.blockfrag;

            if(commandTableField == null){
                commandTableField = PlacementFragment.class.getDeclaredField("commandTable");
                commandTableField.setAccessible(true);
                rebuildCommandField = PlacementFragment.class.getDeclaredField("rebuildCommand");
                rebuildCommandField.setAccessible(true);
            }

            Table table = (Table)commandTableField.get(fragment);
            if(table == null) return;
            Runnable current = (Runnable)rebuildCommandField.get(fragment);
            if(table == installedTable && current instanceof RebuildHook) return;

            if(current instanceof RebuildHook hook){
                hook.table = table;
            }else{
                rebuildCommandField.set(fragment, new RebuildHook(current, table));
            }
            installedTable = table;
            appendRow(table);
        }catch(NoSuchFieldException | SecurityException ignored){
            //字段结构变了(非 v8 / 被其它补丁改名):不要再每帧重复反射
            installFailed = true;
        }catch(Throwable ignored){
            installedTable = null;
        }
    }

    private static void appendRow(Table commandTable){
        Table inner = innerTable(commandTable);
        if(inner == null || inner.find(rowName) != null) return;

        Table row = new Table();
        row.name = rowName;
        row.left();

        TextButton watch = row.button(Core.bundle.get("ra2ann.monitor.button", "Watch unit"),
            Styles.cleart, UnitWatch::watchSelected).size(100f, 40f)
            .tooltip(Core.bundle.get("ra2ann.monitor.hint", "Select one unit, then click."), true).get();
        watch.setDisabled(() -> selectedCount() != 1);

        TextButton cancel = row.button("×", Styles.cleart, UnitWatch::clear).size(36f, 40f).padLeft(4f)
            .tooltip(Core.bundle.get("ra2ann.monitor.cancel", "Stop watching"), true).get();
        cancel.setDisabled(() -> !isWatching());

        //第二行显示当前监控对象:命令表在手机上很窄,和按钮挤在同一行会撑破面板
        row.row();
        Label status = new Label("", Styles.defaultLabel);
        row.add(status).colspan(2).growX().left().padTop(2f).color(Color.lightGray);
        status.setEllipsis("…");

        watch.update(() -> watch.setColor(selectedCount() == 1 ? Color.white : Color.gray));
        cancel.update(() -> cancel.setColor(isWatching() ? Color.white : Color.gray));
        status.update(() -> status.setText(isWatching()
            ? Core.bundle.format("ra2ann.monitor.active", watchedName())
            : Core.bundle.get("ra2ann.monitor.idle", "No unit watched")));

        inner.row();
        inner.add(row).growX().padTop(4f).left();
    }

    private static Table innerTable(Table commandTable){
        Table result = null;
        for(Cell<?> cell : commandTable.getCells()){
            if(cell.get() instanceof Table table && table != commandTable){
                result = table;
            }
        }
        return result;
    }

    private static int selectedCount(){
        try{
            return control == null ? 0 : control.input.selectedUnits.size;
        }catch(Throwable ignored){
            return 0;
        }
    }

    private static void info(String message){
        if(ui == null) return;
        try{
            ui.showInfoToast(message, 2f);
        }catch(Throwable ignored){
        }
    }

    private static class RebuildHook implements Runnable{
        Runnable original;
        Table table;

        RebuildHook(Runnable original, Table table){
            this.original = original;
            this.table = table;
        }

        @Override
        public void run(){
            if(original != null) original.run();
            appendRow(table);
        }
    }

    //endregion
}
