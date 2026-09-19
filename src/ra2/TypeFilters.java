package ra2;

import arc.Core;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Button;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.content;

/**
 * 各类播报的白名单过滤(自 BattleVoice 合并而来)。
 * 以内部名逗号分隔保存;留空表示该类全部播报。
 */
public final class TypeFilters{
    /** enemy rally / core-threat announcements */
    public static final String ATTACK_UNITS = "ra2ann-filter-attack-units";
    /** our units that are being shot at */
    public static final String ATTACKED_UNITS = "ra2ann-filter-attacked-units";
    /** our unit losses */
    public static final String LOSS_UNITS = "ra2ann-filter-loss-units";
    /** our building losses */
    public static final String LOSS_BLOCKS = "ra2ann-filter-loss-blocks";

    private TypeFilters(){
    }

    public static boolean allows(String settingKey, String internalName){
        if(internalName == null) return true;
        String list = Core.settings.getString(settingKey, "");
        if(list == null || list.trim().isEmpty()) return true;
        for(String item : list.split("[,，]")){
            if(item.trim().equalsIgnoreCase(internalName)) return true;
        }
        return false;
    }

    public static boolean attackUnitAllowed(UnitType type){
        return type == null || allows(ATTACK_UNITS, type.name);
    }

    public static boolean attackedUnitAllowed(UnitType type){
        return type == null || allows(ATTACKED_UNITS, type.name);
    }

    public static boolean lossUnitAllowed(mindustry.gen.Unit unit){
        return unit == null || unit.type == null || allows(LOSS_UNITS, unit.type.name);
    }

    public static boolean lossBlockAllowed(Building build){
        return build == null || build.block == null || allows(LOSS_BLOCKS, build.block.name);
    }

    public static void addFilterButtons(Table table){
        table.button(Core.bundle.get("ra2ann.filter.attack-units", "Enemy attack unit filter"), () ->
            showFilterDialog(Core.bundle.get("ra2ann.filter.attack-units.title", "Enemy attack announcements"), ATTACK_UNITS, true))
            .growX().height(45f).padTop(7f).row();
        table.button(Core.bundle.get("ra2ann.filter.attacked-units", "Our attacked unit filter"), () ->
            showFilterDialog(Core.bundle.get("ra2ann.filter.attacked-units.title", "Our units under attack"), ATTACKED_UNITS, true))
            .growX().height(45f).padTop(7f).row();
        table.button(Core.bundle.get("ra2ann.filter.loss-units", "Our unit loss filter"), () ->
            showFilterDialog(Core.bundle.get("ra2ann.filter.loss-units.title", "Our unit loss announcements"), LOSS_UNITS, true))
            .growX().height(45f).padTop(7f).row();
        table.button(Core.bundle.get("ra2ann.filter.loss-blocks", "Our building loss filter"), () ->
            showFilterDialog(Core.bundle.get("ra2ann.filter.loss-blocks.title", "Our building loss announcements"), LOSS_BLOCKS, false))
            .growX().height(45f).padTop(7f).row();
        table.add(Core.bundle.get("ra2ann.filter.hint", "Checked types are announced; empty selection = all."))
            .growX().wrap().padTop(4f).row();
    }

    private static void showFilterDialog(String title, String settingKey, boolean units){
        BaseDialog dialog = new BaseDialog(title);
        dialog.cont.margin(16f);

        Seq<Row> rows = new Seq<>();
        if(units){
            for(UnitType type : content.units()){
                if(type.hidden || type.internal || type.uiIcon == null) continue;
                rows.add(new Row(settingKey, type.name, type.localizedName, type.uiIcon));
            }
        }else{
            for(Block block : content.blocks()){
                if(block.buildVisibility == BuildVisibility.hidden || block.uiIcon == null) continue;
                rows.add(new Row(settingKey, block.name, block.localizedName, block.uiIcon));
            }
        }

        Table list = new Table();
        if(rows.isEmpty()){
            list.add(Core.bundle.get("ra2ann.filter.empty", "No types available.")).pad(8f).row();
        }
        for(Row row : rows){
            addRow(list, row);
            list.row();
        }

        dialog.cont.pane(list).maxHeight(Core.graphics.getHeight() * 0.6f).growX().row();

        dialog.buttons.defaults().width(140f);
        dialog.buttons.button(Core.bundle.get("ra2ann.filter.all", "All"), () -> setAll(rows, true));
        dialog.buttons.button(Core.bundle.get("ra2ann.filter.none", "None"), () -> setAll(rows, false));
        dialog.buttons.button("@ok", () -> {
            StringBuilder builder = new StringBuilder();
            for(Row row : rows){
                if(row.checked){
                    if(builder.length() > 0) builder.append(',');
                    builder.append(row.internal);
                }
            }
            Core.settings.put(settingKey, builder.toString());
            dialog.hide();
        });
        dialog.buttons.button("@cancel", dialog::hide);
        dialog.show();
    }

    private static void addRow(Table list, Row row){
        Button check = new Button(Styles.grayt);
        check.background(Styles.grayPanel);
        check.margin(8f);
        check.add(new Image()).update(i -> i.setDrawable(check.isOver()
            ? (row.checked ? Tex.checkOnOver : Tex.checkOver)
            : row.checked ? Tex.checkOn : Tex.checkOff))
            .size(28f).padRight(8f);
        if(row.icon != null){
            check.add(new Image(row.icon)).size(28f).padRight(8f);
        }
        check.add(row.display).left().growX();
        check.clicked(() -> row.checked = !row.checked);
        check.left();
        list.add(check).growX().height(44f).left();
    }

    private static void setAll(Seq<Row> rows, boolean value){
        for(Row row : rows) row.checked = value;
    }

    private static class Row{
        final String internal;
        final String display;
        final TextureRegion icon;
        boolean checked;

        Row(String settingKey, String internal, String display, TextureRegion icon){
            this.internal = internal;
            this.display = display;
            this.icon = icon;
            this.checked = allows(settingKey, internal);
        }
    }
}
