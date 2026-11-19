package com.clipmax.app

/**
 * Offline curated Unicode emoji dataset grouped by standard categories.
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
        SYMBOLS
    }

    data class Category(
        val id: CategoryId,
        val label: String,
        val emojis: List<String>
    )

    val CATEGORIES: List<Category> = listOf(
        Category(
            id = CategoryId.SMILEYS,
            label = "Smileys",
            emojis = listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
                "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
                "😘", "😗", "😚", "😋", "😛", "😜", "🤪", "😝",
                "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐",
                "😑", "😶", "😏", "😒", "🙄", "😬", "😌", "😔",
                "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮",
                "🥵", "🥶", "😎", "🤓", "🧐", "😕", "😟", "🙁",
                "😮", "😯", "😲", "😳", "🥺", "😢", "😭", "😱",
                "😤", "😡", "😠", "🤬", "🥳", "🥹", "❤️", "✨"
            )
        ),
        Category(
            id = CategoryId.PEOPLE,
            label = "People",
            emojis = listOf(
                "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏",
                "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆",
                "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜",
                "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️",
                "💪", "🧠", "👀", "👤", "👥", "🫂", "🧑‍💻", "👩‍🚀"
            )
        ),
        Category(
            id = CategoryId.NATURE,
            label = "Nature",
            emojis = listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
                "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
                "🐧", "🐦", "🦉", "🐝", "🦋", "🐢", "🐙", "🐬",
                "🐳", "🌸", "🌺", "🌻", "🌹", "🌷", "🌱", "🌲",
                "🍀", "🍁", "🌍", "🌙", "⭐", "☀️", "⚡", "🔥"
            )
        ),
        Category(
            id = CategoryId.FOOD,
            label = "Food",
            emojis = listOf(
                "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓",
                "🫐", "🍒", "🍑", "🥭", "🍍", "🥑", "🍅", "🥕",
                "🌽", "🍞", "🥐", "🧀", "🍳", "🥞", "🍔", "🍟",
                "🍕", "🌮", "🌯", "🥗", "🍜", "🍣", "🍤", "🍦",
                "🍩", "🍪", "🎂", "🍫", "☕", "🍵", "🧃", "🥤"
            )
        ),
        Category(
            id = CategoryId.ACTIVITIES,
            label = "Activities",
            emojis = listOf(
                "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱",
                "🏓", "🏸", "🥊", "🎯", "🎳", "🎮", "🕹️", "🎲",
                "🧩", "♟️", "🎭", "🎨", "🎬", "🎤", "🎧", "🎼",
                "🎹", "🥁", "🎸", "🎺", "🏆", "🥇", "🎖️", "🎉"
            )
        ),
        Category(
            id = CategoryId.TRAVEL,
            label = "Travel",
            emojis = listOf(
                "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑",
                "🚒", "🚐", "🚚", "🚲", "🛵", "🏍️", "🚆", "🚇",
                "✈️", "🛫", "🚀", "🛸", "⛵", "🚢", "🗺️", "🧭",
                "🏔️", "🏖️", "🏝️", "🏙️", "🏠", "🏢", "🏥", "🏫"
            )
        ),
        Category(
            id = CategoryId.OBJECTS,
            label = "Objects",
            emojis = listOf(
                "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "💾", "💿",
                "📷", "🎥", "📞", "🔋", "🔌", "💡", "🔦", "🕰️",
                "⏳", "💰", "💳", "💎", "🔧", "🔨", "⚙️", "🔒",
                "🔓", "🔑", "📎", "📋", "📌", "📝", "📁", "🔍"
            )
        ),
        Category(
            id = CategoryId.SYMBOLS,
            label = "Symbols",
            emojis = listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
                "💔", "💯", "💢", "💥", "💫", "💦", "💨", "💬",
                "💭", "✅", "☑️", "✔️", "❌", "❎", "➕", "➖",
                "➗", "✖️", "‼️", "⁉️", "❓", "❗", "⚠️", "🔄",
                "⬆️", "⬇️", "⬅️", "➡️", "↗️", "↘️", "🔗", "🏁"
            )
        )
    )
}
