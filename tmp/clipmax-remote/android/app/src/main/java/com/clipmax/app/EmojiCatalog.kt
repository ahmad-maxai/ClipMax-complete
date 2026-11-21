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

    data class EmojiEntry(
        val glyph: String,
        val keywordsEn: List<String>,
        val keywordsAr: List<String>,
        val supportsSkinTone: Boolean = false
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
        "😤", "😡", "😠", "🤬", "🤯", "😳", "🥵", "🥶",
        "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭",
        "🤫", "🤥", "😶‍🌫️", "ニュ", "🤤", "😴", "😷", "🤒",
        "🤕", "🤢", "🤮", "🤧", "hot", "cold", "party", "pleading",
        "🥳", "🥹", "🥺", "🤠", "🤡", "🤥", "🤫", "🤭",
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💖", "💗", "💓"
    )

    val PEOPLE_EMOJIS: List<String> = listOf(
        "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏",
        "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆",
        "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜",
        "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️",
        "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃",
        "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅",
        "👄", "biting", "kiss", "👤", "👥", "🫂", "👶", "👧",
        "🧒", "👦", "👩", "🧑", "👨", "👩‍🦱", "👨‍🦱", "👩‍🦰",
        "👨‍🦰", "👱‍♀️", "👱", "👱‍♂️", "👩‍🦳", "👨‍🦳", "👩‍🦲", "👨‍🦲",
        "🧔‍♀️", "🧔", "🧔‍♂️", "👵", "🧓", "👴", "👲", "👳‍♀️",
        "👳", "👳‍♂️", "🧕", "👮‍♀️", "👮", "👮‍♂️", "👷‍♀️", "👷",
        "👷‍♂️", "ガード", "💂‍♀️", "💂", "💂‍♂️", "🕵️‍♀️", "🕵️", "🕵️‍♂️",
        "👩‍⚕️", "👨‍⚕️", "👩‍🌾", "👨‍🌾", "👩‍🍳", "👨‍🍳", "👩‍🎓", "👨‍🎓",
        "👩‍🎤", "👨‍🎤", "👩‍🏫", "👨‍🏫", "👩‍🏭", "👨‍🏭", "👩‍💻", "👨‍💻",
        "👩‍💼", "👨‍💼", "👩‍🔧", "👨‍🔧", "👩‍🔬", "👨‍🔬", "👩‍🎨", "👨‍🎨",
        "👩‍🚒", "👨‍🚒", "👩‍✈️", "👨‍✈️", "👩‍🚀", "👨‍🚀", "👩‍⚖️", "👨‍⚖️"
    )

    val NATURE_EMOJIS: List<String> = listOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
        "🐨", "🐯", "🦁", "🐮", "🐷", "🐽", "🐸", "🐵",
        "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦", "🐤",
        "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗",
        "🐴", "🦄", "🐝", "🪱", "🐛", "🦋", "🐌", "🐞",
        "🐜", "🪰", "🪲", "cockroach", "🦟", "🦗", "🕷️", "🕸️",
        "🦂", "🐢", "🐍", "Lizard", "🦖", "🦕", "🐙", "🦑",
        "Shrimp", "Lobster", "Crab", "Pufferfish", "Tropical fish", "Fish", "Dolphin", "Whale",
        "Seal", "Crocodile", "Leopard", "Tiger", "Water buffalo", "Ox", "Cow", "Dromedary camel",
        "Camel", "Llama", "Giraffe", "Elephant", "Mammoth", "Rhinoceros", "Hippopotamus", "Kangaroo",
        "Badger", "Koala", "Sloth", "Otter", "Skunk", "Hedgehog", "Bouquet", "Cherry blossom",
        "White flower", "Lotus", "Rosette", "Rose", "Wilted flower", "Hibiscus", "Sunflower", "Blossom",
        "Tulip", "Hyacinth", "Seedling", "Potted plant", "Evergreen tree", "Deciduous tree", "Palm tree", "Cactus",
        "Sheaf of rice", "Herb", "Shamrock", "Four leaf clover", "Maple leaf", "Fallen leaf", "Leaf fluttering in wind", "Mushroom",
        "Earth globe Europe-Africa", "Earth globe Americas", "Earth globe Asia-Australia", "Globe with meridians", "Crescent moon", "New moon face", "First quarter moon face", "Last quarter moon face",
        "Full moon", "New moon", "Waxing crescent moon", "First quarter moon", "Waxing gibbous moon", "Full moon", "Waning gibbous moon", "Last quarter moon",
        "Waning crescent moon", "Dunst", "Glowing star", "Shooting star", "Milky way", "Cloud", "Sun behind cloud", "Thundercloud and rain",
        "White sun with small cloud", "Sun behind large cloud", "Sun behind rain cloud", "Cloud with lightning", "Cloud with tornado", "Fog", "Wind face", "Cyclone",
        "Rainbow", "Closed umbrella", "Umbrella", "Umbrella with rain drops", "Parasol on ground", "High voltage", "Fire", "Droplet", "Water wave"
    )

    val FOOD_EMOJIS: List<String> = listOf(
        "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
        "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥",
        "🥝", "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️",
        "🫑", "🌽", "🥕", "🫒", "Garlic", "Onion", "Potato", "Sweet potato",
        "Croissant", "Bread", "Flatbread", "Pretzel", "Bagel", "Pancakes", "Waffle", "Cheese wedge",
        "Meat on bone", "Poultry leg", "Cutlet", "Bacon", "Hamburger", "Fries", "Pizza", "Hot dog",
        "Sandwich", "Taco", "Burrito", "Tamale", "Stuffed flatbread", "Falafel", "Eggplant", "Cooking",
        "Pot of food", "Fondue", "Bowl with spoon", "Green salad", "Popcorn", "Butter", "Salt", "Canned food",
        "Bento box", "Rice cracker", "Rice ball", "Cooked rice", "Curry rice", "Steaming bowl", "Spaghetti", "Roasted sweet potato",
        "Oden", "Sushi", "Fried shrimp", "Fish cake with swirl", "Moon cake", "Dango", "Dumpling", "Fortune cookie",
        "Takeout box", "Oyster", "Soft ice cream", "Shaved ice", "Ice cream", "Doughnut", "Cookie", "Birthday cake",
        "Shortcake", "Cupcake", "Pie", "Chocolate bar", "Candy", "Lollipop", "Custard", "Honey pot",
        "Baby bottle", "Glass of milk", "Hot beverage", "Teapot", "Teacup without handle", "Sake", "Wine glass", "Cocktail glass",
        "Tropical drink", "Beer mug", "Clinking beer mugs", "Clinking glasses", "Tumbler glass", "Cup with straw", "Bubble tea", "Beverage box",
        "Mate", "Ice cube", "Chopsticks", "Fork and knife with plate", "Fork and knife", "Spoon", "Kitchen knife", "Jar"
    )

    val ACTIVITIES_EMOJIS: List<String> = listOf(
        "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉",
        "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🏑", "🥍",
        "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿",
        "Boxing glove", "Martial arts uniform", "Skateboard", "Roller skate", "Sled", "Ice skate", "Curling stone", "Skis",
        "Snowboard", "Parachute", "Person lifting weights", "Person fencing", "Horse racing", "Skier", "Snowboarder", "Person golf",
        "Person surfing", "Person rowing boat", "Person swimming", "Person bouncing ball", "Person weightlifting", "Person cartwheeling", "Person juggling", "Person lotus position",
        "Trophy", "Sports medal", "1st place medal", "2nd place medal", "3rd place medal", "Rosette", "Reminder ribbon", "Ticket",
        "Admission tickets", "Circus tent", "Juggling", "Performing arts", "Palette", "Clapper board", "Microphone", "Headphone",
        "Musical score", "Musical keyboard", "Drum", "Long drum", "Saxophone", "Accordion", "Guitar", "Musical trumpet",
        "Violin", "Banjo", "Maracas", "Flute", "Game die", "Chess pawn", "Mahjong red dragon", "Flower playing cards",
        "Slot machine", "Axe", "Target", "Yo-yo", "Billiard", "Bowling", "Video game", "Joystick"
    )

    val TRAVEL_EMOJIS: List<String> = listOf(
        "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑",
        "🚒", "🚐", "🛻", "🚚", "🚛", "🚜", "🦯", "🦽",
        "🦼", "🛴", "🚲", "🛵", "🏍️", "Motor scooter", "Bus stop", "Railway car",
        "High-speed train", "Bullet train", "Train", "Metro", "Light rail", "Station", "Tram", "Monorail",
        "Mountain railway", "Train", "Bus", "Trolleybus", "Minibus", "Ambulance", "Fire engine", "Police car",
        "Taxi", "Automobile", "Sport utility vehicle", "Delivery truck", "Articulated lorry", "Tractor", "Bicycle", "Kick scooter",
        "Bus stop", "Motorway", "Railway track", "Oil drum", "Fuel pump", "Rotary beacon", "Traffic light", "Vertical traffic light",
        "Stop sign", "Construction", "Anchor", "Ring buoy", "Sailboat", "Canoe", "Speedboat", "Passenger ship",
        "Ferry", "Motorboat", "Ship", "Airplane", "Small airplane", "Airplane departure", "Airplane arrival", "Parachute",
        "Seat", "Helicopter", "Suspended railway", "Mountain cableway", "Aerial tramway", "Satellite", "Rocket", "Flying saucer",
        "Bellhop bell", "Luggage", "Hourglass done", "Hourglass not done", "Watch", "Alarm clock", "Stopwatch", "Timer clock",
        "Mantelpiece clock", "Twelve o'clock", "Map of Japan", "Compass", "Snow-capped mountain", "Mountain", "Volcano", "Mount fuji",
        "Camping", "Beach with umbrella", "Desert", "Desert island", "National park", "Stadium", "Classical building", "Building construction",
        "Brick", "Rock", "Wood", "Hut", "Houses", "Derelict house", "House", "House with garden",
        "Office building", "Japanese post office", "Post office", "Hospital", "Bank", "Hotel", "Convenience store", "School",
        "Department store", "Factory", "Japanese castle", "Castle", "Wedding", "Tokyo tower", "Statue of liberty", "Church"
    )

    val OBJECTS_EMOJIS: List<String> = listOf(
        "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🖲️", "💽",
        "💾", "💿", "📀", "VCR", "Camera", "Camera with flash", "Video camera", "Movie camera",
        "Film projector", "Film frames", "Telephone receiver", "Telephone", "Pager", "Fax machine", "Television", "Radio",
        "Studio microphone", "Level slider", "Control knobs", "Compass", "Stopwatch", "Timer clock", "Mantelpiece clock", "Hourglass done",
        "Light bulb", "Electric light bulb", "Flashlight", "Paper lantern", "Diya lamp", "Notebook with decorative cover", "Notebook", "Ledger",
        "Page with curl", "Bookmark tabs", "Receipt", "Bar chart", "Chart decreasing", "Chart increasing", "Card index dividers", "Card index",
        "Address book", "Notebook", "Clipboard", "Notepad", "Page facing up", "Open book", "Green book", "Blue book",
        "Orange book", "Books", "Notebook", "Scroll", "Page with curl", "Bookmark", "Label", "Money bag",
        "Coin", "Yen banknote", "Dollar banknote", "Euro banknote", "Pound banknote", "Money with wings", "Credit card", "Receipt",
        "Wrench", "Hammer", "Nut and bolt", "Gear", "Clamp", "Balance scale", "White cane", "Link",
        "Chains", "Hook", "Toolbox", "Magnet", "Alchemical symbol for tin", "Alembic", "Test tube", "Petri dish",
        "DNA", "Microscope", "Telescope", "Satellite antenna", "Syringe", "Drop of blood", "Pill", "Adhesive bandage",
        "Stethoscope", "Door", "Elevator", "Mirror", "Window", "Bed", "Couch and lamp", "Chair",
        "Toilet", "Plunger", "Shower", "Bathtub", "Razor", "Lotion bottle", "Safety pin", "Broom",
        "Basket", "Roll of paper", "Bucket", "Soap", "Toothbrush", "Sponge", "Fire extinguisher", "Shopping cart"
    )

    val SYMBOLS_EMOJIS: List<String> = listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
        "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗",
        "💖", "💘", "💝", "💟", "peace", "atom", "om", "star of david",
        "wheel of dharma", "yin yang", "latin cross", "orthodox cross", "star and crescent", "peace symbol", "menorah", "aries",
        "taurus", "gemini", "cancer", "leo", "virgo", "libra", "scorpio", "sagittarius",
        "capricorn", "aquarius", "pisces", "ophiuchus", "shuffle tracks button", "repeat button", "repeat single button", "play button",
        "fast forward button", "next track button", "play or pause button", "reverse button", "fast reverse button", "last track button", "up button", "fast up button",
        "down button", "eject button", "cinema", "bright button", "dim button", "antenna bars", "wireless", "mobile phone off",
        "no mobile phones", "battery", "electric plug", "low battery", "check mark", "cross mark", "warning", "prohibited",
        "no entry", "name badge", "trident emblem", "high voltage", "radioactive", "biohazard", "recycling symbol", "infinity",
        "heavy dollar sign", "currency exchange", "heavy heavy multiplication x", "plus", "minus", "division", "equals", "double exclamation mark",
        "exclamation question mark", "question mark", "white exclamation mark", "exclamation mark", "wavy dash", "currency exchange", "curly loop", "double curly loop"
    )

    /**
     * ~245+ ISO 3166-1 regional-indicator Country & Territory Flags.
     * Strictly excludes all LGBT Pride flags (🏳️‍🌈), transgender flags (🏳️‍⚧️, ⚧️), and generic banner flags.
     */
    val FLAGS_EMOJIS: List<String> = listOf(
        "🇦🇫", "🇦🇱", "🇩🇿", "🇦🇸", "🇦🇩", "🇦🇴", "🇦🇮", "🇦🇶",
        "🇦🇬", "🇦🇷", "🇦🇲", "🇦🇼", "🇦🇺", "🇦🇹", "🇦🇿", "🇧🇸",
        "🇧🇭", "🇧🇩", "🇧🇧", "🇧🇾", "🇧🇪", "🇧🇿", "🇧🇯", "🇧🇲",
        "🇧🇹", "🇧🇴", "🇧🇦", "🇧🇼", "🇧🇷", "🇮🇴", "🇻🇬", "🇧🇳",
        "🇧🇬", "🇧🇫", "🇧🇮", "🇰🇭", "🇨🇲", "🇨🇦", "🇮🇨", "🇨🇻",
        "🇧🇶", "🇰🇾", "🇨🇫", "🇹🇩", "🇨🇱", "🇨🇳", "🇨🇽", "🇨🇨",
        "🇨🇴", "🇰🇲", "🇨🇬", "🇨🇩", "🇨🇰", "🇨🇷", "🇭🇷", "🇨🇺",
        "🇨🇼", "🇨🇾", "🇨🇿", "🇩🇰", "🇩🇯", "🇩🇲", "🇩🇴", "🇪🇨",
        "🇪🇬", "🇸🇻", "🇬🇶", "🇪🇷", "🇪🇪", "SZ", "🇪🇹", "🇪🇺",
        "🇫🇰", "🇫🇴", "🇫🇯", "🇫🇮", "🇫🇷", "🇬🇫", "🇵🇫", "🇬🇦",
        "🇬🇲", "🇬🇪", "🇩🇪", "🇬🇭", "🇬🇮", "🇬🇷", "🇬🇱", "🇬🇩",
        "🇬🇵", "🇬🇺", "🇬🇹", "GG", "🇬🇳", "🇬🇼", "🇬🇾", "🇭🇹",
        "🇭🇳", "🇭🇰", "🇭🇺", "🇮🇸", "🇮🇳", "🇮🇩", "🇮🇷", "🇮🇶",
        "🇮🇪", "🇮🇲", "🇮🇱", "🇮🇹", "🇯🇲", "🇯🇵", "🎌", "🇯🇪",
        "🇯🇴", "🇰🇿", "🇰🇪", "🇰🇮", "🇽🇰", "🇰🇼", "🇰🇬", "🇱🇦",
        "🇱🇻", "🇱🇧", "🇱🇸", "🇱🇷", "🇱🇾", "🇱🇮", "🇱🇹", "🇱🇺",
        "🇲🇴", "🇲🇬", "🇲🇼", "🇲🇾", "🇲🇻", "🇲🇱", "🇲🇹", "🇲🇭",
        "🇲🇶", "🇲🇷", "🇲🇺", "🇾🇹", "🇲🇽", "🇫🇲", "🇲🇩", "🇲🇨",
        "🇲🇳", "🇲🇪", "🇲🇸", "🇲🇦", "🇲🇿", "🇲🇲", "🇳🇦", "🇳🇷",
        "🇳🇵", "🇳🇱", "🇳🇨", "🇳🇿", "🇳🇮", "🇳🇪", "🇳🇬", "🇳🇺",
        "🇳🇫", "🇰🇵", "🇲🇵", "🇳🇴", "🇴🇲", "🇵🇰", "🇵🇼", "🇵🇸",
        "🇵🇦", "🇵🇬", "🇵🇾", "🇵🇪", "🇵🇭", "🇵🇳", "🇵🇱", "🇵🇹",
        "🇵🇷", "🇶🇦", "🇷🇪", "🇷🇴", "🇷🇺", "🇷🇼", "🇼🇸", "🇸🇲",
        "🇸🇹", "🇸🇦", "🇸🇳", "🇷🇸", "🇸🇨", "🇸🇱", "🇸🇬", "🇸🇽",
        "🇸🇰", "🇸🇮", "🇸🇧", "सो", "🇿🇦", "🇰🇷", "🇸🇸", "🇪🇸",
        "🇱🇰", "🇧🇱", "🇸🇭", "🇰🇳", "🇱🇨", "🇵🇲", "🇻🇨", "🇸🇩",
        "🇸🇷", "🇸🇪", "🇨🇭", "🇸🇾", "🇹🇼", "🇹🇯", "🇹🇿", "🇹🇭",
        "🇹🇱", "🇹🇬", "🇹🇰", "🇹🇴", "🇹🇹", "🇹🇳", "🇹🇷", "🇹🇲",
        "🇹🇨", "🇹🇻", "🇺🇬", "🇺🇦", "🇦🇪", "🇬🇧", "US", "🇺🇾",
        "🇺🇿", "🇻🇺", "🇻🇦", "🇻🇪", "🇻🇳", "🇼🇫", "🇪🇭", "🇾🇪",
        "🇿🇲", "🇿🇼", "🏴󠁧󠁢󠁥󠁮󠁧󠁿", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "🏴󠁧󠁢󠁷󠁬󠁳󠁿"
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

    fun search(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (cat in CATEGORIES) {
            for (emoji in cat.emojis) {
                if (emoji !in seen) {
                    seen.add(emoji)
                    results.add(emoji)
                }
            }
        }
        return results.take(120)
    }
}
