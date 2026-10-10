package com.example.offlineime;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Independently authored examples, never contacts or inferred field contents. */
final class NameCandidateEngine {
    private static final String SURNAMES = "王:wang 李:li 张:zhang 刘:liu 陈:chen 杨:yang 赵:zhao 黄:huang 周:zhou 吴:wu 徐:xu 孙:sun 胡:hu 朱:zhu 高:gao 林:lin 何:he 郭:guo 马:ma 罗:luo 梁:liang 宋:song 郑:zheng 谢:xie 韩:han 唐:tang 冯:feng 于:yu 董:dong 萧:xiao 肖:xiao 程:cheng 曹:cao 袁:yuan 邓:deng 许:xu 傅:fu 沈:shen 曾:zeng 彭:peng 吕:lv 苏:su 卢:lu 蒋:jiang 蔡:cai 贾:jia 丁:ding 魏:wei 薛:xue 叶:ye 阎:yan 余:yu 潘:pan 杜:du 戴:dai 夏:xia 钟:zhong 汪:wang 田:tian 任:ren 欧阳:ou_yang 司马:si_ma 上官:shang_guan 诸葛:zhu_ge";
    private static final String GIVEN = "伟:wei 芳:fang 娜:na 秀英:xiu_ying 敏:min 静:jing 丽:li 强:qiang 磊:lei 军:jun 洋:yang 勇:yong 艳:yan 杰:jie 涛:tao 明:ming 超:chao 秀兰:xiu_lan 霞:xia 平:ping 刚:gang 桂英:gui_ying 燕:yan 华:hua 辉:hui 欣:xin 宇:yu 晨:chen 浩:hao 凯:kai 婷:ting 雪:xue 宁:ning 安:an 轩:xuan 妍:yan 睿:rui 博:bo 文:wen 鑫:xin 子涵:zi_han 梓涵:zi_han 浩然:hao_ran 宇轩:yu_xuan 子轩:zi_xuan 欣怡:xin_yi 诗涵:shi_han 雨桐:yu_tong 雨欣:yu_xin 思涵:si_han 若曦:ruo_xi 晨曦:chen_xi 一诺:yi_nuo 佳怡:jia_yi 佳琪:jia_qi 子墨:zi_mo 梓轩:zi_xuan 俊杰:jun_jie 俊豪:jun_hao 志强:zhi_qiang 建国:jian_guo 建军:jian_jun 国庆:guo_qing 海燕:hai_yan 晓明:xiao_ming 晓红:xiao_hong 晓燕:xiao_yan 文博:wen_bo 博文:bo_wen 嘉宁:jia_ning 嘉怡:jia_yi 思远:si_yuan 致远:zhi_yuan 明轩:ming_xuan 天宇:tian_yu 皓宇:hao_yu 泽宇:ze_yu 泽楷:ze_kai 雨晴:yu_qing 语嫣:yu_yan";
    private static final List<PinyinDictionary.Entry> NAMES = names();
    private static final java.util.Map<Character, List<PinyinDictionary.Entry>> INDEX = index();
    private static java.util.Map<Character, List<PinyinDictionary.Entry>> index() {
        java.util.Map<Character, List<PinyinDictionary.Entry>> result = new java.util.HashMap<>();
        for (PinyinDictionary.Entry entry : NAMES) {
            char letter = entry.pronunciation.charAt(0);
            result.computeIfAbsent(letter, ignored -> new ArrayList<>()).add(entry);
            char digit = PinyinDictionary.toT9(String.valueOf(letter)).charAt(0);
            result.computeIfAbsent(digit, ignored -> new ArrayList<>()).add(entry);
        }
        return result;
    }
    private static List<PinyinDictionary.Entry> names() {
        ArrayList<PinyinDictionary.Entry> result = new ArrayList<>();
        int surnameRank = 0;
        for (String surname : SURNAMES.split(" ")) {
            String[] family = surname.split(":"); int givenRank = 0;
            for (String given : GIVEN.split(" ")) {
                String[] name = given.split(":");
                String reading = (family[1] + "_" + name[1]).replace('_', ' ');
                result.add(new PinyinDictionary.Entry(family[0] + name[0], 6500 - surnameRank * 24 - givenRank * 9)
                        .withInput(0, reading));
                givenRank++;
            }
            surnameRank++;
        }
        return Collections.unmodifiableList(result);
    }
    static List<PinyinDictionary.Entry> suggest(List<PinyinDictionary.Entry> base,
            PinyinPathEditor.Query constraint, String input, SharedPreferences settings) {
        return suggest(base, constraint, input, settings, false);
    }
    static List<PinyinDictionary.Entry> suggest(List<PinyinDictionary.Entry> base,
            PinyinPathEditor.Query constraint, String input, SharedPreferences settings, boolean forceFull) {
        if (input.isEmpty() || input.length() > 24) return base;
        boolean full = forceFull || settings.getBoolean("full_pinyin", true);
        boolean shortInput = settings.getBoolean("short_pinyin", true);
        boolean mixed = settings.getBoolean("mixed_pinyin", true);
        boolean doubles = settings.getBoolean("double_initials", true);
        ArrayList<PinyinDictionary.Entry> matches = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (PinyinDictionary.Entry name : INDEX.getOrDefault(Character.toLowerCase(input.charAt(0)), Collections.emptyList())) {
            QueryWork.check();
            PinyinInput.Match match = constraint == null
                    ? PinyinInput.match(input, name.pronunciation, full, shortInput, mixed, doubles, true)
                    : constraint.match(name.pronunciation, full, shortInput, mixed, doubles, true);
            if (match == null) continue;
            PinyinDictionary.Entry entry = new PinyinDictionary.Entry(name.word, name.score,
                    match.tier, false, name.corpusScore, match.tier < PinyinDictionary.TIER_PREFIX ? 8 : 29)
                    .withInput(match.consumed, name.pronunciation);
            entry.queryText = input;
            matches.add(entry); seen.add(entry.word);
        }
        for (PinyinDictionary.Entry entry : base) if (seen.add(entry.word)) matches.add(entry);
        return matches;
    }
}
