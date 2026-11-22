package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaKeyboardSpecTest {

    @Test
    fun testAllFourMediaModesExistAndHaveGridMetrics() {
        val modes = MediaKeyboardSpec.MediaSubMode.values().toSet()
        assertEquals(
            setOf(
                MediaKeyboardSpec.MediaSubMode.EMOJI,
                MediaKeyboardSpec.MediaSubMode.GIFS,
                MediaKeyboardSpec.MediaSubMode.STICKERS,
                MediaKeyboardSpec.MediaSubMode.KAOMOJI
            ),
            modes
        )
        assertTrue(MediaKeyboardSpec.STICKER_COLS_PORTRAIT >= 4)
        assertTrue(MediaKeyboardSpec.STICKER_COLS_LANDSCAPE >= MediaKeyboardSpec.STICKER_COLS_PORTRAIT)
        assertTrue(MediaKeyboardSpec.KAOMOJI_COLS_PORTRAIT >= 4)
        assertTrue(MediaKeyboardSpec.GIF_COLS_PORTRAIT >= 2)
        assertTrue(MediaKeyboardSpec.TOP_BAR_HEIGHT_PORTRAIT_DP >= 40f)
        assertTrue(MediaKeyboardSpec.BOTTOM_BAR_HEIGHT_PORTRAIT_DP >= 40f)
    }

    @Test
    fun testMediaViewportSpecExpandsComfortablyWithinScreenBounds() {
        for (preset in listOf("compact", "normal", "tall")) {
            val stdPortrait = KeyboardLayoutSpec.computeViewportSpecDp(preset, 800f, isLandscape = false)
            val mediaPortrait = KeyboardLayoutSpec.computeMediaViewportSpecDp(preset, 800f, isLandscape = false)
            assertTrue(mediaPortrait.mainPanelHeightDp >= stdPortrait.mainPanelHeightDp)
            assertTrue(mediaPortrait.totalContentHeightDp <= 800f * 0.48f + 0.01f)

            val stdLandscape = KeyboardLayoutSpec.computeViewportSpecDp(preset, 390f, isLandscape = true)
            val mediaLandscape = KeyboardLayoutSpec.computeMediaViewportSpecDp(preset, 390f, isLandscape = true)
            assertTrue(mediaLandscape.mainPanelHeightDp >= stdLandscape.mainPanelHeightDp)
            assertTrue(mediaLandscape.totalContentHeightDp <= 390f * 0.60f + 0.01f)
        }
    }

    @Test
    fun testStickerCatalogHasThreePacks24ItemsAndAllAssetsExistOnDisk() {
        assertEquals(3, StickerCatalog.PACKS.size)
        assertEquals(listOf("reactions", "daily", "notes"), StickerCatalog.PACKS.map { it.packId })

        val allItems = StickerCatalog.PACKS.flatMap { it.items }
        assertEquals(24, allItems.size)
        assertEquals(24, allItems.map { it.id }.toSet().size)

        val assetsRoot = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets")
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected Android assets directory in workspace", assetsRoot)

        for (pack in StickerCatalog.PACKS) {
            assertEquals(8, pack.items.size)
            assertTrue(pack.titleEn.isNotBlank())
            assertTrue(pack.titleAr.isNotBlank())
            assertTrue(File(assetsRoot, pack.iconAssetPath).isFile)
            for (item in pack.items) {
                val assetFile = File(assetsRoot, item.assetPath)
                assertTrue("Missing bundled sticker PNG: ${assetFile.path}", assetFile.isFile)
                assertTrue("Sticker PNG must not be empty: ${assetFile.path}", assetFile.length() > 0L)
            }
        }

        // Empty search returns all 24 stickers; targeted EN/AR queries filter accurately
        assertEquals(24, StickerCatalog.search("").size)
        val coffeeMatches = StickerCatalog.search("coffee")
        assertTrue(coffeeMatches.any { it.id == "react_coffee" })
        val arabicMatches = StickerCatalog.search("شكراً")
        assertTrue(arabicMatches.any { it.id == "daily_thanks" })
        assertTrue(StickerCatalog.search("nonexistent_sticker_xyz").isEmpty())
    }

    @Test
    fun testKaomojiCatalogHas13CategoriesPreservesUnicodeAndSearches() {
        assertEquals(13, KaomojiCatalog.CATEGORIES.size)
        assertEquals(KaomojiCatalog.CategoryId.values().size, KaomojiCatalog.CATEGORIES.size)
        assertEquals(13, KaomojiCatalog.CATEGORIES.map { it.id }.toSet().size)

        for (cat in KaomojiCatalog.CATEGORIES) {
            assertTrue(cat.labelEn.isNotBlank())
            assertTrue(cat.labelAr.isNotBlank())
            assertTrue("Category ${cat.id} must have entries", cat.items.isNotEmpty())
            assertTrue(cat.items.all { it.isNotBlank() })
        }

        val allEntries = KaomojiCatalog.CATEGORIES.flatMap { it.items }
        assertTrue("Shrug kaomoji must preserve backslash & katakana", allEntries.contains("¯\\_(ツ)_/¯"))
        assertTrue("Excited kaomoji must preserve Arabic/Unicode digits", allEntries.contains("٩(◕‿◕)۶"))

        val shrugMatches = KaomojiCatalog.search("shrug")
        assertTrue(shrugMatches.contains("¯\\_(ツ)_/¯"))
        val arabicHappyMatches = KaomojiCatalog.search("سعيد")
        assertTrue(arabicHappyMatches.contains("(^‿^)"))
        assertTrue(KaomojiCatalog.search("").isEmpty())
        assertTrue(KaomojiCatalog.search("zzzz_no_kaomoji_999").isEmpty())
    }

    @Test
    fun testEmojiCatalogSearchAndFlagIntegrity() {
        assertTrue(EmojiCatalog.FLAGS_EMOJIS.contains("🇸🇴"))
        assertTrue(EmojiCatalog.FLAGS_EMOJIS.contains("🇱🇨"))

        val pizzaResults = EmojiCatalog.search("pizza")
        assertTrue(pizzaResults.contains("🍕"))
        val arabicHeartResults = EmojiCatalog.search("قلب")
        assertTrue(arabicHeartResults.contains("❤️"))
        assertTrue(EmojiCatalog.search("").isEmpty())
        assertTrue(EmojiCatalog.search("zzzz_no_emoji_999").isEmpty())
    }

    @Test
    fun testTenorV2JsonParsingAndUrlValidationSecurity() {
        assertTrue(TenorV2GifProvider.FEATURED_CATEGORIES.any { it.first == "featured" })
        assertTrue(TenorV2GifProvider.FEATURED_CATEGORIES.any { it.first == "recent" })

        assertNotNull(TenorV2GifProvider.validateUrl("https://tenor.googleapis.com/v2/featured?key=abc"))
        assertNotNull(TenorV2GifProvider.validateUrl("https://media.tenor.com/sample/funny.gif"))
        assertNull(TenorV2GifProvider.validateUrl("http://media.tenor.com/sample/funny.gif"))
        assertNull(TenorV2GifProvider.validateUrl("https://evil.example.com/payload.gif"))

        val sampleJson = """
            {
              "results": [
                {
                  "id": "12345",
                  "title": "",
                  "content_description": "Happy Cat Dance",
                  "media_formats": {
                    "tinygif": {
                      "url": "https://media.tenor.com/abc/cat-tiny.gif",
                      "dims": [220, 165]
                    },
                    "gif": {
                      "url": "https://media.tenor.com/abc/cat-full.gif",
                      "dims": [480, 360]
                    }
                  }
                },
                {
                  "id": "bad_host",
                  "title": "Blocked",
                  "media_formats": {
                    "tinygif": { "url": "https://untrusted.example.org/x.gif" },
                    "gif": { "url": "https://untrusted.example.org/x-full.gif" }
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = TenorV2GifProvider.parseTenorJsonResponse(sampleJson)
        assertEquals(1, parsed.size)
        val item = parsed[0]
        assertEquals("12345", item.id)
        assertEquals("Happy Cat Dance", item.title)
        assertEquals("https://media.tenor.com/abc/cat-tiny.gif", item.previewUrl)
        assertEquals("https://media.tenor.com/abc/cat-full.gif", item.gifUrl)
        assertEquals(220, item.width)
        assertEquals(165, item.height)
    }

    @Test
    fun testTenorApiKeyResolutionAndMasking() {
        assertEquals("", MediaKeyboardSpec.TenorApiKeyConfig.resolveEffectiveKey(null, ""))
        assertFalse(MediaKeyboardSpec.TenorApiKeyConfig.isConfigured(""))
        assertEquals("res_key_1234", MediaKeyboardSpec.TenorApiKeyConfig.resolveEffectiveKey("   ", " res_key_1234 "))
        assertEquals("pref_key_9999", MediaKeyboardSpec.TenorApiKeyConfig.resolveEffectiveKey(" pref_key_9999 ", "res_key_1234"))
        assertTrue(MediaKeyboardSpec.TenorApiKeyConfig.isConfigured("AIzaSyDummyTestValue"))
        assertEquals("AIza••••alue", MediaKeyboardSpec.TenorApiKeyConfig.maskKey("AIzaSyDummyTestValue"))
    }

    @Test
    fun testRichContentMimeCompatibilityAndCacheNaming() {
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(null, "image/png"))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(emptyArray(), "image/gif"))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("text/plain"), "image/png"))
        assertTrue(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("image/png"), "image/png"))
        assertTrue(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("image/*"), "image/gif"))
        assertTrue(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("*/*"), "image/png"))

        val sticker = StickerCatalog.PACKS.first().items.first()
        assertEquals("sticker_${sticker.id}.png", MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForSticker(sticker))
        val gif = GifProvider.GifItem("id/unsafe:1", "Demo", "https://media.tenor.com/a.gif", "https://media.tenor.com/b.gif", 200, 150)
        assertEquals("gif_prev_id_unsafe_1.gif", MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForGif(gif, isPreview = true))
        assertEquals("gif_full_id_unsafe_1.gif", MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForGif(gif, isPreview = false))
    }

    @Test
    fun testRecentHistoriesForStickersKaomojiAndGifs() {
        val stickerHist = MediaKeyboardSpec.RecentStickerHistory(maxEntries = 3)
        stickerHist.record("react_great")
        stickerHist.record("daily_hi")
        stickerHist.record("notes_done")
        stickerHist.record("react_love")
        stickerHist.record("daily_hi")
        assertEquals(listOf("daily_hi", "react_love", "notes_done"), stickerHist.items().map { it.id })
        val restoredStickers = MediaKeyboardSpec.RecentStickerHistory(
            MediaKeyboardSpec.RecentStickerHistory.decode(stickerHist.encode())
        )
        assertEquals(listOf("daily_hi", "react_love", "notes_done"), restoredStickers.items().map { it.id })

        val kaomojiHist = MediaKeyboardSpec.RecentKaomojiHistory(maxEntries = 3)
        kaomojiHist.record(":-)")
        kaomojiHist.record("¯\\_(ツ)_/¯")
        kaomojiHist.record("(^‿^)")
        kaomojiHist.record(":-)")
        assertEquals(listOf(":-)", "(^‿^)", "¯\\_(ツ)_/¯"), kaomojiHist.items())
        val restoredKaomoji = MediaKeyboardSpec.RecentKaomojiHistory(
            MediaKeyboardSpec.RecentKaomojiHistory.decode(kaomojiHist.encode())
        )
        assertEquals(kaomojiHist.items(), restoredKaomoji.items())

        val gif1 = GifProvider.GifItem("g1", "One", "https://media.tenor.com/1p.gif", "https://media.tenor.com/1f.gif", 200, 150)
        val gif2 = GifProvider.GifItem("g2", "Two", "https://media.tenor.com/2p.gif", "https://media.tenor.com/2f.gif", 320, 240)
        val gifHist = MediaKeyboardSpec.RecentGifHistory(maxEntries = 4)
        gifHist.record(gif1)
        gifHist.record(gif2)
        gifHist.record(gif1)
        assertEquals(listOf("g1", "g2"), gifHist.items().map { it.id })
        val restoredGifs = MediaKeyboardSpec.RecentGifHistory(
            MediaKeyboardSpec.RecentGifHistory.decode(gifHist.encode())
        )
        assertEquals(gifHist.items(), restoredGifs.items())
    }

    @Test
    fun testMediaStringsLocalizedInEnglishAndArabic() {
        for (locale in listOf("en", "ar")) {
            assertTrue(AppUiStrings.mediaEmojiTabA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaGifTabA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaStickersTabA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaKaomojiTabA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.richMediaUnsupportedStatus(locale).isNotBlank())
            assertTrue(AppUiStrings.tenorApiKeyRequiredTitle(locale).isNotBlank())
            assertTrue(AppUiStrings.tenorApiKeyRequiredDesc(locale).isNotBlank())
            assertTrue(AppUiStrings.loadingGifsMessage(locale).isNotBlank())
            assertTrue(AppUiStrings.networkErrorGifsMessage(locale).isNotBlank())
            assertTrue(AppUiStrings.retryLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.emptyStickersMessage(locale).isNotBlank())
            assertTrue(AppUiStrings.emptyGifsMessage(locale).isNotBlank())
        }
    }
}
