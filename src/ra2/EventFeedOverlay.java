package ra2;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.WidgetGroup;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import arc.util.pooling.Pools;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;

import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * 右侧事件卡片 + 世界浮标 + 卡片连线(自 BattleVoice 合并而来)。
 * 同一条消息在 {@link #DEDUP_TICKS} 内重复触发时不再新增卡片,只把已有卡片和浮标的停留时间续期,
 * 避免同一事件让右侧列表刷屏。
 */
public final class EventFeedOverlay{
    private static final String rootName = "ra2-announcement-overlay";
    private static final float minWidth = 240f;
    private static final float maxWidth = 560f;
    private static final int defaultMaxEntries = 6;
    /** identical messages within this window reuse the existing card instead of adding a new one */
    private static final float DEDUP_TICKS = 75f;
    /** RA2 panel: near-black 1px frame + derived highlight/shadow, no rounding */
    private static final String BORDER_HEX = "10100f";
    private static final String DEFAULT_ACCENT = "ef3d46";
    private static final String DEFAULT_ALERT_BACKGROUND = "b51f2a";
    private static final String DEFAULT_INFO_BACKGROUND = "4a4a52";

    /**
     * 播报小类清单:{强调色设置键, 卡片底色设置键, 强调色默认值}。
     * 设置页的小类颜色行与卡片绘制共用这一份,避免键名和默认值两处走样。
     */
    static final String[][] colorCategories = {
        {"ra2ann-accent-color", "ra2ann-accent-bg", DEFAULT_ACCENT},
        {"ra2ann-wave-color", "ra2ann-wave-bg", "ffd166"},
        {"ra2ann-base-color", "ra2ann-base-bg", DEFAULT_ACCENT},
        {"ra2ann-attack-color", "ra2ann-attack-bg", "ff5a3c"},
        {"ra2ann-core-threat-color", "ra2ann-core-threat-bg", "ff2a2a"},
        {"ra2ann-control-color", "ra2ann-control-bg", "ff7e46"},
        {"ra2ann-control-friendly-color", "ra2ann-control-friendly-bg", "4ce0c8"},
        {"ra2ann-loss-unit-color", "ra2ann-loss-unit-bg", "b51f2a"},
        {"ra2ann-loss-block-color", "ra2ann-loss-block-bg", "9aa0a6"},
        {"ra2ann-high-value-color", "ra2ann-high-value-bg", "ffb347"},
        {"ra2ann-factory-color", "ra2ann-factory-bg", "64a0ff"},
        {"ra2ann-unit-ready-color", "ra2ann-unit-ready-bg", "64a0ff"},
        {"ra2ann-miner-color", "ra2ann-miner-bg", "ffd166"},
        {"ra2ann-info-color", "ra2ann-info-bg", "64a0ff"},
    };

    /** scratch colors so the per-frame card draw does not allocate */
    private static final Color baseColor = new Color(), panelTop = new Color(), panelBottom = new Color(),
        panelLight = new Color(), panelDark = new Color();
    private static Color borderColor;

    private static HudRoot root;
    private static boolean attached;

    private EventFeedOverlay(){
    }

    public static void init(){
        if(attached) return;
        attached = true;
        ensureAttached();
    }

    public static void show(String message, float worldX, float worldY, TextureRegion icon, String markerText, String colorKey){
        if(message == null || message.trim().isEmpty()) return;

        boolean cards = Core.settings.getBool("ra2ann-ui-enabled", true);
        boolean markers = Core.settings.getBool("ra2ann-marker-enabled", true);

        // 需求:准确的播报内容由提示框承担 —— 语音不需要准确,所以准确信息不能只活在语音里。
        // 卡片被关掉时用原版提示框兜底;也可以设置成每次播报都额外弹一条。
        if(toastMode() >= 2 || (!cards && toastMode() >= 1)) toast(message);
        if(!cards && !markers) return;

        ensureAttached();
        if(root == null) return;

        boolean hasPosition = !Float.isNaN(worldX) && !Float.isNaN(worldY);
        float now = Time.time;
        float panelDuration = Math.max(0.5f, Core.settings.getInt("ra2ann-ui-duration", 4)) * 60f;
        float markerDuration = Math.max(0.5f, Core.settings.getInt("ra2ann-marker-duration", 8)) * 60f;

        String marker = markerText == null ? shortText(message) : markerText;
        if(root.refresh(message, marker, worldX, worldY, hasPosition, now + panelDuration, now + markerDuration)) return;

        root.add(new Entry(message, marker,
            worldX, worldY, hasPosition, icon, colorKey, now, now + panelDuration, now + markerDuration));
    }

    /** 0 = 关闭,1 = 卡片关闭时兜底,2 = 每次播报都弹。 */
    private static int toastMode(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-toast-mode", 1), 0, 2);
    }

    /** Vanilla top-of-screen notification; the accurate record of an event when cards are off. */
    private static void toast(String message){
        if(ui == null || !activeGame()) return;
        try{
            ui.showInfoToast(message, Math.max(2f, Core.settings.getInt("ra2ann-toast-duration", 5)));
        }catch(Throwable ignored){
        }
    }

    public static void clear(){
        if(root != null) root.clearEntries();
    }

    private static void ensureAttached(){
        if(ui == null || ui.hudGroup == null) return;

        Element existing = ui.hudGroup.find(rootName);
        if(existing instanceof HudRoot){
            root = (HudRoot)existing;
            return;
        }
        if(existing != null) existing.remove();

        root = new HudRoot();
        root.name = rootName;
        ui.hudGroup.addChild(root);
        root.toFront();
    }

    private static float panelWidth(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-ui-width", 320), minWidth, maxWidth) * Scl.scl(1f);
    }

    private static float panelScale(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-ui-scale", 100), 80, 160) / 100f;
    }

    private static float opacity(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-ui-opacity", 85), 30, 100) / 100f;
    }

    private static int maxEntries(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-ui-max-entries", defaultMaxEntries), 3, 10);
    }

    private static float spacing(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-ui-spacing", 4), 0, 12) * Scl.scl(1f);
    }

    private static float offsetX(){
        float value = Math.max(0f, Core.settings.getInt("ra2ann-ui-offset-x", 12)) * Scl.scl(1f);
        return Mathf.clamp(value, 0f, Math.max(0f, Core.graphics.getWidth() - panelWidth()));
    }

    private static float offsetY(){
        float value = Math.max(0f, Core.settings.getInt("ra2ann-ui-offset-y", 72)) * Scl.scl(1f);
        float safeHeight = Math.max(Scl.scl(40f), Core.graphics.getHeight());
        return Mathf.clamp(value, 0f, safeHeight - Scl.scl(20f));
    }

    private static boolean activeGame(){
        return state != null && state.isGame() && ui != null && ui.hudfrag != null && ui.hudfrag.shown;
    }

    private static float lineWidth(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-line-width", 3), 1f, 6f) * Scl.scl(1f);
    }

    private static float lineAlpha(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-line-alpha", 95), 35f, 100f) / 100f * opacity();
    }

    /** 小类强调色:左侧色条、连线与浮标共用,和卡片底色是两套独立设置。 */
    private static Color entryColor(String key){
        return Color.valueOf(accentHex(key));
    }

    /** 情报类(生产完成/兵厂训练/科研/胜负/区块)默认灰底,其余默认警报红底。 */
    private static boolean infoFamily(String colorKey){
        if(colorKey == null) return false;
        String key = colorKey.toLowerCase();
        return key.contains("factory") || key.contains("unit-ready") || key.contains("info");
    }

    private static Color backgroundColor(String colorKey){
        return Color.valueOf(cardBackgroundHex(colorKey));
    }

    /** 小类强调色的默认值;未知键(含 null)回落到默认强调色。 */
    static String accentDefaultHex(String colorKey){
        if(colorKey == null || colorKey.isEmpty()) return DEFAULT_ACCENT;
        for(String[] category : colorCategories){
            if(category[0].equals(colorKey)) return category[2];
        }
        return DEFAULT_ACCENT;
    }

    /** 小类当前生效的强调色(设置值无效时用默认值)。 */
    static String accentHex(String colorKey){
        String key = colorKey == null || colorKey.isEmpty() ? "ra2ann-accent-color" : colorKey;
        String fallback = accentDefaultHex(key);
        return cleanHex(Core.settings.getString(key, fallback), fallback);
    }

    /** 小类对应的卡片底色设置键;null 代表默认强调色小类。 */
    static String backgroundKey(String accentKey){
        String key = accentKey == null || accentKey.isEmpty() ? "ra2ann-accent-color" : accentKey;
        for(String[] category : colorCategories){
            if(category[0].equals(key)) return category[1];
        }
        return null;
    }

    /** 警报档默认底色(旧版“卡片底色”继续作为回退,老用户的自定义值不会丢)。 */
    static String alertBackgroundHex(){
        String legacy = legacyAlertColor();
        return cleanHex(Core.settings.getString("ra2ann-card-alert-color", legacy), legacy);
    }

    /** 情报档默认底色。 */
    static String infoBackgroundHex(){
        return cleanHex(Core.settings.getString("ra2ann-card-info-color", DEFAULT_INFO_BACKGROUND), DEFAULT_INFO_BACKGROUND);
    }

    /** 小类没有单独设置底色时沿用的默认底色。 */
    static String defaultBackgroundHex(String accentKey){
        return infoFamily(accentKey) ? infoBackgroundHex() : alertBackgroundHex();
    }

    /** 卡片实际使用的底色:优先小类自己的设置,否则回落到警报/情报默认底色。 */
    static String cardBackgroundHex(String accentKey){
        String key = backgroundKey(accentKey);
        if(key != null){
            String override = cleanHex(Core.settings.getString(key, ""));
            if(override != null) return override;
        }
        return defaultBackgroundHex(accentKey);
    }

    private static String legacyAlertColor(){
        return cleanHex(Core.settings.getString("ra2ann-card-color", DEFAULT_ALERT_BACKGROUND), DEFAULT_ALERT_BACKGROUND);
    }

    /** 归一化 6/8 位十六进制颜色;不合法返回 null。绘制路径每帧都会走,不用正则。 */
    private static String cleanHex(String value){
        if(value == null) return null;
        String clean = value.trim();
        if(clean.startsWith("#")) clean = clean.substring(1);
        int length = clean.length();
        if(length != 6 && length != 8) return null;
        for(int i = 0; i < length; i++){
            char c = clean.charAt(i);
            if((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) return null;
        }
        return clean;
    }

    private static String cleanHex(String value, String fallback){
        String clean = cleanHex(value);
        return clean == null ? fallback : clean;
    }

    private static String shortText(String message){
        if(message == null) return "";
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        int coordinate = clean.lastIndexOf("(");
        if(coordinate > 0) clean = clean.substring(0, coordinate).trim();
        clean = clean.replace("<", "").replace(">", "");
        return clean.length() > 28 ? clean.substring(0, 28) : clean;
    }

    private static class HudRoot extends WidgetGroup{
        final Seq<Entry> entries = new Seq<>();
        final ConnectionLayer connections = new ConnectionLayer(this);
        final MarkerLayer markers = new MarkerLayer(this);
        final WidgetGroup cards = new WidgetGroup();
        int lastMaxEntries = -1;
        float lastWidth = -1f;
        float lastScale = -1f;
        float lastOpacity = -1f;

        HudRoot(){
            setFillParent(true);
            touchable = Touchable.childrenOnly;
            addChild(connections);
            addChild(markers);
            addChild(cards);
        }

        void add(Entry entry){
            entries.add(entry);
            while(entries.size > maxEntries()) entries.remove(0);
            rebuildCards();
        }

        /** @return true when an identical alert is still fresh and was extended instead of duplicated */
        boolean refresh(String message, String markerText, float worldX, float worldY, boolean hasPosition, float panelUntil, float markerUntil){
            for(int i = entries.size - 1; i >= 0; i--){
                Entry entry = entries.get(i);
                if(!entry.message.equals(message) || Time.time - entry.addedAt > DEDUP_TICKS) continue;
                entry.panelUntil = Math.max(entry.panelUntil, panelUntil);
                entry.markerUntil = Math.max(entry.markerUntil, markerUntil);
                //同一条消息续期时把标记挪到本次事件的位置,否则连线会一直指向第一次出现的地点
                if(hasPosition){
                    entry.worldX = worldX;
                    entry.worldY = worldY;
                    entry.hasPosition = true;
                }
                entry.markerText = markerText;
                return true;
            }
            return false;
        }

        void clearEntries(){
            entries.clear();
            cards.clearChildren();
        }

        void rebuildCards(){
            cards.clearChildren();
            int limit = maxEntries();
            while(entries.size > limit) entries.remove(0);
            float width = panelWidth();
            float scale = panelScale();
            for(int i = entries.size - 1; i >= 0; i--){
                Entry entry = entries.get(i);
                entry.card = new AnnouncementCard(entry, width, scale);
                cards.addChild(entry.card);
            }
            lastMaxEntries = limit;
            lastWidth = width;
            lastScale = scale;
            layoutCards();
        }

        void layoutCards(){
            if(!Core.settings.getBool("ra2ann-ui-enabled", true)){
                cards.visible = false;
                return;
            }
            cards.visible = true;
            float y = getHeight() - offsetY();
            float x = offsetX();
            float gap = spacing();
            for(Entry entry : entries){
                if(entry.card == null || !entry.card.visible) continue;
                entry.card.setPosition(x, y, Align.topLeft);
                y -= entry.card.getHeight() + gap;
            }
        }

        @Override
        public void act(float delta){
            super.act(delta);
            if(!activeGame()){
                clearEntries();
                return;
            }

            boolean changed = false;
            for(int i = entries.size - 1; i >= 0; i--){
                Entry entry = entries.get(i);
                if(Time.time >= entry.panelUntil){
                    entries.remove(i);
                    changed = true;
                }
            }
            if(lastMaxEntries != maxEntries()
            || Math.abs(lastWidth - panelWidth()) > 0.5f
            || Math.abs(lastScale - panelScale()) > 0.001f
            || Math.abs(lastOpacity - opacity()) > 0.001f
            || changed){
                lastOpacity = opacity();
                rebuildCards();
            }
            layoutCards();
        }
    }

    private static class Entry{
        final String message;
        String markerText;
        float worldX, worldY;
        boolean hasPosition;
        final TextureRegion icon;
        final String colorKey;
        final float addedAt;
        float panelUntil, markerUntil;
        AnnouncementCard card;

        Entry(String message, String markerText, float worldX, float worldY, boolean hasPosition,
              TextureRegion icon, String colorKey, float addedAt, float panelUntil, float markerUntil){
            this.message = message;
            this.markerText = markerText;
            this.worldX = worldX;
            this.worldY = worldY;
            this.hasPosition = hasPosition;
            this.icon = icon;
            this.colorKey = colorKey;
            this.addedAt = addedAt;
            this.panelUntil = panelUntil;
            this.markerUntil = markerUntil;
        }
    }

    private static class AnnouncementCard extends Table{
        private final Entry entry;
        private final Label label;
        private final Image icon;
        private final float width;

        AnnouncementCard(Entry entry, float width, float scale){
            super();
            this.entry = entry;
            this.width = width;
            touchable = Touchable.disabled;
            margin(Scl.scl(6f));

            if(entry.icon != null){
                icon = new Image(entry.icon);
                add(icon).size(Scl.scl(32f)).padRight(Scl.scl(6f)).left().top();
            }else{
                icon = null;
            }
            label = new Label(entry.message, Styles.outlineLabel);
            label.setWrap(true);
            label.setAlignment(Align.left, Align.left);
            add(label).growX().minWidth(0f).left().top();
            setWidth(width);
            refreshLayout(scale);
        }

        void refreshLayout(float scale){
            label.setFontScale(scale);
            label.color.a = opacity();
            float contentWidth = Math.max(Scl.scl(40f), width - Scl.scl(24f) - (icon == null ? 0f : Scl.scl(38f)));
            label.setWidth(contentWidth);
            invalidateHierarchy();
            setHeight(Math.max(Scl.scl(40f), getPrefHeight()));
            layout();
        }

        @Override
        public void draw(){
            validate();
            float alpha = parentAlpha * opacity();
            float w = width, h = getHeight();
            float t = Scl.scl(1f);
            Color accent = entryColor(entry.colorKey);

            //竖向渐变底:警报红 / 情报灰,上下各自微调亮度
            baseColor.set(backgroundColor(entry.colorKey));
            panelTop.set(baseColor).lerp(Color.white, 0.10f);
            panelBottom.set(baseColor).lerp(Color.black, 0.20f);
            int strips = 8;
            float sh = h / strips;
            for(int i = 0; i < strips; i++){
                Draw.color(panelTop, panelBottom, (i + 0.5f) / strips);
                Draw.alpha(alpha);
                //条带轻微重叠 1px,避免缩放下的细缝
                Fill.rect(x + w / 2f, y + h - sh * (i + 0.5f), w, sh + t);
            }

            //1px 近黑外框
            if(borderColor == null) borderColor = Color.valueOf(BORDER_HEX);
            Draw.color(borderColor, alpha);
            Fill.rect(x + w / 2f, y + h - t / 2f, w, t);
            Fill.rect(x + w / 2f, y + t / 2f, w, t);
            Fill.rect(x + t / 2f, y + h / 2f, t, h);
            Fill.rect(x + w - t / 2f, y + h / 2f, t, h);

            //内侧左上高光 / 右下暗边,做出 RA2 面板的斜切感
            panelLight.set(baseColor).lerp(Color.white, 0.28f);
            Draw.color(panelLight, alpha);
            Fill.rect(x + w / 2f, y + h - 1.5f * t, w - 2f * t, t);
            Fill.rect(x + 1.5f * t, y + h / 2f, t, h - 2f * t);

            panelDark.set(baseColor).lerp(Color.black, 0.45f);
            Draw.color(panelDark, alpha);
            Fill.rect(x + w / 2f, y + 1.5f * t, w - 2f * t, t);
            Fill.rect(x + w - 1.5f * t, y + h / 2f, t, h - 2f * t);

            //左侧 4px 事件类型强调条
            Draw.color(accent, alpha);
            Fill.rect(x + Scl.scl(3f), y + h / 2f, Scl.scl(4f), Math.max(Scl.scl(8f), h - Scl.scl(6f)));
            Draw.reset();
            super.draw();
        }
    }

    private static class ConnectionLayer extends Element{
        private final HudRoot root;
        private final Vec2 projected = new Vec2();

        ConnectionLayer(HudRoot root){
            this.root = root;
            touchable = Touchable.disabled;
            cullable = false;
        }

        @Override
        public void draw(){
            if(!Core.settings.getBool("ra2ann-ui-enabled", true)
            || !root.cards.visible
            || !Core.settings.getBool("ra2ann-line-enabled", true)
            || !Core.settings.getBool("ra2ann-marker-enabled", true)) return;
            Draw.z(Layer.overlayUI - 1f);
            Color outline = Color.valueOf("111111");
            float width = lineWidth();
            Draw.color(outline, lineAlpha());
            Lines.stroke(width + Scl.scl(2f));
            for(Entry entry : root.entries){
                if(entry.card == null || !entry.hasPosition || Time.time >= entry.markerUntil) continue;
                Core.camera.project(projected.set(entry.worldX, entry.worldY));
                float tx = Mathf.clamp(projected.x, Scl.scl(6f), Core.graphics.getWidth() - Scl.scl(6f));
                float ty = Mathf.clamp(projected.y, Scl.scl(6f), Core.graphics.getHeight() - Scl.scl(6f));
                drawLine(entry.card.getX(Align.right), entry.card.getY(Align.center), tx, ty);
            }
            Lines.stroke(width);
            for(Entry entry : root.entries){
                if(entry.card == null || !entry.hasPosition || Time.time >= entry.markerUntil) continue;
                Core.camera.project(projected.set(entry.worldX, entry.worldY));
                float tx = Mathf.clamp(projected.x, Scl.scl(6f), Core.graphics.getWidth() - Scl.scl(6f));
                float ty = Mathf.clamp(projected.y, Scl.scl(6f), Core.graphics.getHeight() - Scl.scl(6f));
                Draw.color(entryColor(entry.colorKey), lineAlpha());
                drawLine(entry.card.getX(Align.right), entry.card.getY(Align.center), tx, ty);
            }
            Draw.reset();
        }

        private void drawLine(float sx, float sy, float tx, float ty){
            if(Core.settings.getBool("ra2ann-line-solid", true)) Lines.line(sx, sy, tx, ty);
            else Lines.dashLine(sx, sy, tx, ty, Math.max(4, (int)(Mathf.dst(sx, sy, tx, ty) / Scl.scl(24f))));
        }
    }

    private static class MarkerLayer extends Element{
        private final HudRoot root;
        private final Vec2 projected = new Vec2();

        MarkerLayer(HudRoot root){
            this.root = root;
            touchable = Touchable.disabled;
            cullable = false;
        }

        @Override
        public void draw(){
            if(!Core.settings.getBool("ra2ann-marker-enabled", true)) return;
            Draw.z(Layer.overlayUI + 1f);
            for(Entry entry : root.entries){
                if(!entry.hasPosition || Time.time >= entry.markerUntil) continue;
                Core.camera.project(projected.set(entry.worldX, entry.worldY));
                drawMarker(projected.x, projected.y, entry.markerText, entry.colorKey);
            }
            Draw.reset();
        }

        private void drawMarker(float x, float y, String text, String colorKey){
            float radius = Scl.scl(10f);
            float pulse = 0.75f + 0.25f * Mathf.absin(Time.time, 6f, 1f);
            float alpha = opacity();
            Color accent = entryColor(colorKey);
            Draw.color(accent, 0.18f * alpha);
            Fill.circle(x, y, radius * (1.5f + pulse));
            Draw.color(accent, 0.95f * alpha);
            Lines.stroke(Scl.scl(2f));
            Lines.circle(x, y, radius * pulse);
            Fill.circle(x, y, Scl.scl(3f));

            if(text == null || text.isEmpty() || Fonts.outline == null) return;
            Font font = Fonts.outline;
            GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
            boolean integerPositions = font.usesIntegerPositions();
            float oldScale = font.getScaleX();
            font.setUseIntegerPositions(false);
            font.getData().setScale(0.8f * panelScale() / Math.max(0.0001f, Scl.scl(1f)));
            layout.setText(font, text);
            boolean right = x < Core.graphics.getWidth() * 0.72f;
            float labelX = right ? x + Scl.scl(16f) : x - Scl.scl(16f);
            int align = right ? Align.left : Align.right;
            labelX = Mathf.clamp(labelX, Scl.scl(8f), Core.graphics.getWidth() - Scl.scl(8f));
            font.setColor(1f, 1f, 1f, alpha);
            font.draw(text, labelX, y + layout.height / 2f, 0f, align, false);
            font.getData().setScale(oldScale);
            font.setUseIntegerPositions(integerPositions);
            font.setColor(Color.white);
            Pools.free(layout);
        }
    }
}
