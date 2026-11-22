package com.clipmax.app

import java.util.Locale

/**
 * Curated bundled offline animated GIF reaction pack stored at `android/app/src/main/assets/gifs/`.
 * All items are genuine multi-frame GIF89a animations that preview smoothly with ImageDecoder
 * and insert without network access.
 * Pure JVM object with zero Android framework imports.
 */
internal object OfflineGifCatalog {

    val ITEMS: List<GifProvider.GifItem> = listOf(
        GifProvider.GifItem(
            id = "offline_gif_wave",
            title = "Wave Hello",
            previewUrl = "asset:///gifs/gif_wave.gif",
            gifUrl = "asset:///gifs/gif_wave.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_wave.gif",
            keywords = listOf("wave", "hi", "hello", "hey", "bye", "تحية", "مرحباً", "وداع")
        ),
        GifProvider.GifItem(
            id = "offline_gif_heart",
            title = "Sending Love",
            previewUrl = "asset:///gifs/gif_heart.gif",
            gifUrl = "asset:///gifs/gif_heart.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_heart.gif",
            keywords = listOf("love", "heart", "warm", "miss you", "حب", "قلب", "ود")
        ),
        GifProvider.GifItem(
            id = "offline_gif_thumbs_up",
            title = "Thumbs Up",
            previewUrl = "asset:///gifs/gif_thumbs_up.gif",
            gifUrl = "asset:///gifs/gif_thumbs_up.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_thumbs_up.gif",
            keywords = listOf("agree", "thumbs up", "ok", "yes", "nice", "موافق", "نعم", "رائع")
        ),
        GifProvider.GifItem(
            id = "offline_gif_party",
            title = "Celebration Time",
            previewUrl = "asset:///gifs/gif_party.gif",
            gifUrl = "asset:///gifs/gif_party.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_party.gif",
            keywords = listOf("party", "celebrate", "congrats", "birthday", "احتفال", "مبروك")
        ),
        GifProvider.GifItem(
            id = "offline_gif_fire",
            title = "On Fire",
            previewUrl = "asset:///gifs/gif_fire.gif",
            gifUrl = "asset:///gifs/gif_fire.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_fire.gif",
            keywords = listOf("fire", "lit", "awesome", "hot", "نار", "حماس", "مشتعل")
        ),
        GifProvider.GifItem(
            id = "offline_gif_lol",
            title = "So Funny LOL",
            previewUrl = "asset:///gifs/gif_lol.gif",
            gifUrl = "asset:///gifs/gif_lol.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_lol.gif",
            keywords = listOf("haha", "lol", "laugh", "funny", "happy", "ضحك", "مضحك", "هههه")
        ),
        GifProvider.GifItem(
            id = "offline_gif_coffee",
            title = "Coffee Break",
            previewUrl = "asset:///gifs/gif_coffee.gif",
            gifUrl = "asset:///gifs/gif_coffee.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_coffee.gif",
            keywords = listOf("coffee", "morning", "tea", "break", "قهوة", "صباح", "استراحة")
        ),
        GifProvider.GifItem(
            id = "offline_gif_thinking",
            title = "Thinking...",
            previewUrl = "asset:///gifs/gif_thinking.gif",
            gifUrl = "asset:///gifs/gif_thinking.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_thinking.gif",
            keywords = listOf("thinking", "hmm", "wonder", "question", "تفكير", "تأمل")
        ),
        GifProvider.GifItem(
            id = "offline_gif_clap",
            title = "Applause",
            previewUrl = "asset:///gifs/gif_clap.gif",
            gifUrl = "asset:///gifs/gif_clap.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_clap.gif",
            keywords = listOf("clap", "applause", "bravo", "good job", "تصفيق", "أحسنت")
        ),
        GifProvider.GifItem(
            id = "offline_gif_sparkles",
            title = "Awesome Sparkle",
            previewUrl = "asset:///gifs/gif_sparkles.gif",
            gifUrl = "asset:///gifs/gif_sparkles.gif",
            width = 160,
            height = 160,
            assetPath = "gifs/gif_sparkles.gif",
            keywords = listOf("wow", "sparkles", "magic", "shine", "مذهل", "واو", "بريق")
        )
    )

    fun search(query: String): List<GifProvider.GifItem> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return ITEMS
        return ITEMS.filter { item ->
            item.title.lowercase(Locale.ROOT).contains(q) ||
                item.keywords.any { it.lowercase(Locale.ROOT).contains(q) }
        }
    }
}
