package com.example.offlineime;

/** Offline emoji text and kaomoji. */
final class EmojiCatalog {
    static final String[] CATEGORIES = {"最近", "收藏", "颜文字", "笑脸", "人物", "动物", "食物", "旅行", "活动", "物品", "符号"};
    static String[] values(String category) {
        switch (category) {
            case "颜文字": return new String[]{
                "(^_^)", "(≧▽≦)", "(＾▽＾)", "(*^▽^*)", "(｡•̀ᴗ-)✧", "(๑•̀ㅂ•́)و✧",
                "٩(๑❛ᴗ❛๑)۶", "ヽ(✿ﾟ▽ﾟ)ノ", "(●°u°●)​", "(｡･ω･｡)", "(◕‿◕)", "(•̀ᴗ•́)و",
                "(〃▽〃)", "(⁄ ⁄•⁄ω⁄•⁄ ⁄)", "(๑´ㅂ`๑)", "(｡♥‿♥｡)", "(づ｡◕‿‿◕｡)づ", "(つ≧▽≦)つ",
                "♡(ӦｖӦ｡)", "( ˘ ³˘)♥", "(｡•́‿•̀｡)", "(っ˘ω˘ς)", "(T_T)", "(╥﹏╥)",
                "(；ω；)", "(ಥ_ಥ)", "(｡•́︿•̀｡)", "(つω`｡)", "(ノ_<。)", "( ´•̥̥̥ω•̥̥̥` )",
                "(￣へ￣)", "(＃`Д´)", "(╬ Ò﹏Ó)", "(눈_눈)", "(¬_¬)", "(￣︿￣)",
                "(╯°□°）╯︵ ┻━┻", "┬─┬ノ( º _ ºノ)", "Σ(°△°|||)", "(⊙_⊙)", "(°ロ°) !", "(・・;)",
                "(・_・ヾ", "(・・?)", "¯\\_(ツ)_/¯", "┐(￣ヘ￣)┌", "(￣▽￣;)", "(￢_￢)",
                "(－_－) zzZ", "(∪｡∪)｡｡｡zzZ", "(￣o￣) . z Z", "(－ω－) zzZ", "ヾ(＾∇＾)", "(・ω・)ノ",
                "( ´ ▽ ` )ﾉ", "ヾ(￣▽￣)Bye~", "m(_ _)m", "(人´∀`)", "(o_ _)ﾉ彡☆", "(ง •̀_•́)ง",
                "ᕦ(ò_óˇ)ᕤ", "( •̀ ω •́ )✧", "(=^･ω･^=)", "ฅ^•ﻌ•^ฅ", "ʕ•ᴥ•ʔ", "／(･ × ･)＼",
                "U・ᴥ・U", "(•ө•)♡", "♪(^∇^*)", "ヽ(；▽；)ノ", "(๑• . •๑)", "(๑˃̵ᴗ˂̵)و"};
            case "笑脸": return split("😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾");
            case "人物": return split("👋 🤚 🖐️ ✋ 🖖 👌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦵 🦶 👂 👃 🧠 🦷 🦴 👀 👁️ 👅 👄 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 👷 💂 🕵️ 👩‍⚕️ 👨‍🍳 👩‍🎓 👨‍💻 👩‍🚀 👨‍🚒 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🏃 💃 🕺 🧘 🛀 🛌 👭 👫 👬 💏 💑 👪");
            case "动物": return split("🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🦟 🦗 🕷️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🦧 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾 🐉 🐲 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍃 🍂 🍁 🍄 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻");
            case "食物": return split("🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 🥪 🥙 🧆 🌮 🌯 🥗 🥘 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽️ 🥢");
            case "旅行": return split("🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛵 🏍️ 🚲 🛴 🚏 🛣️ 🛤️ 🚨 🚥 🚦 🛑 🚧 ⛽ 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🚁 🚀 🛸 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏢 🏬 🏥 🏦 🏨 🏪 🏫 🏭 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🌃 🌌 🌉 🌁 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 🌪️ 🌫️ 🌈 ☔ ⚡ 🔥 💧 🌊 🌍 🌎 🌏 🌙 ⭐ 🌟 ✨");
            case "活动": return split("⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🪂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🎊 🎉 🎈 🎁 🎀 🎄 🎋 🎍 🎎 🎏 🎐 🎑 🧧");
            case "物品": return split("⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🕹️ 💽 💾 💿 📀 📷 📸 📹 🎥 📽️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🎚️ 🎛️ 🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 🧯 🛢️ 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧱 ⛓️ 🧲 🔫 💣 🧨 🔪 🗡️ 🛡️ 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 🕳️ 💊 💉 🩹 🩺 🚪 🛏️ 🛋️ 🪑 🚽 🚿 🛁 🧴 🧷 🧹 🧺 🧻 🧼 🧽 🧾 📦 ✉️ 📧 📨 📩 📪 📫 📬 📭 📮 📝 📚 📖 🔖 🏷️ ✏️ 🖊️ 🖋️ 🖌️ 🖍️ 📁 📂 🗂️ 📅 📆 📌 📍 📎 🖇️ 📏 📐 ✂️ 🔒 🔓 🔑 🗝️");
            case "符号": return split("❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ ♦️ ♣️ ♠️ 💯 💢 💥 💫 💦 💨 💬 💭 💤 ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ❓ ❔ ❕ ❗ ‼️ ⁉️ ⚠️ 🚫 ⛔ 🔞 📵 🚭 ♻️ ⚜️ 🔱 🔰 ⭕ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ⭐ ★ ☆ ✨ → ← ↑ ↓ ↔️ ↕️ ↗️ ↘️ ↙️ ↖️ ↩️ ↪️ ⤴️ ⤵️ ▶️ ⏸️ ⏹️ ⏺️ ⏭️ ⏮️ 🔀 🔁 🔂 🔊 🔉 🔈 🔇 🔔 🔕 🎵 🎶 ©️ ®️ ™️ ℃ ￥ € £ ¥ °");
            default: return split("😀 😂 🥰 😍 😊 😎 🤔 🥺 😭 👍 🙏 👏 🎉 ❤️ ✨ 🔥 💪 🤝 🤗 😘 😅 🤣 😋 😴 🌹 🎂 ☕ 🍻 ✅ 💯");
        }
    }
    private static String[] split(String text) { return text.split(" "); }
    static String categoryLabel(String category) {
        switch (category) {
            case "笑脸": return "😀";
            case "人物": return "👤";
            case "动物": return "🐶";
            case "食物": return "🍔";
            case "旅行": return "🚗";
            case "活动": return "⚽";
            case "物品": return "💡";
            case "符号": return "♥";
            default: return category;
        }
    }
    static String[] recent(android.content.SharedPreferences preferences) {
        String json = preferences.getString("recent_v2", "");
        if (!json.isEmpty()) {
            try {
                org.json.JSONArray items = new org.json.JSONArray(json);
                String[] values = new String[items.length()];
                for (int i = 0; i < values.length; i++) values[i] = items.getString(i);
                return values;
            } catch (org.json.JSONException ignored) { }
        }
        String legacy = preferences.getString("recent", "");
        return legacy.isEmpty() ? new String[0] : legacy.split("\\|");
    }
    static boolean isKaomoji(String value) {
        for (String item : values("颜文字")) if (item.equals(value)) return true;
        return value.length() > 8 && value.indexOf('(') >= 0;
    }
}
