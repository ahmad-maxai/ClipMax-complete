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

    private val CATEGORY_ALIASES: Map<CategoryId, List<String>> = mapOf(
        CategoryId.CLASSIC to listOf("classic", "smile", "wink", "face", "كلاسيكي"),
        CategoryId.HAPPY to listOf("happy", "joy", "glad", "cheerful", "سعيد", "فرح"),
        CategoryId.EXCITED to listOf("excited", "yay", "hype", "thrilled", "متحمس"),
        CategoryId.SAD to listOf("sad", "unhappy", "down", "blue", "حزين"),
        CategoryId.CRYING to listOf("cry", "crying", "tears", "sob", "بكاء", "دموع"),
        CategoryId.ANGRY to listOf("angry", "mad", "furious", "annoyed", "غاضب"),
        CategoryId.SURPRISED to listOf("surprised", "shock", "wow", "omg", "متفاجئ"),
        CategoryId.CONFUSED to listOf("confused", "huh", "sleep", "tired", "مرتبك"),
        CategoryId.EMBARRASSED to listOf("embarrassed", "shy", "blush", "sweat", "محرج", "خجل"),
        CategoryId.AFFECTIONATE to listOf("love", "heart", "hug", "kiss", "affection", "حب", "ودي"),
        CategoryId.PLAYFUL to listOf("playful", "cute", "wink", "smirk", "مرح"),
        CategoryId.SHRUGGING to listOf("shrug", "shrugging", "dunno", "idk", "لا أعلم"),
        CategoryId.CELEBRATORY to listOf("celebrate", "party", "cheer", "yay", "hug", "احتفال", "تهنئة")
    )

    fun search(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (cat in CATEGORIES) {
            val catMatch = cat.labelEn.lowercase().contains(q) ||
                cat.labelAr.contains(q) ||
                (CATEGORY_ALIASES[cat.id]?.any { it.contains(q) || q.contains(it) } == true)
            for (item in cat.items) {
                if (catMatch || item.lowercase().contains(q)) {
                    if (seen.add(item)) {
                        results.add(item)
                    }
                }
            }
        }
        return results.take(80)
    }
}

