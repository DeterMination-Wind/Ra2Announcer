package ra2;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.WidgetGroup;
import arc.util.Time;
import mindustry.ui.Styles;

import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * 屏幕上方居中的红色警戒横幅。用于“监控单位死亡”这类需要一眼看到的本地事件;
 * 文字内容与事件卡片一致,卡片仍负责右侧记录/世界标记。
 */
public final class MonitorBanner{
    private static final String rootName = "ra2-monitor-banner";
    private static final String BORDER_HEX = "10100f";
    private static final Color borderColor = Color.valueOf(BORDER_HEX);
    private static final Color panelBase = new Color(), panelTop = new Color(), panelBottom = new Color(),
        panelLight = new Color(), panelDark = new Color();

    private static HudRoot root;
    private static boolean attached;

    private MonitorBanner(){
    }

    public static void init(){
        if(attached) return;
        attached = true;
        ensure();
    }

    public static void show(String title, String line, TextureRegion icon){
        ensure();
        if(root == null) return;
        root.show(title, line, icon);
    }

    public static void clear(){
        if(root != null) root.hide();
    }

    private static void ensure(){
        if(ui == null || ui.hudGroup == null) return;
        Element existing = ui.hudGroup.find(rootName);
        if(existing instanceof HudRoot hud){
            root = hud;
            return;
        }
        if(existing != null) existing.remove();

        root = new HudRoot();
        root.name = rootName;
        ui.hudGroup.addChild(root);
        root.toFront();
    }

    private static class HudRoot extends WidgetGroup{
        private final Banner banner = new Banner();
        private float shownAt = -1f;
        private float until = -1f;
        private float lastTextWidth = -1f;

        HudRoot(){
            setFillParent(true);
            touchable = Touchable.disabled;
            addChild(banner);
            banner.visible = false;
        }

        void show(String title, String line, TextureRegion icon){
            banner.set(title, line, icon);
            banner.visible = true;
            shownAt = Time.time;
            until = shownAt + Math.max(3, Core.settings.getInt("ra2ann-monitor-banner-duration", 6)) * 60f;
            banner.color.a = 1f;
            layoutBanner();
        }

        void hide(){
            shownAt = -1f;
            until = -1f;
            banner.visible = false;
        }

        private void layoutBanner(){
            float width = Math.min(Core.graphics.getWidth() * 0.74f, Scl.scl(640f));
            float height = Scl.scl(64f);
            banner.setBounds((getWidth() - width) / 2f, getHeight() - Scl.scl(96f) - height, width, height);
            if(getWidth() > 0f){
                //固定文字宽度,让超长昵称真的走省略号而不是撑破面板
                float textWidth = Math.max(Scl.scl(80f), width - Scl.scl(76f));
                if(Math.abs(textWidth - lastTextWidth) > 0.5f){
                    lastTextWidth = textWidth;
                    banner.textWidth(textWidth);
                }
            }
        }

        @Override
        public void act(float delta){
            super.act(delta);
            if(!state.isGame() || ui == null || ui.hudfrag == null || !ui.hudfrag.shown){
                hide();
                return;
            }
            if(!banner.visible) return;

            float now = Time.time;
            if(now >= until){
                hide();
                return;
            }
            //0.2 秒淡入,最后 0.4 秒淡出
            banner.color.a = Mathf.clamp((now - shownAt) / 12f) * Mathf.clamp((until - now) / 24f);
            layoutBanner();
        }
    }

    private static class Banner extends Table{
        private final Image icon;
        private final Label title, line;
        private final Cell<Label> titleCell, lineCell;
        private final Color accent = new Color();
        private final Color panel = new Color();

        Banner(){
            touchable = Touchable.disabled;
            margin(Scl.scl(10f));

            icon = new Image();
            icon.visible = false;
            add(icon).size(Scl.scl(38f)).padRight(Scl.scl(10f)).left();

            Table text = new Table();
            text.left();
            title = new Label("", Styles.outlineLabel);
            title.setFontScale(1.05f);
            line = new Label("", Styles.outlineLabel);
            line.setEllipsis("…");
            titleCell = text.add(title).left().growX();
            titleCell.row();
            lineCell = text.add(line).left().growX();
            add(text).growX().left();
        }

        void textWidth(float width){
            titleCell.width(width);
            lineCell.width(width);
            invalidateHierarchy();
        }

        void set(String titleText, String lineText, TextureRegion iconRegion){
            title.setText(titleText == null ? "" : titleText);
            line.setText(lineText == null ? "" : lineText);
            if(iconRegion != null){
                icon.setDrawable(new TextureRegionDrawable(iconRegion));
                icon.visible = true;
            }else{
                icon.visible = false;
            }
            accent.set(Color.valueOf(EventFeedOverlay.accentHex("ra2ann-attack-color")));
            panel.set(Color.valueOf(EventFeedOverlay.alertBackgroundHex()));
            title.setColor(accent);
            line.setColor(Color.white);
            invalidateHierarchy();
        }

        @Override
        public void draw(){
            validate();
            float alpha = parentAlpha * color.a;
            float w = getWidth(), h = getHeight(), t = Scl.scl(1f);

            panelBase.set(panel);
            panelTop.set(panelBase).lerp(Color.white, 0.10f);
            panelBottom.set(panelBase).lerp(Color.black, 0.20f);
            int strips = 8;
            float sh = h / strips;
            for(int i = 0; i < strips; i++){
                Draw.color(panelTop, panelBottom, (i + 0.5f) / strips);
                Draw.alpha(alpha);
                Fill.rect(x + w / 2f, y + h - sh * (i + 0.5f), w, sh + t);
            }

            Color border = borderColor;
            Draw.color(border, alpha);
            Fill.rect(x + w / 2f, y + h - t / 2f, w, t);
            Fill.rect(x + w / 2f, y + t / 2f, w, t);
            Fill.rect(x + t / 2f, y + h / 2f, t, h);
            Fill.rect(x + w - t / 2f, y + h / 2f, t, h);

            panelLight.set(panelBase).lerp(Color.white, 0.28f);
            Draw.color(panelLight, alpha);
            Fill.rect(x + w / 2f, y + h - 1.5f * t, w - 2f * t, t);
            Fill.rect(x + 1.5f * t, y + h / 2f, t, h - 2f * t);

            panelDark.set(panelBase).lerp(Color.black, 0.45f);
            Draw.color(panelDark, alpha);
            Fill.rect(x + w / 2f, y + 1.5f * t, w - 2f * t, t);
            Fill.rect(x + w - 1.5f * t, y + h / 2f, t, h - 2f * t);

            //左侧 5px 强调条:与事件卡片的视觉语言保持一致
            Draw.color(accent, alpha);
            Fill.rect(x + Scl.scl(3f), y + h / 2f, Scl.scl(5f), Math.max(Scl.scl(8f), h - Scl.scl(6f)));
            Draw.reset();
            super.draw();
        }
    }
}
