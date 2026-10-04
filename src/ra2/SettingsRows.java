package ra2;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * 设置页里的自定义行(分区标题、色块、按钮)。
 *
 * <p>自定义行不能直接 {@code table.add(...)} 到设置表上:原版 v8 的 {@code pref()} 每次都调用
 * {@code rebuild()},MindustryX 把重建推迟到绘制时({@code act()} → {@code build()});两者都会先
 * {@code clearChildren()} 再按顺序重建列表里注册过的 {@code Setting}。直加的行会在重建时被抹掉,
 * 表现为整段设置(颜色、标题、语言按钮、过滤按钮)凭空消失。</p>
 *
 * <p>所以自定义行一律经 {@link SettingsTable#pref} 注册成真正的 {@code Setting}:原版与
 * MindustryX 都会在重建时调用 {@link SettingsTable.Setting#add},行内容自动回来。</p>
 */
final class SettingsRows{

    private static final Color TITLE_COLOR = Color.valueOf("ffd166");

    private SettingsRows(){
    }

    /** 注册一行由调用方自由绘制的内容。 */
    static void custom(SettingsTable table, Cons<SettingsTable> builder){
        table.pref(new SettingsTable.Setting(null){
            @Override
            public void add(SettingsTable t){
                builder.get(t);
            }
        });
    }

    /** 注册一个分区标题行({@code name} 为 null,不参与“重置默认值”)。 */
    static void title(SettingsTable table, String bundleKey){
        String text = Core.bundle.get(bundleKey, bundleKey);
        table.pref(new SettingsTable.Setting(null){
            @Override
            public void add(SettingsTable t){
                t.add(text).colspan(2).growX().left().padTop(12f).padBottom(4f).color(TITLE_COLOR).row();
            }
        });
    }
}
