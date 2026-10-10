package com.example.offlineime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashMap;

/** Dependency-free continuous pinyin splitter with bounded dynamic programming. */
final class PinyinLexer {
    // Ordinary full syllables for keyboard paths. Syllabic interjections
    // (m/n/ng/hm/hng) stay in the corpus but must not create full input paths.
    // Keeping this independent of corpus contents makes segmentation stable.
    static final String[] STANDARD_SYLLABLES = (
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu "
          + "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo "
          + "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo "
          + "e ei en eng er fa fan fang fei fen feng fo fou fu "
          + "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo "
          + "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo "
          + "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun "
          + "ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo "
          + "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve "
          + "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu "
          + "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nuo nv nve "
          + "o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu "
          + "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun "
          + "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo "
          + "sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo "
          + "ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo "
          + "wa wai wan wang wei wen weng wo wu "
          + "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun "
          + "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun "
          + "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"
    ).split(" ");
    private final Set<String> syllables = new HashSet<>();
    private final int maxLength;
    private final MapCache cache = new MapCache(128);
    private final ByteBudgetCache<SplitState> incremental = new ByteBudgetCache<>(2 * 1024 * 1024, 16, state -> {
        int bytes = 64;
        for (List<List<String>> paths : state.dp) bytes += ByteBudgetCache.paths(paths);
        return bytes;
    });
    private static final class SplitState {
        final String input;
        final List<List<String>>[] dp;
        SplitState(String input, List<List<String>>[] dp) { this.input = input; this.dp = dp; }
    }

    PinyinLexer(Collection<String> ignoredCorpusKeys) {
        int max = 0;
        for (String syllable : STANDARD_SYLLABLES) {
            syllables.add(syllable);
            max = Math.max(max, syllable.length());
        }
        // Keep the constructor compatible with callers, but corpus keys also
        // contain abbreviations, whole words and Latin names. They must never
        // extend the full-syllable grammar.
        maxLength = max;
    }

    List<List<String>> splitAll(String input, int limit) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.isEmpty() || normalized.length() > 192 || limit <= 0) return java.util.Collections.emptyList();
        String cacheKey = normalized + "#" + limit;
        List<List<String>> cached = cache.get(cacheKey);
        if (cached != null) return cached;
        // Apostrophe explicitly separates syllables (xi'an must not become xian).
        String[] forced = normalized.replaceAll("'+$", "").split("'", -1);
        if (forced.length > 1) {
            List<List<String>> result = new ArrayList<>();
            result.add(new ArrayList<String>());
            for (String part : forced) {
                List<List<String>> sub = syllables.contains(part)
                        ? java.util.Collections.singletonList(java.util.Collections.singletonList(part))
                        : splitAll(part, limit);
                if (sub.isEmpty()) return java.util.Collections.emptyList();
                List<List<String>> next = new ArrayList<>();
                for (List<String> a : result) for (List<String> b : sub) {
                    if (next.size() >= limit) break;
                    ArrayList<String> merged = new ArrayList<>(a); merged.addAll(b); next.add(merged);
                }
                result = next;
            }
            cache.put(cacheKey, result); return result;
        }
        @SuppressWarnings("unchecked") List<List<String>>[] dp = new List[normalized.length() + 1];
        for (int i = 0; i < dp.length; i++) dp[i] = new ArrayList<>();
        dp[0].add(java.util.Collections.emptyList());
        SplitState prior = incremental.get(Integer.toString(limit));
        int common = 0;
        if (prior != null) {
            while (common < Math.min(prior.input.length(), normalized.length())
                    && prior.input.charAt(common) == normalized.charAt(common)) common++;
            for (int i = 0; i <= common; i++) dp[i] = prior.dp[i];
        }
        for (int end = common + 1; end <= normalized.length(); end++) {
            QueryWork.check();
            int startMin = Math.max(0, end - Math.max(1, maxLength));
            for (int start = end - 1; start >= startMin; start--) {
                String part = normalized.substring(start, end);
                if (!syllables.contains(part)) continue;
                for (List<String> prefix : dp[start]) {
                    ArrayList<String> path = new ArrayList<>(prefix);
                    path.add(part);
                    dp[end].add(path);
                }
            }
            dp[end].sort(Comparator.comparingInt(List::size));
            if (dp[end].size() > limit * 4) dp[end].subList(limit * 4, dp[end].size()).clear();
        }
        List<List<String>> result = new ArrayList<>(dp[normalized.length()].subList(0, Math.min(limit, dp[normalized.length()].size())));
        cache.put(cacheKey, result);
        QueryWork.check(); incremental.put(Integer.toString(limit), new SplitState(normalized, dp));
        return result;
    }

    private static final class MapCache {
        private final LinkedHashMap<String,List<List<String>>> map;
        MapCache(final int max) { map = new LinkedHashMap<String,List<List<String>>>(max, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String,List<List<String>>> e) { return size() > max; }
        }; }
        synchronized List<List<String>> get(String k) { return map.get(k); }
        synchronized void put(String k, List<List<String>> v) { map.put(k, v); }
    }
}
