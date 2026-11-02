package ra2;

import arc.Core;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import mindustry.game.MapObjectives;
import mindustry.game.MapObjectives.MapObjective;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static mindustry.Vars.player;
import static mindustry.Vars.state;

/**
 * 地图目标(Map Objective)完成播报。
 *
 * <p>Mindustry 没有公开的“目标完成”事件:完成路径只有 {@code Call.completeObjective(index)} →
 * {@code MapObjective.done()} 把内部 completed 置位,单人和多人客户端都走同一条路。
 * 所以这里按完成状态轮询,只播报本次新完成的目标:载入存档/中途加入时已经完成的目标
 * 会在第一次轮询时记为已处理,不会补播。</p>
 *
 * <p>播报内容仍是“卡片为准确信息源”:卡片列出完成的目标文本(去掉颜色标记与换行),
 * 语音只播索菲亚的固定台词 “Objective complete.”({@code ann_objective} → {@code ann_wave_cleared})。</p>
 *
 * <p>实现说明:完成状态只有 {@code MapObjective.isCompleted()} 能读到,而
 * {@code MapObjective implements arc.util.serialization.AllowSerialization};工作区的
 * {@code Arc/arc-core} 构建产物比 Mindustry v159 所需的 Arc 旧(缺这个标记接口),
 * 直接调用会让 javac 报“无法访问 AllowSerialization”。这里改用反射读取几个公开成员,
 * 既不动共享的 Arc 产物,也不依赖编译期接口可见性;运行期调用的都是 v159 的公开 API。</p>
 */
final class ObjectiveWatch{
    /** 一次卡片里最多列几个目标,其余用“等N项”收尾 */
    private static final int MAX_LISTED = 3;
    /** 同一批目标完成只播一次语音,避免连锁目标连续完成时刷屏 */
    private static final long VOICE_COOLDOWN = 3_000L;

    private static final Method isCompletedMethod = method("isCompleted");
    private static final Method textMethod = method("text");
    private static final Method typeNameMethod = method("typeName");
    private static final Field hiddenField = field("hidden");

    /** 已处理过的目标(按对象身份),含加载时本来就是完成状态的目标 */
    private static final ObjectSet<Object> reported = new ObjectSet<>();
    /** 当前跟踪的目标执行器;换地图/换存档时整体重置 */
    private static MapObjectives executor;

    private ObjectiveWatch(){
    }

    static void reset(){
        reported.clear();
        executor = null;
    }

    static void update(){
        if(player == null || !state.isGame() || state.rules == null) return;
        MapObjectives current = state.rules.objectives;
        if(current == null) return;

        Seq<?> all = current.all;
        if(all == null) return;

        if(current != executor){
            executor = current;
            reported.clear();
            //载入存档/中途加入:已经完成的目标不重复播报
            for(Object objective : all){
                if(completed(objective)) reported.add(objective);
            }
            return;
        }

        Seq<Object> newly = null;
        for(Object objective : all){
            if(!completed(objective)) continue;
            if(!reported.add(objective)) continue;
            //hidden 目标在游戏里对玩家不可见,播报会泄漏情报
            if(hidden(objective)) continue;
            if(newly == null) newly = new Seq<>();
            newly.add(objective);
        }
        if(newly == null) return;
        if(!Core.settings.getBool("ra2ann-objective", true)) return;

        Seq<String> parts = new Seq<>();
        for(int i = 0; i < Math.min(newly.size, MAX_LISTED); i++){
            parts.add(objectiveText(newly.get(i)));
        }
        if(parts.isEmpty()) return;
        String item = Texts.list(parts, Math.max(0, newly.size - MAX_LISTED));

        String message = Core.bundle.format("ra2ann.objective.line", item);
        EventFeedOverlay.show(message, Float.NaN, Float.NaN, null, item, "ra2ann-info-color");
        //与“波次清空”同一句原版台词、同一优先级;3 秒冷却避免连锁目标连续完成时刷屏
        Announcer.play(Announcer.P_ALARM, VOICE_COOLDOWN, "ann_objective", "ann_objective");
    }

    private static boolean completed(Object objective){
        if(isCompletedMethod == null) return false;
        try{
            return Boolean.TRUE.equals(isCompletedMethod.invoke(objective));
        }catch(Throwable ignored){
            return false;
        }
    }

    private static boolean hidden(Object objective){
        if(hiddenField == null) return false;
        try{
            return hiddenField.getBoolean(objective);
        }catch(Throwable ignored){
            return false;
        }
    }

    /** 目标自己的展示文本;没有时退回目标类型名(都是游戏本地化过的)。 */
    private static String objectiveText(Object objective){
        String text = invokeText(textMethod, objective);
        if(text == null || text.trim().isEmpty()) text = invokeText(typeNameMethod, objective);
        if(text == null || text.trim().isEmpty()) text = Core.bundle.get("ra2ann.unknown.target", "?");
        return clean(text);
    }

    private static String invokeText(Method method, Object objective){
        if(method == null) return null;
        try{
            Object value = method.invoke(objective);
            return value instanceof String ? (String)value : null;
        }catch(Throwable ignored){
            return null;
        }
    }

    /** 去掉 Mindustry 的颜色/图标标记与换行,卡片与提示框只保留可读文本。 */
    private static String clean(String text){
        if(text == null) return "";
        String out = text.replaceAll("\\[[^\\]]*\\]", " ");
        out = out.replace('\n', ' ').replace('\r', ' ');
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    private static Method method(String name){
        try{
            return objectiveType().getMethod(name);
        }catch(Throwable ignored){
            return null;
        }
    }

    private static Field field(String name){
        try{
            return objectiveType().getField(name);
        }catch(Throwable ignored){
            return null;
        }
    }

    private static Class<?> objectiveType(){
        return MapObjective.class;
    }
}
