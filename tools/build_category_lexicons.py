"""Build categorized offline vocabulary from pinned local seeds and jieba data.

Normal APK builds use the generated text and need no extra Python packages.
Regeneration requires the versions in data_sources/category-requirements.txt.
Use --output-root to prepare files without changing the Android lexicon.
"""
import argparse
from collections import Counter
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import re
import runpy

from opencc import OpenCC
from pypinyin import lazy_pinyin, Style


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_existing(lexicon):
    readings = {}
    words = {}
    for path in sorted(lexicon.rglob('*.txt')):
        if path.name == 'single_char_common_8105.txt' or path.name.startswith('category_') or path.name == 'life_category_hot.txt':
            continue
        if path.parent.name not in ('core', 'zh', 'zh-ext'):
            continue
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            if not line.strip() or line.startswith('#'):
                continue
            columns = line.split('\t')
            word, raw = columns[:2]
            raw = ' '.join(re.split(r"[\s']+", raw.lower().replace('ü', 'v').replace('u:', 'v')))
            score = int(columns[3] if path.name.startswith('life_short_hot_') else columns[2])
            key = (word, raw)
            readings[key] = max(readings.get(key, 0), score)
            if word not in words or score > words[word][1]:
                words[word] = (raw, score)
    return readings, words


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project-root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--baseline-lexicon', type=Path, help='Use an existing app lexicon while staging')
    parser.add_argument('--output-root', type=Path)
    args = parser.parse_args()
    root = args.project_root.resolve()
    data = root / 'data_sources'
    output = (args.output_root or root / 'lexicon').resolve()
    baseline = args.baseline_lexicon or root / 'lexicon'
    for package, version in [('jieba', '0.42.1'), ('pypinyin', '0.55.0'), ('opencc-python-reimplemented', '0.1.7')]:
        if importlib.metadata.version(package) != version:
            raise ValueError(f'Regeneration requires {package}=={version}')
    config = runpy.run_path(str(data / 'category_seeds.py'))
    categories = config['CATEGORIES']
    old, preferred = load_existing(baseline)
    syllables = set((data / 'category-syllables.txt').read_text(encoding='utf-8').split())
    corrections = []
    for line in (data / 'category-polyphones.tsv').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        columns = line.split('\t')
        word, raw = columns[:2]
        corrections.append((word, raw.split(), len(columns) > 2 and columns[2] == 'exact'))
    corrections.sort(key=lambda c: -len(c[0]))
    converter = OpenCC('t2s')
    buckets = {key: {} for key in categories}
    extra_titles = {
        'idioms': '成语惯用语', 'common_three': '三字通用补充',
        'common_four': '四字常用补充', 'common_long': '通用长词补充',
        'proper_people': '语料人名补充', 'proper_places': '语料地名补充',
        'proper_organizations': '语料机构名称补充',
    }
    buckets.update({key: {} for key in extra_titles})
    hot = {}
    omitted = Counter()
    new_readings = {}
    provenance = Counter()
    reading_cache = {}

    def reading(word):
        if word in reading_cache:
            return reading_cache[word]
        if word in preferred:
            raw = preferred[word][0]
            tokens = raw.split()
        else:
            tokens = lazy_pinyin(word, style=Style.NORMAL, errors='default')
            occupied = set()
            for term, values, exact in corrections:
                if exact and term != word:
                    continue
                start = 0
                while (position := word.find(term, start)) >= 0:
                    indices = set(range(position, position + len(term)))
                    if not indices & occupied:
                        tokens[position:position + len(term)] = values
                        occupied |= indices
                    start = position + len(term)
            tokens = [t.lower().replace('ü', 'v').replace('u:', 'v').replace('lue', 'lve').replace('nue', 'nve') for t in tokens]
        raw = ' '.join(tokens)
        if len(tokens) != len(word) or any(t not in syllables for t in tokens):
            omitted['unusable_reading'] += 1
            raw = None
        reading_cache[word] = raw
        return raw

    def add(category, word, score, source, warm=False):
        if not 2 <= len(word) <= 24 or not re.fullmatch(r'[\u4e00-\u9fff]+', word):
            omitted['format'] += 1
            return
        if converter.convert(word) != word:
            omitted['traditional'] += 1
            return
        raw = reading(word)
        if raw is None:
            return
        key = (word, raw)
        # Existing word frequency and alternative readings stay intact.
        score = old.get(key, score)
        buckets[category][key] = max(buckets[category].get(key, 0), score)
        if key not in old:
            new_readings[key] = max(new_readings.get(key, 0), score)
        if warm and 3 <= len(word) <= 8:
            hot[key] = max(hot.get(key, 0), score)
        provenance[source] += 1

    for category, (title, terms) in categories.items():
        for word in dict.fromkeys(terms.split()):
            add(category, word, 6500 if category.startswith('chat_') else 6100, 'project_seeds', warm=True)
    for category, verbs, objects in config['REQUEST_GROUPS']:
        for verb in verbs.split('|'):
            for obj in objects.split('|'):
                for prefix in config['REQUEST_PREFIXES']:
                    for suffix in config['REQUEST_SUFFIXES']:
                        # Avoid the unnatural "provide ... a little" variants.
                        if suffix and verb in ('提供', '补充', '提交'):
                            continue
                        add(category, prefix + verb + obj + suffix, 4900, 'compatible_requests')
    for category, subjects, statuses in config['STATUS_GROUPS']:
        for subject in subjects.split('|'):
            for status in statuses.split('|'):
                add(category, subject + status, 5200, 'compatible_statuses', warm=True)

    dictionary = data / 'jieba-0.42.1-dict.txt'
    for line in dictionary.read_text(encoding='utf-8').splitlines():
        word, frequency, tag = line.split()
        frequency = int(frequency)
        if not 3 <= len(word) <= 12 or not re.fullmatch(r'[\u4e00-\u9fff]+', word):
            continue
        if tag in ('m', 'mq', 'q', 'eng', 'x'):
            continue
        if tag.startswith('nr'):
            if frequency < 50:
                continue
            category = 'proper_people'
        elif tag == 'ns':
            if frequency < 10:
                continue
            category = 'proper_places'
        elif tag == 'nt':
            if frequency < 10:
                continue
            category = 'proper_organizations'
        elif tag == 'i':
            category = 'idioms'
        else:
            if frequency < (10 if len(word) == 3 else 5):
                continue
            category = 'common_three' if len(word) == 3 else 'common_four' if len(word) == 4 else 'common_long'
        # Existing corpus terms are not duplicated into the large supplement.
        if word in preferred:
            continue
        score = min(8000, max(1800, round(1800 + 1200 * math.log10(frequency + 1))))
        if category.startswith('proper_'):
            score = min(score, 5500)
        add(category, word, score, 'jieba_dictionary')

    output.mkdir(parents=True, exist_ok=True)
    file_reports = []
    for category, rows in buckets.items():
        title = categories[category][0] if category in categories else extra_titles[category]
        relative = f'zh-ext/category_{category}.txt'
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        comments = [f'# 开心离线输入法分类补充：{title}',
                    '# 项目词表与匹配模板：项目整理，生成式 AI 辅助；不使用私人消息或用户输入。',
                    '# 语料补充：jieba 0.42.1 本地 dict.txt；MIT 许可全文见 category-LICENSE.jieba.md。',
                    '# 拼音：pypinyin 0.55.0，优先沿用已有词语读音及项目多音词校正。',
                    '# 格式：词语<TAB>实际无声调拼音<TAB>基础排序分数；不扩散错拼别名。']
        ordered = sorted(rows.items(), key=lambda pair: (-pair[1], pair[0]))
        path.write_text('\n'.join(comments + [f'{word}\t{raw}\t{score}' for (word, raw), score in ordered]) + '\n', encoding='utf-8')
        file_reports.append({'category': category, 'title': title, 'file': relative, 'rows': len(rows),
                             'new_readings': sum(key not in old for key in rows), 'sha256': digest(path)})
    # Bounded hot cache complements the SQLite corpus; never load all categories into RAM.
    hot_path = output / 'zh/life_category_hot.txt'
    hot_path.parent.mkdir(parents=True, exist_ok=True)
    hot_rows = sorted(hot.items(), key=lambda pair: (-pair[1], pair[0]))
    hot_path.write_text('# 分类精选短语；内存加载有固定数量上限，完整分类由 SQLite 检索。\n'
                        + '\n'.join(f'{word}\t{raw}\t{score}' for (word, raw), score in hot_rows) + '\n', encoding='utf-8')
    report = {'category_count': len(file_reports), 'category_rows': sum(len(b) for b in buckets.values()),
              'unique_category_readings': len(set().union(*(set(b) for b in buckets.values()))),
              'new_word_readings': len(new_readings),
              'new_words': len({word for word, raw in new_readings} - set(preferred)),
              'new_by_length': dict(sorted(Counter(len(word) for word, raw in new_readings).items())),
              'provenance_counts': dict(provenance), 'omitted': dict(omitted),
              'source_files': [{'file': p.name, 'sha256': digest(p)} for p in [dictionary, data / 'category_seeds.py', data / 'category-polyphones.tsv', data / 'category-syllables.txt']],
              'files': file_reports, 'hot_source_rows': len(hot_rows), 'hot_memory_limit': 1200}
    (output / 'category-expansion.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    notice = (f'分类词库扩充\n\n共 {len(file_reports)} 个分类，新增 {len(new_readings):,} 条不同词语/读音。\n'
              '日常场景词与短语由项目整理（生成式 AI 辅助），请求短语只组合匹配的动词与对象。\n'
              '成语、三四字词和部分专名补充来自 jieba 0.42.1 本地词典，按词频和词性筛选。\n'
              '已有词条、多音读音和基础词频保留；低频语料采用较低基础分，不强行占据首选。\n'
              '所有内容随 APK 离线打包。分类明细及校验值见 category-expansion.json。\n\n'
              + '\n'.join(f"{f['title']}：{f['rows']:,} 条" for f in file_reports) + '\n')
    (output / 'category-expansion-notice.md').write_text(notice, encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('files', 'source_files')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
