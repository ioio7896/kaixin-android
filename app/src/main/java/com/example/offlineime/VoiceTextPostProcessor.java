package com.example.offlineime;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline Chinese ASR post-processor: spoken punctuation, clause/sentence punctuation, and ITN. */
final class VoiceTextPostProcessor {
    private static final String SENTENCE_ENDERS = "。？！；,.!?;：:\n";
    private static final String[] QUESTION_PATTERNS = {
            "为什么", "为啥", "怎么", "怎样", "怎么样", "多少", "几点", "几个", "几天", "几年",
            "哪里", "哪儿", "哪个", "哪位", "哪些", "哪天", "谁", "什么", "啥", "是不是", "能不能",
            "可不可以", "有没有", "好不好", "行不行", "对不对", "会不会", "要不要", "来不来",
            "去不去", "忙不忙", "贵不贵", "远不远", "是否", "难道", "何必", "几号", "星期几", "周几"
    };
    private static final String[] EXCLAMATION_PATTERNS = {
            "太好了", "真棒", "太棒了", "真不错", "太厉害了", "天哪", "糟糕", "加油",
            "小心", "快点", "哈哈", "太开心了", "真好看", "太赞了", "谢谢你"
    };

    private static final Pattern YEAR_PATTERN =
            Pattern.compile("([零〇一二三四五六七八九]{4})年");
    private static final Pattern PERCENT_PATTERN =
            Pattern.compile("百分之([零一二两三四五六七八九十百千万点]+)");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("([一二三四五六七八九十]{1,3})月([一二三四五六七八九十]{1,3})(日|号)");
    // A bare string of digits after 点 is decimal unless the minutes have a time marker.
    private static final Pattern TIME_PATTERN =
            Pattern.compile("(?<![零一二两三四五六七八九十百千万])([零一二两三四五六七八九十]{1,3})点"
                    + "(半|钟|([零一二三四五六七八九十]{1,3})分(?!钟)"
                    + "|([一二三四五]?十[一二三四五六七八九]?)(?![零一二三四五六七八九十分]))");
    private static final Pattern DECIMAL_PATTERN =
            Pattern.compile("([零一二两三四五六七八九十百千万]+)点([零一二三四五六七八九]++)(?![十半钟]|分(?!钟))");
    private static final Pattern DIGIT_RUN_PATTERN =
            Pattern.compile("[零〇一幺二三四五六七八九]{4,}");
    private static final Pattern ORDINAL_PATTERN =
            Pattern.compile("第([一二三四五六七八九十百千万]{2,})");
    private static final Pattern STRICT_UNIT_PATTERN =
            Pattern.compile("([一二两三四五六七八九十百千万]+)(块钱|元|角|美元|欧元|米|厘米|毫米|公里|千米|千克|公斤|克|吨|毫升|升|度|分钟|小时|秒|页|楼|层|室|路|平米|平方米|岁)");
    private static final Pattern GENERAL_UNIT_PATTERN =
            Pattern.compile("([一二两三四五六七八九十百千万]{2,})(块|斤|天|年|个月|周|次|倍|个|名|人|条|张|件|份|本|台|辆|家|套|座)");

    private VoiceTextPostProcessor() {}

    static String insertClauseCommas(String raw, List<Integer> pauseOffsets, boolean autoPunct) {
        if (!autoPunct || raw == null || raw.isEmpty() || pauseOffsets == null || pauseOffsets.isEmpty()) {
            return raw == null ? "" : raw;
        }
        StringBuilder sb = new StringBuilder(raw.length() + pauseOffsets.size());
        int cursor = 0;
        int lastBreak = 0;
        for (int i = 0; i < pauseOffsets.size(); i++) {
            int pos = pauseOffsets.get(i);
            if (pos <= cursor || pos >= raw.length()) continue;
            sb.append(raw, cursor, pos);
            cursor = pos;
            if (pos - lastBreak >= 4) {
                char prev = sb.length() > 0 ? sb.charAt(sb.length() - 1) : 0;
                char next = raw.charAt(pos);
                if (SENTENCE_ENDERS.indexOf(prev) < 0 && prev != '，' && prev != '、'
                        && SENTENCE_ENDERS.indexOf(next) < 0 && next != '，' && next != '、') {
                    sb.append('，');
                    lastBreak = pos;
                }
            }
        }
        if (cursor < raw.length()) sb.append(raw, cursor, raw.length());
        return sb.toString();
    }

