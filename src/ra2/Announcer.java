package ra2;

import arc.Core;
import arc.audio.Sound;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.gen.Sounds;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.tree;

/**
 * 声音库 + 权重链调度 + 全局限流。
 *
 * <p>固定台词是红警2原版副官“索菲亚”(Zofia)的录音,语言无关,统一放在 {@code assets/sounds/<key>.ogg};
 * 单位/建筑名朗读仍是 TTS 名称包,按 {@code assets/sounds/<lang>/name-*.ogg} 分语言存放。</p>
 *
 * <p>限流规则(需求“播报过于频繁”):同一时刻只播一条播报链,链与链之间至少留
 * {@code ra2ann-voice-gap} 秒静音;正在播报时不排队,只允许权重更高且达到警报级的播报抢占;
 * 同一段音频在 {@link #MIN_REPLAY} 内不会重复播放。链内每段音频按真实时长排期,
 * 不再用固定间隔堆叠,避免语音互相压盖。</p>
 */
public final class Announcer{
    public static final int P_ATTACK = 100;
    public static final int P_CONTROL = 80;
    public static final int P_ALARM = 60;
    public static final int P_INFO = 40;

    /** shipped fixed-line recordings (original RA2 Zofia clips); aliases are resolved at load time */
    static final String[] FIXED_LINES = {
        "ann_base", "ann_wave", "ann_wave_warn", "ann_wave_cleared",
        "ann_core_attack", "ann_core_critical", "ann_core_threat",
        "ann_reactor", "ann_low_power",
        "ann_unit_lost", "ann_structure_lost", "ann_unit_attack", "ann_miner_attack",
        "ann_unit_ready", "ann_training", "ann_cancel",
        "ann_research", "ann_victory", "ann_defeat",
        "ann_enemy_base", "ann_boss_kill", "ann_sector_captured",
        "ann_high_value_block", "ann_watch_destroyed",
        "ann_control_friendly", "ann_control_building",
        "ann_force_infantry", "ann_force_air", "ann_force_naval"
    };

    /** graceful aliases so a language pack missing a clip still chains into complete sentences */
    private static final String[][] ALIASES = {
        {"ann_force_armor", "ann_wave_warn"},
        {"ann_boss", "ann_force_armor"},
        {"ann_sector", "ann_wave"},
    };

    /** silence between two clips of the same chain, in ticks */
    private static final float CHAIN_GAP = 5f;
    /** used when the audio backend cannot report a clip length */
    private static final float FALLBACK_LENGTH = 70f;
    /** the same clip never retriggers faster than this, in ticks */
    private static final float MIN_REPLAY = 90f;
    /** a chain is a sentence plus its subject name; more than this is a bug, not a sentence */
    private static final int MAX_CHAIN = 3;

    private static final ObjectMap<String, Sound> sounds = new ObjectMap<>();
    private static final ObjectMap<String, Long> lastPlayed = new ObjectMap<>();
    private static final ObjectMap<String, Float> lastClipAt = new ObjectMap<>();
    private static final Seq<PendingClip> pending = new Seq<>();

    private static int chainPriority = -1;
    private static float chainUntil = -1f;

    private Announcer(){
    }

    public static boolean enabled(){
        return Core.settings.getBool("ra2ann-enabled", true);
    }

    public static float volume(){
        return Mathf.clamp(Core.settings.getInt("ra2ann-voice-volume", 100), 0, 100) / 100f;
    }

    /** Name-clip language ("zh" or "en"); the fixed RA2 lines are shared by both packs. */
    public static String nameLang(){
        return "en".equals(Core.settings.getString("ra2ann-name-lang", "zh")) ? "en" : "zh";
    }

    public static void load(){
        reload();
    }

    /** Re-resolves every clip; call after the name-pack language changed. */
    public static void reload(){
        sounds.clear();
        for(String name : FIXED_LINES) loadClip(name, name);
        loadNames(nameLang());
        for(String[] alias : ALIASES) loadAlias(alias[0], alias[1]);
        // second pass: anything still unresolved falls back to the generic wave line
        for(String[] alias : ALIASES){
            if(!has(alias[0])) loadAlias(alias[0], "ann_wave");
        }
    }

    private static void loadNames(String lang){
        String other = "en".equals(lang) ? "zh" : "en";
        for(var type : content.units()){
            loadName(lang, other, "name-unit-" + type.name);
        }
        for(var block : content.blocks()){
            loadName(lang, other, "name-block-" + block.name);
        }
    }

