package com.clipmax.app

/**
 * Curated dataset of classic emoticons and kaomojis grouped into 13 distinct categories
 * matching reference "185802.png" (Classic, Happy, Excited, Sad, Crying, Angry,
 * Surprised, Confused, Embarrassed, Affectionate, Playful, Shrugging, Celebratory).
 * Pure JVM object with zero Android dependencies.
 */
internal object KaomojiCatalog {

    enum class CategoryId {
        CLASSIC,
        HAPPY,
        EXCITED,
        SAD,
        CRYING,
        ANGRY,
        SURPRISED,
        CONFUSED,
        EMBARRASSED,
        AFFECTIONATE,
        PLAYFUL,
        SHRUGGING,
        CELEBRATORY
    }

    data class Category(
        val id: CategoryId,
        val labelEn: String,
        val labelAr: String,
        val items: List<String>
    )

    val CATEGORIES: List<Category> = listOf(
        Category(
            CategoryId.CLASSIC,
            "Classic",
            "كلاسيكي",
            listOf(":-)", ":-^", "^_^", "(^^)", ";-)", "8-)", "B-)", "o:-)", ":-D", "};-)", ";)", ":-*", ":-P", ":-!", ":-$", ":-X", ":-|", ":-\\", ":-[")
        ),
        Category(
            CategoryId.HAPPY,
            "Happy",
            "سعيد",
            listOf("(^‿^)", "(^_^)", "(^.^)", "(´◡`)", "(◕‿◕)", "(◍•ᴗ•◍)", "(☉｡☉)!", "(｡◕‿◕｡)", "(ᵔᴥᵔ)", "(^,^)")
        ),
        Category(
            CategoryId.EXCITED,
            "Excited",
            "متحمس",
            listOf("\\(^o^)/", "٩(◕‿◕)۶", "o(≧▽≦)o", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "\\(★ω★)/", "٩( ᐛ )و", "ヽ(♡▂♡)ﾉ", "o(>< )o")
        ),
        Category(
            CategoryId.SAD,
            "Sad",
            "حزين",
            listOf("(._.)", "(T_T)", "(;_;)", "(>_<)", "(u_u)", "(ಥ﹏ಥ)", "(-_-)", "(｡•́︿•̀｡)", "(´._.`)")
        ),
        Category(
            CategoryId.CRYING,
            "Crying",
            "بكاء",
            listOf("(╥﹏╥)", "(😭)", "(╥╯﹏╰╥)", "(QAQ)", "(TOT)", "(;﹏;)", "(πーπ)", "(;ω;)")
        ),
        Category(
            CategoryId.ANGRY,
            "Angry",
            "غاضب",
            listOf("(>__<)", "(╬ﾟ◥⊖◤ﾟ)╬", "(｀⌒´メ)", "(¬_¬)", "(#-_)", "(ಠ_ಠ)", "(╬ Ò﹏Ó)")
        ),
        Category(
            CategoryId.SURPRISED,
            "Surprised",
            "متفاجئ",
            listOf("(⊙_⊙)", "(o_O)", "(°ロ°)", "∑(O_O;)", "Σ(°ロ°)", "(⊙o⊙)", "w(°ｏ°)w", "Σ(ﾟДﾟ)")
        ),
        Category(
            CategoryId.CONFUSED,
            "Confused",
            "مرتبك",
            listOf("(¬_¬ )", "(¯―¯٥)", "(O.o)?", "(~_~;)", "(⊙_☉)", "(-.-)Zzz", "(•_•)?", "(・・ ) ?")
        ),
        Category(
            CategoryId.EMBARRASSED,
            "Embarrassed",
            "محرِج",
            listOf("(^_^;)", "(#^^#)", "(._.`;)", "(;^_^)", "(oops)", "(〃ω〃)", "(*/ω＼*)")
        ),
        Category(
            CategoryId.AFFECTIONATE,
            "Affectionate",
            "ودي",
            listOf("(♥ω♥*)", "(｡♥‿♥｡)", "(´｡• ᵕ •｡`)", "(◍•ω•◍)❤", "(✿˵•̀ᴗ•́˵)♡", "(´ ω `♡)", "(｡- ω -｡)")
        ),
        Category(
            CategoryId.PLAYFUL,
            "Playful",
            "مرح",
            listOf("(¬‿¬)", "(^_<)", "(o^^o)", "(๑>◡<๑)", "(>w<)", "(•‿•)", "( ˙꒳​˙ )")
        ),
        Category(
            CategoryId.SHRUGGING,
            "Shrugging",
            "لا أعلم",
            listOf("¯\\_(ツ)_/¯", "¯\\(°_o)/¯", "乁( ⁰͡ Ĺ̯ ⁰͡ )ㄏ", "¯\\(ヅ)_/¯", "╮(-_-)╭", "¯\\(◔◡◔)/¯")
        ),
        Category(
            CategoryId.CELEBRATORY,
            "Celebratory",
            "احتفالي",
            listOf("o(>ω<)o", "ヽ(o⌣ˬ⌣o)ﾉ", "d(^_^)b", "\\(^_^)/", "(づ￣ ³￣)づ", "(★^O^★)", "\\(o˘◡˘o)/")
        )
    )

    fun search(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (cat in CATEGORIES) {
            for (item in cat.items) {
                if (item.lowercase().contains(q) || cat.labelEn.lowercase().contains(q) || cat.labelAr.contains(q)) {
                    if (item !in seen) {
                        seen.add(item)
                        results.add(item)
                    }
                }
            }
        }
        if (results.isEmpty()) {
            for (cat in CATEGORIES) {
                for (item in cat.items) {
                    if (item !in seen) {
                        seen.add(item)
                        results.add(item)
                    }
                }
            }
        }
        return results.take(80)
    }
}
