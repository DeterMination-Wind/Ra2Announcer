# RA2 Announcer(索菲亚战场播报)项目说明

Mindustry v8/v159 客户端 Java mod:红警2原版副官索菲亚(Zofia)语音的战场播报。
本目录是 `BattleVoice` 与旧版 `Ra2Announcer` 合并后的唯一工程 —— `../BattleVoice` 已并入这里,不再单独维护。
功能、边界与素材来源见 `README.md`(英文)与 `README_zh.md`(中文)。

## 文件布局

- `src/ra2/` — 全部 Java 源码(flat src 布局,包名 `ra2`):
  - `Ra2Announcer.java` 主类:`Mod` 入口、事件编排、设置页(`bekBuildSettings`)、颜色编辑器、高价值目标与自选单位判定、`categoryLine()` 兵种分类台词
  - `Announcer.java` 声音库 + 权重链调度 + 全局限流(最小间隔、真实时长排期、同片段最短重播)
  - `EventFeedOverlay.java` 右侧卡片/浮标/连线(+ 同消息去重)
  - `ThreatScanner.java` 敌方集结聚类与核心威胁(按同步终点聚类,不依赖 `CommandAI.group`)
  - `ControlWatch.java` 单控检测(`UnitControlEvent` / `BuildingCommandEvent`)
  - `LossTracker.java` 我方损失合并播报(卡片带具体类型名,语音念名可选)
  - `UnitReports.java` 生产完成与“我方单位受袭”的聚合播报
  - `TypeFilters.java` 四套白名单过滤 + 勾选对话框
  - `SettingsRows.java` 设置页自定义行(分区标题/色块/按钮)的注册包装,见“代码约束”
  - `Texts.java` `名称×数量、…` 列表文本工具
- `assets/sounds/ann_*.ogg` — 红警2原版索菲亚固定台词(29 段,与语言无关)
- `assets/sounds/zh/`、`assets/sounds/en/` — 单位/建筑名 TTS 名称包(各 60 单位 + 231 建筑)
- `assets/bundles/` — `bundle.properties`(英)/ `bundle_zh_CN.properties`(简中)/ `bundle_zh_TW.properties`(繁中,由简体经 OpenCC 转换),键前缀 `ra2ann.` / `setting.ra2ann-*.name`
- `tools/fetch_ra2_voice.py` — 从 HuggingFace 数据集按 HTTP Range 只取需要的索菲亚片段并转 ogg
- `tools/build_names.py` — 从 `../Mindustry-master/core/assets/bundles` 重建名称词表
- `tools/generate_voice.py` — edge-tts 生成名称语音(仅名称,固定台词不走 TTS)
- `tools/verify_pack.py` — 静态自检(bundle 多语键一致、设置键、消息键、音频齐全、别名链)

## 构建命令

```powershell
./gradlew classes     # 快速编译检查
./gradlew deploy      # build/libs/Ra2Announcer.jar、dist/Ra2Announcer.jar(桌面+安卓,含 classes.dex)
./gradlew build       # 额外经 finalizedBy 产出 构建/Ra2Announcer/Ra2Announcer-dev.jar
python tools/verify_pack.py
```

- 依赖解析:优先本地源码产物(`../Mindustry-master/core/build/classes/java/main` + `../Arc/arc-core/build/libs/arc-core-1.0.jar`),否则 JitPack `com.github.Anuken.MindustryJitpack:core:v159`。
- D8 查找:`D8_PATH` → `ANDROID_SDK_ROOT`/`ANDROID_HOME` → 工作区根 `commandlinetools-win-*/cmdline-tools/bin/d8.bat`。
- Java 17(`--release 17`),源码 UTF-8。jar 内资源布局是 `sounds/`、`bundles/`(与已发布的 v1.0.1 jar 一致,不要改成 `assets/` 前缀)。

## 代码约束

