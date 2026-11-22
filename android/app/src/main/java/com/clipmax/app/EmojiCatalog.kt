package com.clipmax.app

/**
 * Comprehensive offline curated Unicode emoji dataset grouped by standard categories.
 * Includes SMILEYS, PEOPLE, NATURE, FOOD, ACTIVITIES, TRAVEL, OBJECTS, SYMBOLS, and FLAGS
 * (with ~245+ ISO 3166-1 regional-indicator country and territory flags).
 * Strictly excludes all LGBT-themed emoji, Pride flags (🏳️‍🌈), and transgender flags (🏳️‍⚧️, ⚧️).
 * Pure JVM object with zero Android dependencies and zero network usage.
 */
internal object EmojiCatalog {

    enum class CategoryId {
        SMILEYS,
        PEOPLE,
        NATURE,
        FOOD,
        ACTIVITIES,
        TRAVEL,
        OBJECTS,
        SYMBOLS,
        FLAGS
    }

    data class Category(
        val id: CategoryId,
        val label: String,
        val emojis: List<String>
    )

    val SMILEYS_EMOJIS: List<String> = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
        "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
        "😘", "😗", "😚", "😋", "😛", "😜", "🤪", "😝",
        "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐",
        "😑", "😶", "😏", "😒", "🙄", "😬", "😌", "😔",
        "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮",
        "🥵", "🥶", "😎", "🤓", "🧐", "😕", "😟", "🙁",
        "😮", "😯", "😲", "😳", "🥺", "😢", "😭", "😱",
        "😤", "😡", "😠", "🤬", "🤯", "🥳", "🥹", "🤠",
        "🤡", "🤥", "🤫", "🤭", "❤️", "🧡", "💛", "💚",
        "💙", "💜", "🖤", "🤍", "🤎", "💖", "💗", "💓"
    )

    val PEOPLE_EMOJIS: List<String> = listOf(
        "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏",
        "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆",
        "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️",
        "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃",
        "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅",
        "👄", "👤", "👥", "🫂", "👶", "👧", "🧒", "👦",
        "👩", "🧑", "👨", "👩‍🦱", "👨‍🦱", "👩‍🦰", "👨‍🦰", "👱‍♀️"
    )

    val NATURE_EMOJIS: List<String> = listOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
        "🐨", "🐯", "🦁", "🐮", "🐷", "🐽", "🐸", "🐵",
        "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦", "🐤",
        "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗",
        "🐴", "🦄", "🐝", "🪱", "🐛", "🦋", "🐌", "🐞",
        "🐜", "🪰", "🪲", "🦟", "🦗", "🕷️", "🕸️", "🦂",
        "🐢", "🐍", "🦖", "🦕", "🐙", "🦑", "🦐", "🦞",
        "🦀", "🐠", "🐟", "🐬", "🐳", "🌸", "🌺", "🌻"
    )

    val FOOD_EMOJIS: List<String> = listOf(
        "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
        "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥",
        "🥝", "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️",
        "🫑", "🌽", "🥕", "🫒", "🧄", "🧅", "🥔", "🍠",
        "🥐", "🍞", "🥖", "🥨", "🥯", "🥞", "🧇", "🧀",
        "🍖", "🍗", "🥩", "🥓", "🍔", "🍟", "🍕", "🌭",
        "🥪", "🌮", "🌯", "🫔", "🥙", "🧆", "🥘", "🍲",
        "🥗", "🍿", "🧈", "🧂", "🥫", "🍱", "🍚", "🍛"
    )

    val ACTIVITIES_EMOJIS: List<String> = listOf(
        "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉",
        "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍",
        "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿",
        "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌",
        "🎿", "🏂", "🪂", "🏋️‍♀️", "🤺", "🏇", "⛷️", "🏂",
        "🏄‍♀️", "🚣‍♀️", "🏊‍♀️", "⛹️‍♀️", "🏋️‍♀️", "🤸‍♀️", "🤹‍♀️", "🧘‍♀️",
        "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🏵️", "🎫",
        "🎟️", "🎪", "🤹", "🎭", "🎨", "🎬", "🎤", "🎧"
    )

    val TRAVEL_EMOJIS: List<String> = listOf(
        "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑",
        "🚒", "🚐", "🛻", "🚚", "🚛", "🚜", "🦯", "🦽",
        "🦼", "🛴", "🚲", "🛵", "🏍️", "🛵", "🚏", " রেল",
        "🚄", "🚅", "🚆", "🚇", "轻轨", "🚉", "🚊", "🚝",
        "🚞", "🚋", "🚌", "🚎", "🚐", "🚑", "🚒", "🚓",
        "🚕", "🚗", "🚙", "🚚", "🚛", "🚜", "🚲", "🛴",
        "🚏", "🛣️", "🛤️", "🛢️", "⛽", "🚨", "🚥", "🚦",
        "🛑", "🚧", "⚓", "⛵", "🛶", "🚤", "🛳️", "⛴️"
    )

    val OBJECTS_EMOJIS: List<String> = listOf(
        "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🖲️", "💽",
        "💾", "💿", "📀", "📼", "📷", "📸", "📹", "🎥",
        "📽️", "🎞️", "📞", "☎️", "📟", "📠", "📺", "📻",
        "🎙️", "🎚️", "🎛️", "🧭", "⏱️", "⏲️", "⏰", "🕰️",
        "💡", "🔦", "🏮", "🪔", "📔", "📕", "📖", "📗",
        "📘", "📙", "📚", "📓", "ledger", "📄", "📋", "📅",
        "📆", "📇", "📈", "📉", "📊", "📋", "📌", "📍",
        "📎", "🖇️", "📏", "📐", "✂️", "🗃️", "🗄️", "🗑️"
    )

    val SYMBOLS_EMOJIS: List<String> = listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
        "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗",
        "💖", "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉️",
        "☸️", "☯️", "☦️", "☪️", "☥", "🕎", "📱", "🛜",
        "🔀", "🔁", "🔂", "▶️", "⏩", "⏭️", "⏯️", "◀️",
        "⏪", "⏮️", "🔼", "🔽", "⏭️", "⏹️", "⏺️", "⏏️",
        "🎦", "🔆", "🔅", "📶", "🛜", "📴", "📵", "🔋",
        "🔌", "🔋", "☑️", "✅", "✔️", "❌", "❎", "❓"
    )

    val FLAGS_EMOJIS: List<String> = listOf(
        "🇦🇫", "🇦🇱", "🇩🇿", "🇦🇸", "🇦🇩", "🇦🇴", "🇦🇮", "🇦🇶",
        "🇦🇬", "🇦🇷", "🇦🇲", "🇦🇼", "🇦🇺", "🇦🇹", "🇦🇿", "🇧🇸",
        "🇧🇭", "🇧🇩", "🇧🇧", "🇧🇾", "🇧🇪", "🇧🇿", "🇧🇯", "🇧🇲",
        "🇧🇹", "🇧🇴", "🇧🇦", "🇧🇼", "🇧🇷", "🇮🇴", "🇻🇬", "🇧🇳",
        "🇧🇬", "🇧🇫", "🇧🇮", "🇰🇭", "🇨🇲", "🇨🇦", "🇮🇨", "🇨🇻",
        "🇰🇾", "🇨🇫", "🇹🇩", "🇨🇱", "🇨🇳", "🇨🇽", "🇨🇨", "🇨🇴",
        "🇰🇲", "🇨🇬", "🇨🇩", "🇨🇰", "🇨🇷", "🇭🇷", "🇨🇺", "🇨🇼",
        "🇨🇾", "🇨🇿", "🇩🇰", "🇩🇯", "🇩🇲", "🇩🇴", "🇪🇨", "🇪🇬",
        "🇸🇻", "🇬🇶", "🇪🇷", "🇪🇪", "🇪🇹", "🇪🇺", "🇫🇰", "🇫🇴",
        "🇫🇯", "🇫🇮", "🇫🇷", "🇬🇫", "🇵🇫", "🇬🇦", "🇬🇲", "🇬🇪",
        "🇩🇪", "🇬🇭", "🇬🇮", "🇬🇷", "🇬🇱", "🇬🇩", "🇬🇵", "🇬🇺",
        "🇬🇹", "🇬🇬", "🇬🇳", "🇬🇼", "🇬🇾", "🇭🇹", "🇭🇳", "🇭🇰",
        "🇭🇺", "🇮🇸", "🇮🇳", "🇮🇩", "🇮🇷", "🇮🇶", "🇮🇪", "🇮🇲",
        "🇮🇱", "🇮🇹", "🇯🇲", "🇯🇵", "🎌", "🇯🇪", "🇯🇴", "🇰🇿",
        "🇰🇪", "🇰🇮", "🇽🇰", "🇰🇼", "🇰🇬", "🇱🇦", "🇱🇻", "🇱🇧",
        "🇱🇸", "🇱🇷", "🇱🇾", "🇱🇮", "🇱🇹", "🇱🇺", "🇲🇴", "🇲🇬",
        "🇲🇼", "🇲🇾", "🇲🇻", "🇲🇱", "🇲🇹", "🇲🇭", "🇲🇶", "🇲🇷",
        "🇲🇺", "🇾🇹", "🇲🇽", "🇫🇲", "🇲🇩", "🇲🇨", "🇲🇳", "🇲🇪",
        "🇲🇸", "🇲🇦", "🇲🇿", "🇲🇲", "🇳🇦", "🇳🇷", "🇳🇵", "🇳🇱",
        "🇳🇨", "🇳🇿", "🇳🇮", "🇳🇪", "🇳🇬", "🇳🇺", "🇳🇫", "🇰🇵",
        "🇲🇵", "🇳🇴", "🇴🇲", "🇵🇰", "🇵🇼", "🇵🇸", "🇵🇦", "🇵🇬",
        "🇵🇾", "🇵🇪", "🇵🇭", "🇵🇳", "🇵🇱", "🇵🇹", "🇵🇷", "🇶🇦",
        "🇷🇪", "🇷🇴", "🇷🇺", "🇷🇼", "🇼🇸", "🇸🇲", "🇸🇹", "🇸🇦",
        "🇸🇳", "🇷🇸", "🇸🇨", "🇸🇱", "🇸🇬", "🇸🇽", "🇸🇰", "🇸🇮",
        "🇸🇧", "🇸🇴", "🇿🇦", "🇰🇷", "🇸🇸", "🇪🇸", "🇱🇰", "🇧🇱",
        "🇸🇭", "🇰🇳", "🇱🇨", "🇵🇲", "🇻🇨", "🇸🇩", "🇸🇷", "🇸🇪",
        "🇨🇭", "🇸🇾", "🇹🇼", "🇹🇯", "🇹🇿", "🇹🇭", "🇹🇱", "🇹🇬",
        "🇹🇰", "🇹🇴", "🇹🇹", "🇹🇳", "🇹🇷", "🇹🇲", "🇹🇨", "🇹🇻",
        "🇺🇬", "🇺🇦", "🇦🇪", "🇬🇧", "🇺🇸", "🇺🇾", "🇺🇿", "🇻🇺",
        "🇻🇦", "🇻🇪", "🇻🇳", "🇼🇫", "🇪🇭", "🇾🇪", "🇿🇲", "🇿🇼"
    )

    val CATEGORIES: List<Category> = listOf(
        Category(CategoryId.SMILEYS, "Smileys & Emotion", SMILEYS_EMOJIS),
        Category(CategoryId.PEOPLE, "People & Body", PEOPLE_EMOJIS),
        Category(CategoryId.NATURE, "Animals & Nature", NATURE_EMOJIS),
        Category(CategoryId.FOOD, "Food & Drink", FOOD_EMOJIS),
        Category(CategoryId.ACTIVITIES, "Activities", ACTIVITIES_EMOJIS),
        Category(CategoryId.TRAVEL, "Travel & Places", TRAVEL_EMOJIS),
        Category(CategoryId.OBJECTS, "Objects", OBJECTS_EMOJIS),
        Category(CategoryId.SYMBOLS, "Symbols", SYMBOLS_EMOJIS),
        Category(CategoryId.FLAGS, "Flags", FLAGS_EMOJIS)
    )

    private val CATEGORY_KEYWORDS: Map<CategoryId, List<String>> = mapOf(
        CategoryId.SMILEYS to listOf("smiley", "smile", "face", "emotion", "happy", "sad", "laugh", "cry", "love", "heart", "وجه", "ابتسامة", "حب", "ضحك"),
        CategoryId.PEOPLE to listOf("people", "person", "hand", "wave", "thumbs", "clap", "pray", "body", "eye", "أشخاص", "يد", "إبهام"),
        CategoryId.NATURE to listOf("animal", "nature", "dog", "cat", "bird", "tree", "flower", "sun", "moon", "fire", "حيوان", "طبيعة", "قط", "كلب"),
        CategoryId.FOOD to listOf("food", "drink", "eat", "fruit", "coffee", "tea", "cake", "pizza", "burger", "طعام", "شراب", "قهوة"),
        CategoryId.ACTIVITIES to listOf("activity", "sport", "ball", "soccer", "football", "game", "music", "party", "trophy", "رياضة", "كرة", "موسيقى"),
        CategoryId.TRAVEL to listOf("travel", "car", "plane", "train", "ship", "map", "city", "house", "building", "سفر", "سيارة", "طائرة"),
        CategoryId.OBJECTS to listOf("object", "phone", "laptop", "computer", "book", "key", "lock", "money", "clock", "tool", "هاتف", "كتاب", "مفتاح"),
        CategoryId.SYMBOLS to listOf("symbol", "check", "star", "sparkle", "arrow", "warning", "mark", "number", "رمز", "نجمة", "صح"),
        CategoryId.FLAGS to listOf("flag", "country", "nation", "علم", "أعلام", "دولة")
    )

    private val EMOJI_KEYWORDS: Map<String, String> = mapOf(
        "😀" to "grin smile happy سعيد",
        "😃" to "smile happy joy سعيد",
        "😄" to "laugh smile happy ضحك",
        "😁" to "grin teeth smile",
        "😆" to "laugh haha xd ضحك",
        "😅" to "sweat laugh nervous",
        "🤣" to "rofl laugh lol ضحك",
        "😂" to "tears joy laugh lol ضحك",
        "🙂" to "smile slight",
        "😉" to "wink غمزة",
        "😊" to "blush smile warm خجل",
        "😇" to "angel halo",
        "🥰" to "love hearts adore حب",
        "😍" to "heart eyes love حب",
        "🤩" to "star struck wow",
        "😘" to "kiss love قبلة",
        "🤔" to "think thinking hmm تفكير",
        "😎" to "cool sunglasses نظارة",
        "😢" to "cry sad tear حزين",
        "😭" to "sob cry tears بكاء",
        "😡" to "angry mad غاضب",
        "🤯" to "mind blown shocked",
        "🥳" to "party celebrate احتفال",
        "❤️" to "red heart love قلب حب",
        "🧡" to "orange heart love قلب",
        "💛" to "yellow heart love قلب",
        "💚" to "green heart love قلب",
        "💙" to "blue heart love قلب",
        "💜" to "purple heart love قلب",
        "💖" to "sparkling heart love قلب",
        "💗" to "growing heart love قلب",
        "💓" to "beating heart love قلب",
        "👋" to "wave hi hello bye سلام",
        "👌" to "ok okay perfect تمام",
        "👍" to "thumbs up like yes موافق",
        "👎" to "thumbs down dislike no",
        "👏" to "clap applause bravo تصفيق",
        "🙌" to "raise hands hooray",
        "🫶" to "heart hands love",
        "🤝" to "handshake deal agree اتفاق",
        "🙏" to "pray thanks please شكرا",
        "💪" to "muscle strong flex قوة",
        "👀" to "eyes look see عيون",
        "🐶" to "dog puppy كلب",
        "🐱" to "cat kitty قط",
        "🦁" to "lion أسد",
        "🌹" to "rose flower وردة",
        "🌸" to "cherry blossom flower",
        "🌞" to "sun شمس",
        "🌙" to "moon crescent قمر",
        "⭐" to "star نجمة",
        "✨" to "sparkles magic لمعان",
        "🔥" to "fire hot lit نار",
        "💧" to "water drop ماء",
        "☕" to "coffee tea cafe قهوة",
        "🍕" to "pizza food بيتزا",
        "🍔" to "burger food برغر",
        "🎂" to "cake birthday كعك",
        "⚽" to "soccer football كرة",
        "🎮" to "game controller gaming",
        "🎵" to "music note موسيقى",
        "🏆" to "trophy win award كأس",
        "🎉" to "party confetti تهنئة",
        "🚗" to "car drive سيارة",
        "✈️" to "airplane flight travel طائرة",
        "🚀" to "rocket launch صاروخ",
        "🏠" to "house home منزل",
        "📱" to "phone mobile هاتف",
        "💻" to "laptop computer حاسوب",
        "🔑" to "key unlock مفتاح",
        "🔒" to "lock secure قفل",
        "✅" to "check done yes تم صح",
        "❌" to "cross no cancel خطأ",
        "❓" to "question mark",
        "💯" to "100 perfect مئة"
    )

    fun search(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        // 1. Direct glyph or keyword match
        for ((emoji, kw) in EMOJI_KEYWORDS) {
            if (emoji == q || kw.contains(q)) {
                if (seen.add(emoji)) {
                    results.add(emoji)
                }
            }
        }
        // 2. Category label & keyword match
        for (cat in CATEGORIES) {
            val catMatch = cat.label.lowercase().contains(q) ||
                (CATEGORY_KEYWORDS[cat.id]?.any { it.contains(q) || q.contains(it) } == true)
            for (emoji in cat.emojis) {
                if (catMatch || emoji.contains(q)) {
                    if (seen.add(emoji)) {
                        results.add(emoji)
                    }
                }
            }
        }
        return results.take(120)
    }
}