    private static void loadName(String lang, String other, String key){
        Sound sound = loadSound(lang + "/" + key);
        if(sound == null) sound = loadSound(other + "/" + key);
        if(sound != null) sounds.put(key, sound);
    }

    private static void loadClip(String key, String file){
        Sound sound = loadSound(file);
        if(sound != null) sounds.put(key, sound);
    }

    /** Resolves one clip; missing files resolve to null so aliases and fallbacks can react. */
    private static Sound loadSound(String file){
        try{
            if(!exists(file)) return null;
            Sound sound = tree.loadSound(file);
            if(sound != null && sound != Sounds.none) return sound;
        }catch(Throwable t){
            // never let a broken clip break event handling
        }
        return null;
    }

    private static boolean exists(String file){
        try{
            return tree.get("sounds/" + file + ".ogg").exists() || tree.get("sounds/" + file + ".mp3").exists();
        }catch(Throwable t){
            return false;
        }
    }

    private static void loadAlias(String name, String source){
        Sound sound = sounds.get(source);
        if(sound != null) sounds.put(name, sound);
    }

    public static boolean has(String name){
        Sound sound = sounds.get(name);
        return sound != null && sound != Sounds.none;
    }

    /**
     * Plays a chain of clips as one announcement.
     *
     * @param cooldownKey cooldown bucket; also gates preemption so throttled announcements never interrupt
     * @return true when the chain was accepted
     */
    public static boolean chain(int priority, long cooldownMs, String cooldownKey, String... keys){
        if(keys == null || keys.length == 0) return false;
        if(!enabled() || !state.isGame()) return false;

        long now = Time.millis();
        if(cooldownMs > 0 && cooldownKey != null && now - lastPlayed.get(cooldownKey, 0L) < cooldownMs) return false;

        float time = Time.time;
        float gap = Mathf.clamp(Core.settings.getInt("ra2ann-voice-gap", 2), 0, 8) * 60f;
        if(time < chainUntil + gap && (priority <= chainPriority || priority < P_ALARM)){
            // one announcement at a time: drop instead of queueing, so nothing piles up into a wall of speech
            return false;
        }

        float at = time + 2f;
        Seq<PendingClip> queued = new Seq<>(MAX_CHAIN);
        for(String key : keys){
            if(key == null) continue;
            Sound sound = sounds.get(key);
            if(sound == null || sound == Sounds.none) continue;
            if(time - lastClipAt.get(key, -1_000_000f) < MIN_REPLAY) continue;
            queued.add(new PendingClip(at, key, sound));
            at += length(sound) + CHAIN_GAP;
            if(queued.size >= MAX_CHAIN) break;
        }
        if(queued.isEmpty()) return false;

        if(cooldownKey != null) lastPlayed.put(cooldownKey, now);
        if(priority >= chainPriority) pending.clear();
        for(PendingClip clip : queued) lastClipAt.put(clip.key, clip.at);
        pending.addAll(queued);
        chainPriority = priority;
        chainUntil = at;
        return true;
    }

    /** Single-clip convenience wrapper. */
    public static boolean play(int priority, long cooldownMs, String cooldownKey, String key){
        return chain(priority, cooldownMs, cooldownKey, key);
    }

    private static float length(Sound sound){
        float seconds = 0f;
        try{
            seconds = sound.getLength();
        }catch(Throwable ignored){
        }
        return seconds > 0.05f && seconds < 20f ? seconds * 60f : FALLBACK_LENGTH;
    }

    /** Drains due clips every frame. */
    public static void update(){
        if(pending.isEmpty()) return;
        float volume = volume();
        for(int i = pending.size - 1; i >= 0; i--){
            PendingClip clip = pending.get(i);
            if(Time.time < clip.at) continue;
            pending.remove(i);
            if(volume <= 0f) continue;
            try{
                clip.sound.play(volume);
            }catch(Throwable ignored){
            }
        }
        if(pending.isEmpty()) chainPriority = -1;
    }

    public static void reset(){
        lastPlayed.clear();
        lastClipAt.clear();
        pending.clear();
        chainPriority = -1;
        chainUntil = -1f;
    }

    private static class PendingClip{
        final float at;
        final String key;
        final Sound sound;

        PendingClip(float at, String key, Sound sound){
            this.at = at;
            this.key = key;
            this.sound = sound;
        }
    }
}