    static String process(String text, boolean spokenPunctuation, boolean smartNumbers) {
        if (text == null || text.isEmpty()) return "";
        String result = text;
        if (spokenPunctuation) result = applySpokenPunctuation(result);
        if (smartNumbers) result = normalizeNumbers(result);
        return cleanRedundantPunctuation(result);
    }

    static void finishSentence(StringBuilder text, boolean autoPunctuation) {
        if (!autoPunctuation) return;
        int length = text.length();
        if (length == 0) return;
        char last = text.charAt(length - 1);
        if (last == '，' || last == '、') {
            text.deleteCharAt(length - 1);
            length--;
            if (length == 0) return;
            last = text.charAt(length - 1);
        }
        if (SENTENCE_ENDERS.indexOf(last) >= 0) return;
        String clause = lastSentenceSlice(text);
        if (clause.isEmpty()) return;
        text.append(classifyEnding(clause));
    }

    private static String lastSentenceSlice(CharSequence text) {
        int start = text.length();
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == '。' || c == '？' || c == '！' || c == '\n' || c == '?' || c == '!') break;
            start--;
        }
        return text.subSequence(start, text.length()).toString().trim();
    }

    private static char classifyEnding(String clause) {
        if (clause.isEmpty()) return '。';
        char last = clause.charAt(clause.length() - 1);
        if (last == '吗' || last == '呢' || last == '嘛') return '？';
        if (last == '么' && !clause.endsWith("那么") && !clause.endsWith("这么")) return '？';
        for (String cue : QUESTION_PATTERNS) {
            if (clause.contains(cue)) return '？';
        }
        for (String cue : EXCLAMATION_PATTERNS) {
            if (clause.contains(cue)) return '！';
        }
        if ((last == '啊' || last == '呀' || last == '啦' || last == '哇')
                && (clause.contains("太") || clause.contains("真") || clause.contains("多么"))) {
            return '！';
        }
        return '。';
    }

    private static String applySpokenPunctuation(String input) {
        String out = input
                .replace("另起一行", "\n")
                .replace("换行", "\n")
                .replace("感叹号", "！")
                .replace("叹号", "！")
                .replace("问号", "？")
                .replace("句号", "。")
                .replace("逗号", "，")
                .replace("冒号", "：")
                .replace("分号", "；")
                .replace("顿号", "、");
        return out;
    }

    private static String cleanRedundantPunctuation(String input) {
        if (input.length() < 2) return input;
        StringBuilder sb = new StringBuilder(input.length());
        char prev = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == prev && "，。？！；：、".indexOf(c) >= 0) continue;
            if ((prev == '，' || prev == '、') && "。？！；".indexOf(c) >= 0) {
                sb.setCharAt(sb.length() - 1, c);
                prev = c;
                continue;
            }
            sb.append(c);
            prev = c;
        }
        return sb.toString();
    }

    static String normalizeNumbers(String input) {
        if (input == null || input.length() < 2) return input == null ? "" : input;
        String s = replaceYears(input);
        s = replacePercent(s);
        s = replaceDates(s);
        s = replaceTimes(s);
        s = replaceDecimals(s);
        s = replaceDigitRuns(s);
        s = replaceOrdinals(s);
        s = replaceStrictUnits(s);
        s = replaceGeneralUnits(s);
        return s;
    }

    private static String replaceYears(String input) {
        Matcher m = YEAR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String digits = spokenDigitsToAscii(m.group(1), false);
            m.appendReplacement(sb, digits != null ? digits + "年" : Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replacePercent(String input) {
        Matcher m = PERCENT_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String num = parseChineseDecimalOrInt(m.group(1));
            m.appendReplacement(sb, num != null ? num + "%" : Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceDates(String input) {
        Matcher m = DATE_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long month = parseChineseInt(m.group(1));
            long day = parseChineseInt(m.group(2));
            if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                m.appendReplacement(sb, month + "月" + day + m.group(3));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceTimes(String input) {
        Matcher m = TIME_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long hour = parseChineseInt(m.group(1));
            if (hour < 0 || hour > 24) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String tail = m.group(2);
            if ("半".equals(tail) || "钟".equals(tail)) {
                m.appendReplacement(sb, hour + "点" + tail);
            } else {
                boolean hasFen = m.group(3) != null;
                String minuteText = hasFen ? m.group(3) : m.group(4);
                boolean leadingZero = minuteText.length() == 2 && minuteText.charAt(0) == '零'
                        && digitValue(minuteText.charAt(1), false) >= 0;
                long minute = parseChineseInt(leadingZero ? minuteText.substring(1) : minuteText);
                if (minute >= 0 && minute <= 59) {
                    m.appendReplacement(sb, hour + "点" + (leadingZero ? "0" : "")
                            + minute + (hasFen ? "分" : ""));
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceDecimals(String input) {
        Matcher m = DECIMAL_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long intPart = parseChineseInt(m.group(1));
            String fracPart = spokenDigitsToAscii(m.group(2), false);
            if (intPart >= 0 && fracPart != null) {
                m.appendReplacement(sb, intPart + "." + fracPart);
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceDigitRuns(String input) {
        Matcher m = DIGIT_RUN_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String digits = spokenDigitsToAscii(m.group(0), true);
            m.appendReplacement(sb, digits != null ? digits : Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceOrdinals(String input) {
        Matcher m = ORDINAL_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long val = parseChineseInt(m.group(1));
            if (val >= 10) m.appendReplacement(sb, "第" + val);
            else m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceStrictUnits(String input) {
        Matcher m = STRICT_UNIT_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rawNum = m.group(1);
            String unit = m.group(2);
            if ("一度".equals(m.group(0))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            long val = parseChineseInt(rawNum);
            if (val >= 0 && (val >= 2 || rawNum.length() >= 2 || !"度".equals(unit))) {
                m.appendReplacement(sb, val + unit);
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceGeneralUnits(String input) {
        Matcher m = GENERAL_UNIT_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long val = parseChineseInt(m.group(1));
            if (val >= 10) m.appendReplacement(sb, val + m.group(2));
            else m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String parseChineseDecimalOrInt(String text) {
        int dot = text.indexOf('点');
        if (dot >= 0) {
            long intPart = parseChineseInt(text.substring(0, dot));
            String frac = spokenDigitsToAscii(text.substring(dot + 1), false);
            return intPart >= 0 && frac != null ? intPart + "." + frac : null;
        }
        long val = parseChineseInt(text);
        return val >= 0 ? Long.toString(val) : null;
    }

    private static String spokenDigitsToAscii(String text, boolean allowYao) {
        if (text == null || text.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            int d = digitValue(text.charAt(i), allowYao);
            if (d < 0) return null;
            sb.append((char) ('0' + d));
        }
        return sb.toString();
    }

    private static int digitValue(char c, boolean allowYao) {
        switch (c) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '幺': return allowYao ? 1 : -1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    private static long parseChineseInt(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (text.length() == 1 && text.charAt(0) == '十') return 10;
        // Handle pure digit sequences like "二三" -> invalid as compound unless 1 char
        long total = 0;
        long section = 0;
        long number = -1;
        boolean sawUnit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int d = digitValue(c, false);
            if (d >= 0) {
                if (number >= 0) return -1; // consecutive digits without unit, e.g. "一二"
                number = d;
                continue;
            }
            sawUnit = true;
            long unit;
            switch (c) {
                case '十': unit = 10; break;
                case '百': unit = 100; break;
                case '千': unit = 1000; break;
                case '万':
                    section += Math.max(number, 0);
                    if (section == 0) section = 1;
                    total += section * 10000L;
                    section = 0;
                    number = -1;
                    continue;
                default: return -1;
            }
            if (number < 0) number = (unit == 10 && section == 0 && total == 0) ? 1 : 0;
            section += number * unit;
            number = -1;
        }
        if (number >= 0) {
            // Spoken shorthand: "一百五" -> 150, "两千三" -> 2300, "三万五" -> 35000
            if (sawUnit && text.length() >= 2 && text.charAt(text.length() - 2) == '百') {
                section += number * 10L;
            } else if (sawUnit && text.length() >= 2 && text.charAt(text.length() - 2) == '千') {
                section += number * 100L;
            } else if (sawUnit && text.length() >= 2 && text.charAt(text.length() - 2) == '万') {
                total += number * 1000L;
            } else {
                section += number;
            }
        }
        return total + section;
    }
}
