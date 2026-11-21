package com.clipmax.app

/**
 * Offline sticker catalog managing the built-in sticker packs (`reactions`, `daily`, `notes`)
 * matching the 24 pre-rendered RGBA PNG assets bundled in assets/stickers/.
 * Pure JVM object with zero Android dependencies.
 */
internal object StickerCatalog {

    data class StickerItem(
        val id: String,
        val packId: String,
        val title: String,
        val assetPath: String,
        val keywords: List<String>
    )

    data class StickerPack(
        val packId: String,
        val titleEn: String,
        val titleAr: String,
        val iconAssetPath: String,
        val items: List<StickerItem>
    )

    val PACKS: List<StickerPack> = listOf(
        StickerPack(
            packId = "reactions",
            titleEn = "Reactions",
            titleAr = "تفاعلات",
            iconAssetPath = "stickers/reactions/react_great.png",
            items = listOf(
                StickerItem("react_great", "reactions", "Great", "stickers/reactions/react_great.png", listOf("great", "good", "nice", "رائع", "ممتاز")),
                StickerItem("react_love", "reactions", "Love", "stickers/reactions/react_love.png", listOf("love", "heart", "حب", "إعجاب")),
                StickerItem("react_haha", "reactions", "Haha", "stickers/reactions/react_haha.png", listOf("haha", "lol", "laugh", "ضحك", "هههه")),
                StickerItem("react_wow", "reactions", "Wow", "stickers/reactions/react_wow.png", listOf("wow", "surprised", "واو", "مذهل")),
                StickerItem("react_party", "reactions", "Party", "stickers/reactions/react_party.png", listOf("party", "celebrate", "احتفال")),
                StickerItem("react_thinking", "reactions", "Thinking", "stickers/reactions/react_thinking.png", listOf("thinking", "hmm", "تفكير")),
                StickerItem("react_coffee", "reactions", "Coffee", "stickers/reactions/react_coffee.png", listOf("coffee", "break", "قهوة")),
                StickerItem("react_100", "reactions", "100", "stickers/reactions/react_100.png", listOf("100", "perfect", "مئة"))
            )
        ),
        StickerPack(
            packId = "daily",
            titleEn = "Daily",
            titleAr = "يومي",
            iconAssetPath = "stickers/daily/daily_hi.png",
            items = listOf(
                StickerItem("daily_hi", "daily", "Hi", "stickers/daily/daily_hi.png", listOf("hi", "hello", "مرحباً", "أهلاً")),
                StickerItem("daily_thanks", "daily", "Thanks", "stickers/daily/daily_thanks.png", listOf("thanks", "thank you", "شكراً")),
                StickerItem("daily_omw", "daily", "OMW", "stickers/daily/daily_omw.png", listOf("omw", "on my way", "في الطريق")),
                StickerItem("daily_ok", "daily", "OK", "stickers/daily/daily_ok.png", listOf("ok", "okay", "حسناً")),
                StickerItem("daily_night", "daily", "Night", "stickers/daily/daily_night.png", listOf("night", "sleep", "تصبح على خير")),
                StickerItem("daily_please", "daily", "Please", "stickers/daily/daily_please.png", listOf("please", "pls", "رجاءً")),
                StickerItem("daily_clap", "daily", "Clap", "stickers/daily/daily_clap.png", listOf("clap", "applause", "تصفيق")),
                StickerItem("daily_goodjob", "daily", "Good Job", "stickers/daily/daily_goodjob.png", listOf("good job", "well done", "عمل جيد"))
            )
        ),
        StickerPack(
            packId = "notes",
            titleEn = "Notes",
            titleAr = "ملاحظات",
            iconAssetPath = "stickers/notes/notes_pinned.png",
            items = listOf(
                StickerItem("notes_pinned", "notes", "Pinned", "stickers/notes/notes_pinned.png", listOf("pinned", "important", "مهم", "ثبيت")),
                StickerItem("notes_saved", "notes", "Saved", "stickers/notes/notes_saved.png", listOf("saved", "clipboard", "حفظ")),
                StickerItem("notes_idea", "notes", "Idea", "stickers/notes/notes_idea.png", listOf("idea", "lightbulb", "فكرة")),
                StickerItem("notes_reminder", "notes", "Reminder", "stickers/notes/notes_reminder.png", listOf("reminder", "alarm", "تذكير")),
                StickerItem("notes_done", "notes", "Done", "stickers/notes/notes_done.png", listOf("done", "check", "تم")),
                StickerItem("notes_focus", "notes", "Focus", "stickers/notes/notes_focus.png", listOf("focus", "work", "تركيز")),
                StickerItem("notes_link", "notes", "Link", "stickers/notes/notes_link.png", listOf("link", "url", "رابط")),
                StickerItem("notes_send", "notes", "Send", "stickers/notes/notes_send.png", listOf("send", "forward", "إرسال"))
            )
        )
    )

    fun search(query: String): List<StickerItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return PACKS.flatMap { it.items }
        return PACKS.flatMap { pack ->
            pack.items.filter { item ->
                item.title.lowercase().contains(q) || item.keywords.any { it.contains(q) }
            }
        }
    }
}
