# RA2 Announcer 索菲亚战场播报

Mindustry 客户端 mod:用**红警2原版副官索菲亚(Zofia)的语音**播报战场事件,附带右侧事件卡片、世界浮标与按类别过滤的播报设置。

由 `Ra2Announcer`(高价值目标、自选单位、科技/战役/生产播报)与 `BattleVoice`(权重语音链、敌方集结检测、单控标点、事件卡片、类型白名单)合并而成,合并后的唯一工程就是本目录。

## 播报内容

| 类别 | 触发 | 语音(索菲亚原声) | 附带的具体类别 |
|---|---|---|---|
| 波次 | 新一波开始 / 前 5 秒预警 / 波次清空 | Enemy forces in your area / armor battalion detected / Objective complete | — |
| 基地 | 开局建基地 / 核心受击 / 核心告急 / 反应堆熔毁 / 持续缺电 | Battlefield Control Standby / Our base is under attack / Base defenses offline / extremely vulnerable here / low power | — |
| 损失 | 我方单位或建筑被摧毁 | Unit lost / Critical structure lost | **朗读具体单位/建筑名**,卡片列出 `名称×数量、…` |
| 受袭 | 我方单位被击中 | Be warned, comrade general(采矿单位走 miner under attack) | **朗读该单位名**,卡片聚合 `名称×数量` |
| 生产 | 兵厂开始训练 / 生产完成 | training / Unit ready | **朗读该单位名** |
| 集结 | 敌方成批控兵,或终点指向我方核心 | infantry/armor/air/fleet detected(按主力兵种选台词) | 主力单位名 + 数量 + 最高威胁类型 |
| 单控 | 敌方或队友手动接管单位/炮塔/建筑、指挥建筑 | 分类台词 / Reinforcements have arrived / Structure garrisoned | **朗读被接管对象的名字** |
| 高价值 | 规则命中的敌方单位/建筑出现或被摧毁 | 分类台词 / Beacon detected / Critical unit lost | **朗读目标名** |
| 自选单位 | 名单内敌方单位出现 / 被消灭 | 同上 | **朗读目标名** |
| 科技与战役 | 解锁科技 / 区块被入侵 / 区块占领 | New technology acquired / Enemy forces in your area / assumed command of this base | 区块名 |
| 胜负 | 胜利 / 失败 | Mission accomplished / Mission failed | — |
| 首领 | 首领波预警 / 首领被消灭 | 按空中或地面选 air armada / armor battalion,再接单位名 | **朗读首领单位名** |
| 敌方核心 | 敌方核心被摧毁 | Enemy base powered down | **朗读建筑名** |

固定台词全部来自红警2原版录音(索菲亚/Zofia,共 29 条);单位与建筑名沿用 TTS 名称包(`zh` 中文名 / `en` 英文名),两包共用同一批固定台词。

## 针对“播报过于频繁”的设计

1. **一次只播一条**:所有语音走 `Announcer` 的权重链,链与链之间强制静音间隔(设置项“两条播报之间的最小间隔”,默认 2 秒)。
2. **不排队、只抢占**:正在播报时低权重播报直接丢弃,只有权重更高且达到警报级的播报才打断。
3. **先聚合再播报**:单位受袭(逐发子弹的事件)按单位类型去重合并成一张卡片;生产、损失、集结都在短窗口内合并。
4. **按类型冷却**:受袭/生产/损失/单控/集结/高价值/单控都有独立的按类型冷却滑条,可自行调大。
5. **链内按真实时长排期**:每段音频用真实长度排下一条,语音不会互相压盖。
6. **同消息去重**:同一条卡片消息在 1.25 秒内重复触发只续期,不新增卡片。
7. **白名单过滤**:敌方进攻单位、我方受袭单位、我方死亡单位、我方建筑损失各有一套勾选白名单。

## 设置

设置 → **RA2 战场播报(索菲亚)**:

