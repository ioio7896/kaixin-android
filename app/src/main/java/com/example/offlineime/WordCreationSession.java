package com.example.offlineime;

import java.util.ArrayList;
import java.util.List;

/** Temporary word assembly; selected segments never reach the host until confirmation. */
final class WordCreationSession {
    static final class Part {
        final String word, reading, raw;
        Part(String word, String reading, String raw) { this.word = word; this.reading = reading; this.raw = raw; }
    }
    final List<Part> parts = new ArrayList<>();
    final List<PinyinPathEditor.Segment> remainingLocks = new ArrayList<>();
    boolean active, saving;
    int editing = -1;
    String remaining = "";
    String strokePrefix = "";
    long revision;
    void start() { clear(); active = true; }
    void clear() { active = false; saving = false; parts.clear(); remainingLocks.clear(); editing = -1; remaining = ""; strokePrefix = ""; revision++; }
    boolean add(String word, String pronunciation, String raw) {
        try {
            String reading = CustomWordStore.reading(pronunciation, word);
            Part part = new Part(word, reading, raw);
            if (editing >= 0) { parts.set(editing, part); editing = -1; }
            else parts.add(part);
            strokePrefix = ""; revision++; return true;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    String word() { StringBuilder value = new StringBuilder(); for (Part part : parts) value.append(part.word); return value.toString(); }
    String reading() { List<String> value = new ArrayList<>(); for (Part part : parts) value.add(part.reading); return String.join("'", value); }
    String label(String input) {
        if (saving) return "造词 · 正在保存…";
        if (editing >= 0) return "造词 · 正在修改「" + parts.get(editing).word + "」 · 点击查看";
        if (parts.isEmpty()) return "造词 · 输入拼音后逐字选择 · 点击查看";
        return "造词 · " + word() + (input.isEmpty() ? " · 完成 ›" : " · 继续选字 ›");
    }
}