- 客户端-only:`init()` 里 `headless` 直接返回;不要引入 `javax.sound`(破坏安卓合并 jar)。
- 多人客户端可见性是硬约束:新增检测先确认数据已同步(`UnitControlEvent`/`BuildingCommandEvent` 为 v8 双向广播,敌方集结用 `CommandAI` 的终点而非不联网的 `group`)。
- **播报一律走 `Announcer.chain/play`(权重 + 冷却 + 全局限流)+ `EventFeedOverlay.show`(卡片)**,不要直接 `Sound.play()`。
- **限流是需求的一部分**:高频事件(受袭、生产、损失、集结、单控)必须先聚合再播报;新事件要给出按类型冷却,并复用 `Announcer` 的最小间隔机制,避免退化成“每发子弹一句话”。
- **语音不需要准确,准确信息归提示框**:每条播报先落 `EventFeedOverlay.show` —— 卡片文字必须带具体名称/数量/玩家/区块/波次号;语音只播索菲亚固定台词,名称片段默认不播(`ra2ann-voice-names` 打开才接在台词后)。卡片被关掉时由 `EventFeedOverlay` 用 `ui.showInfoToast` 兜底(`ra2ann-toast-mode`)。任何“细节只能从语音听出来”的实现都算不符合需求。
- 声音键是逻辑名(`ann_wave` / `name-unit-*`),固定台词文件在 `assets/sounds/` 根目录、名称包在 `assets/sounds/<lang>/`;`Announcer.ALIASES` 负责缺片段时的兜底,不要在键名里混语言前缀。
- 设置键前缀 `ra2ann-`;bundle 键前缀 `ra2ann.`;声音键 `ann_*` / `name-unit-*` / `name-block-*`。
- **设置页里的自定义行(分区标题、颜色色块、语言按钮、过滤按钮、测试按钮)必须经 `SettingsRows.title/custom` 注册成 `SettingsTable.Setting`,不要直接 `table.add(...)`/`table.button(...)`**:原版 v8 的 `pref()` 每次都 `rebuild()`,MindustryX 把重建推迟到绘制时(`act()` → `build()`),两者都会 `clearChildren()` 后只按注册列表重建 —— 直加的行会被抹掉(表现为 MindustryX 上“颜色/标题/按钮整段消失”)。
- 新增语音行:先在 `tools/fetch_ra2_voice.py` 的 `CLIPS` 里选一段索菲亚原声(用 `--list` 看文本)→ 生成 ogg → `Announcer.FIXED_LINES` 注册 → 三个 bundle 补 `setting.*`/消息键 → `python tools/verify_pack.py` 通过。
- `bekBundled` 钩子保持原样(公共静态布尔 + `bekBuildSettings(SettingsTable)` + `addCategory` 守卫),供并入 Neon 时复用。

## 验证清单

1. `./gradlew classes` 无错误;`python tools/verify_pack.py` 全绿。
2. `deploy` 后检查 `build/libs/Ra2Announcer.jar` 含 `mod.hjson`、`ra2/*.class`、`classes.dex`、`sounds/ann_*.ogg`、`sounds/zh|en/name-*.ogg`、`bundles/`。
3. 单人装 mod:波次/核心受击/测试播报按钮出声;右侧卡片与浮标出现;同一事件短时间内不刷屏。
4. 触发一次单位损失与一次生产:卡片列出具体“名称×数量”;语音只播索菲亚台词(除非打开“语音朗读具体名称”)。
5. 关掉“右侧事件卡片”后触发播报:原版提示框仍显示同一条准确文字。
6. 局域网双开:客户端能收到敌方单控 `UnitControlEvent`、敌方集结聚类播报(MP 可见性核心验证)。
7. 设置页四套过滤对话框可勾选保存;透明度/缩放滑条即时生效;名称语音语言切换后重新加载名称包。
8. 打开设置页确认 5 个分区标题、“颜色”区 15 个色块(点开可改色且保存后色块文字/颜色立即更新)、名称语音语言按钮、4 个过滤按钮与测试播报按钮全部在位 —— 原版与 MindustryX 都要过(设置表重建时最容易被抹掉)。

## 提交约定

本仓库在 git 上(远端 `DeterMination-Wind/Ra2Announcer`),提交信息用 `Ra2Announcer: ...`。
默认只做本地 dev 构建(`mod.hjson` 保持 `ra2-announcer-dev`/`0.0.0`),不发布、不打 tag。