- **语音**:名称语音语言(中文/English)、语音音量、两条播报之间的最小间隔。
- **播报类别**:波次、基地、核心受击(含间隔)、单位损失、建筑损失、单位受袭、采矿单位受袭、生产完成、兵厂训练、敌方核心、首领、敌方集结(最小数量/间隔/核心半径)、单控(敌我分开)、建筑指挥、科技、战役、胜负。
- **目标与过滤**:高价值目标规则(`core,boss,t5,unit:reign,block:foreshadow`)、自选单位名单、四套类型过滤对话框。
- **界面与声音**:事件卡片、世界浮标、连线、卡片宽度/缩放/不透明度/持续时间/最大条数/间距/偏移、连线宽度与不透明度。
- **颜色**:卡片底色、默认强调色,以及波次/基地/进攻/核心威胁/单控/损失/高价值/兵厂/单位就绪/采矿/情报各自的强调色(点右侧色块编辑)。

## 语音素材

```powershell
# 固定台词:红警2原版索菲亚录音(按 HTTP Range 只下载用到的 29 段,约几 MB)
python tools/fetch_ra2_voice.py            # 全部
python tools/fetch_ra2_voice.py --list     # 查看 key → 原片段 → 原文
python tools/fetch_ra2_voice.py ann_wave   # 单独重取

# 单位/建筑名:从原版 bundle 重建词表 + edge-tts 生成
python tools/build_names.py
python tools/generate_voice.py --lang zh   # 中文(Xiaoxiao)
python tools/generate_voice.py --lang en   # 英文(Aria)

# 静态自检:双语 bundle 键一致性、设置键、消息键、音频文件是否齐全
python tools/verify_pack.py
```

素材来源:[HuggingFace `kingsznhone/Red-Alert-2-Full-Voice-Data`](https://huggingface.co/datasets/kingsznhone/Red-Alert-2-Full-Voice-Data)(RA2/YR 全语音数据集,含 `long_character_anno.txt` 文本转录)。数据集声明**禁止商用**;索菲亚台词是 Westwood/EA 的原版录音,请勿用于商业用途或再分发收费版本。

## 构建

```powershell
./gradlew classes     # 快速编译检查
./gradlew deploy      # build/libs/Ra2Announcer.jar + dist/Ra2Announcer.jar(桌面+安卓,含 classes.dex)
./gradlew build       # 额外产出 构建/Ra2Announcer/Ra2Announcer-dev.jar
python tools/verify_pack.py
```

- 依赖优先使用工作区源码产物(`../Mindustry-master/core/build/classes/java/main` + `../Arc/arc-core/build/libs/arc-core-1.0.jar`),缺失时回退 JitPack `MindustryJitpack:core:v159`。
- 安卓 dex 用 D8,查找顺序 `D8_PATH` → `ANDROID_SDK_ROOT/ANDROID_HOME` → 工作区根 `commandlinetools-win-*`。
- Java 17(`--release 17`),源码 UTF-8。

## 已知边界

- 客户端-only:`headless` 直接返回;多人下只播客户端能看到的事件(核心血量、电力走轮询,电力仅主机/单人有效)。
- 伤害事件是本地模拟的:我方单位受袭在本地客户端可见,极端网络情况下可能延迟或漏报。
- 原版没有“玩家直接接管炮塔”的独立事件,但 v8 的炮塔单控走 `BlockUnit`,已覆盖;纯微处理器逻辑控制不联网同步,不在播报范围内。
- 名称包按“内部名”查音频:缺失的名字会静默跳过(卡片文字仍显示名称),用 `python tools/verify_pack.py` 可以自查覆盖率。
- 同一条目在两种语言下共用红警原声固定台词,语言设置只切换单位/建筑名的朗读语言。

## 许可

代码:MIT。固定台词为红警2原版录音(仅供本地个人使用,禁止商用);名称语音由 Microsoft Edge TTS 生成。
