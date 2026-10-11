package com.example.offlineime;

/** Offline, categorized symbols for the dedicated symbol picker. */
final class SymbolCatalog {
    static final String[] CATEGORIES = {
            "常用", "最近", "中文", "英文", "括号", "数学", "序号",
            "箭头", "货币", "单位", "特殊", "希腊", "音乐"
    };

    private SymbolCatalog() { }

    static String[] values(String category) {
        String symbols;
        switch (category) {
            case "中文":
                symbols = "， 。 、 ； ： ？ ！ ‘ ’ “ ” （ ） 【 】 《 》 〈 〉 「 」 『 』 〔 〕 … …… — —— · ～ ﹏ ． 〃 々 〆 〇 〝 〞 ﹅ 〖 〗 ﹃ ﹄ ﹁ ﹂";
                break;
            case "英文":
                symbols = ", . ; : ? ! ' \" ( ) [ ] { } < > / \\ | @ # $ % ^ & * - _ + = ~ ` :‑ ;… ¡ ¿ ‽ ‼ ⁇ ⁉ • ‣ ※";
                break;
            case "括号":
                symbols = "（ ） [ ] { } 【 】 〔 〕 〖 〗 〈 〉 《 》 「 」 『 』 ﹙ ﹚ ﹛ ﹜ ﹝ ﹞ ⟨ ⟩ ⌈ ⌉ ⌊ ⌋ ⦃ ⦄ ⦅ ⦆ ⦇ ⦈";
                break;
            case "数学":
                symbols = "+ − × ÷ = ≠ ≈ ≡ < > ≤ ≥ ± ∓ ∞ √ ∛ ∜ ∑ ∏ ∫ ∬ ∂ ∇ ∆ ∈ ∉ ∋ ∅ ∪ ∩ ⊂ ⊃ ⊆ ⊇ ∧ ∨ ¬ ∀ ∃ ∄ ∴ ∵ ∝ ∠ ⊥ ∥ ∽ ≌ ≒ ≜ ≔ ⊕ ⊗ ⊙ ∙ · ‰ ‱ % ℃ ℉ π ½ ⅓ ¼ ¾ ² ³";
                break;
            case "序号":
                symbols = "① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ❶ ❷ ❸ ❹ ❺ ❻ ❼ ❽ ❾ ❿ ⑴ ⑵ ⑶ ⑷ ⑸ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ ⅰ ⅱ ⅲ ⅳ ⅴ ㈠ ㈡ ㈢ ㈣ ㈤";
                break;
            case "箭头":
                symbols = "← ↑ → ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇑ ⇒ ⇓ ⇔ ⇕ ↩ ↪ ↫ ↬ ↰ ↱ ↶ ↷ ↺ ↻ ⇄ ⇆ ⇋ ⇌ ➔ ➜ ➝ ➞ ➟ ➠ ➡ ➢ ➣ ➤ ➥ ➦ ➧ ➨ ➩ ➪ ➫ ➬ ➭ ➮ ➯ ➱ ➲ ➳ ➴ ➵ ➶ ➷ ➸ ➹ ➺ ➻ ➼ ➽ ➾";
                break;
            case "货币":
                symbols = "¥ ￥ $ ＄ € £ ￡ ₩ ₫ ₹ ₽ ₿ ¢ ₵ ₡ ₢ ₣ ₤ ₦ ₲ ₴ ₸ ₺ ₼ ₽ ₾ ₪ ﷼ ؋ ฿ ₭ ₮ ₱ ₨ ₯ ₰ ₳ ₧ ₻";
                break;
            case "单位":
                symbols = "℃ ℉ ° ′ ″ % ‰ ‱ ㎡ ㎢ ㎠ ㎣ ㎤ ㎥ ㎦ ㎍ ㎎ ㎏ ㏄ ㏎ ㎜ ㎝ ㎞ ㍉ ㌢ ㍍ ㌔ ㎅ ㎆ ㎇ ㎐ ㎑ ㎒ ㎓ ㎃ ㎄ ㎈ ㎉ ㎗ ㎘ ㎩ ㎪ ㎫ ㎳ ㎲ ㎰";
                break;
            case "特殊":
                symbols = "★ ☆ ✦ ✧ ✩ ✪ ✫ ✬ ✭ ✮ ✯ ✰ ✱ ✲ ✳ ✴ ✵ ✶ ✷ ✸ ✹ ✺ ✻ ✼ ✽ ✾ ✿ ❀ ❁ ❂ ❃ ❄ ❅ ❆ ❇ ❈ ❉ ❊ ❋ ● ○ ◉ ◎ ◌ ◇ ◆ □ ■ ▣ ▤ ▲ △ ▼ ▽ ▶ ▷ ◀ ◁ ♠ ♣ ♥ ♦ ♡ ♢ ☀ ☁ ☂ ☃ ☄ ☎ ☏ ☑ ☒ ☘ ☕ ☮ ☯ ☺ ☻ ☹ ☠ ⚐ ⚑ ⚠ ⚡ ⚪ ⚫ ✓ ✔ ✕ ✖ ✗ ✘ © ® ™ ℗ § ¶ † ‡ ※ 〒";
                break;
            case "希腊":
                symbols = "Α Β Γ Δ Ε Ζ Η Θ Ι Κ Λ Μ Ν Ξ Ο Π Ρ Σ Τ Υ Φ Χ Ψ Ω α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ τ υ φ χ ψ ω ς ϑ ϕ ϖ ϰ";
                break;
            case "音乐":
                symbols = "♪ ♫ ♬ ♩ ♭ ♮ ♯ 𝄞 𝄢 𝄡 𝄫 𝄪 𝄽 𝄾 𝄿 𝅀 𝅁 𝅂 𝅃 𝅄 𝅅 𝅆 𝅇 𝅈 𝅉 𝅊 𝅋";
                break;
            default:
                symbols = "， 。 ？ ！ 、 ： ； … —— · ～ （ ） 【 】 《 》 “ ” ‘ ’ @ # ￥ % & * + - = / \\ _ ¥ $ € £ ° ℃ ① ② ③ ④ ⑤ → ← ↑ ↓ ✓ ✔ × √ ★ ☆ ♥ ♡ ♪ ♫ ※ © ® ™";
                break;
        }
        return symbols.split(" ");
    }
}
