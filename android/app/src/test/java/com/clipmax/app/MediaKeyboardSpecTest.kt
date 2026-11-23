package com.clipmax.app

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class MediaKeyboardSpecTest {

    private fun findAssetsRoot(): File {
        val root = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets")
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected Android assets directory in workspace", root)
        return root!!
    }

    @Test
    fun testAllThreeMediaModesExistAndHaveGridMetrics() {
        val modes = MediaKeyboardSpec.MediaSubMode.values().toSet()
        assertEquals(
            setOf(
                MediaKeyboardSpec.MediaSubMode.EMOJI,
                MediaKeyboardSpec.MediaSubMode.STICKERS,
                MediaKeyboardSpec.MediaSubMode.KAOMOJI
            ),
            modes
        )
        assertTrue(MediaKeyboardSpec.STICKER_COLS_PORTRAIT >= 4)
        assertTrue(MediaKeyboardSpec.STICKER_COLS_LANDSCAPE >= MediaKeyboardSpec.STICKER_COLS_PORTRAIT)
        assertTrue(MediaKeyboardSpec.KAOMOJI_COLS_PORTRAIT >= 4)
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
    fun testStickerCatalogHasAtLeast8Packs300UniqueStickersAndUniquePngFiles() {
        assertTrue("Expected at least 8 packs, found ${StickerCatalog.PACKS.size}", StickerCatalog.PACKS.size >= 8)
        assertEquals(StickerCatalog.PACKS.size, StickerCatalog.PACKS.map { it.packId }.toSet().size)

        val allItems = StickerCatalog.PACKS.flatMap { it.items }
        assertTrue("Expected at least 300 stickers, found ${allItems.size}", allItems.size >= 300)
        assertEquals(allItems.size, StickerCatalog.totalStickerCount())
        assertEquals("Sticker IDs must be unique", allItems.size, allItems.map { it.id }.toSet().size)
        assertEquals("Sticker asset paths must be unique", allItems.size, allItems.map { it.assetPath }.toSet().size)

        val assetsRoot = findAssetsRoot()
        val seenHashes = HashSet<String>(allItems.size)

        for (pack in StickerCatalog.PACKS) {
            assertTrue(pack.items.size >= 20)
            assertTrue(pack.titleEn.isNotBlank())
            assertTrue(pack.titleAr.isNotBlank())
            assertTrue(pack.descEn.isNotBlank())
            assertTrue(pack.descAr.isNotBlank())
            assertTrue(pack.estimatedSizeBytes > 0L)
            assertTrue(File(assetsRoot, pack.iconAssetPath).isFile)

            for (item in pack.items) {
                val assetFile = File(assetsRoot, item.assetPath)
                assertTrue("Missing bundled sticker PNG: ${assetFile.path}", assetFile.isFile)
                val bytes = assetFile.readBytes()
                assertTrue("Sticker PNG must not be empty: ${assetFile.path}", bytes.isNotEmpty())

                val sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                assertTrue("Duplicate sticker artwork content hash detected at ${item.assetPath}", seenHashes.add(sha256))
            }
        }

        // Empty search returns full catalog; EN/AR queries and pack scoping filter accurately
        assertEquals(allItems.size, StickerCatalog.search("").size)
        assertTrue(StickerCatalog.search("coffee").any { it.id == "react_coffee" })
        assertTrue(StickerCatalog.search("شكراً").any { it.id == "daily_thanks" })
        assertTrue(StickerCatalog.search("", allowedPackIds = setOf("reactions")).all { it.packId == "reactions" })
        assertTrue(StickerCatalog.search("nonexistent_sticker_xyz").isEmpty())
    }

    @Test
    fun testStickerPackStorePolicyAndFavorites() {
        val encoded = MediaKeyboardSpec.StickerPackStorePolicy.encodeInstalledIds(
            listOf("reactions", "daily", "reactions", "  party_celebrate  ")
        )
        assertEquals("reactions,daily,party_celebrate", encoded)

        val decoded = MediaKeyboardSpec.StickerPackStorePolicy.decodeInstalledIds("reactions, unknown_pack, daily, notes")
        assertEquals(listOf("reactions", "daily", "notes"), decoded)

        assertEquals("0 KB", MediaKeyboardSpec.StickerPackStorePolicy.formatStorageSize(0))
        assertEquals("500 KB", MediaKeyboardSpec.StickerPackStorePolicy.formatStorageSize(500 * 1024L))
        assertEquals("1.5 MB", MediaKeyboardSpec.StickerPackStorePolicy.formatStorageSize((1.5 * 1024 * 1024).toLong()))

        val favs = MediaKeyboardSpec.StickerFavoritesHistory(maxEntries = 3)
        assertTrue(favs.toggle("react_love"))
        assertTrue(favs.isFavorite("react_love"))
        assertTrue(favs.toggle("daily_hi"))
        assertFalse(favs.toggle("react_love"))
        assertFalse(favs.isFavorite("react_love"))
        assertEquals(listOf("daily_hi"), favs.items().map { it.id })

        val restoredFavs = MediaKeyboardSpec.StickerFavoritesHistory(
            MediaKeyboardSpec.StickerFavoritesHistory.decode(favs.encode())
        )
        assertEquals(listOf("daily_hi"), restoredFavs.items().map { it.id })
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
    fun testRichContentEligibilityWebpFallbackAndCacheNaming() {
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(null, "image/png"))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(emptyArray(), "image/webp"))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("text/plain"), "image/png"))
        assertTrue(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("image/png"), "image/png"))
        assertTrue(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("image/*"), "image/webp"))
        assertTrue(MediaKeyboardSpec.MediaRichContentPolicy.supportsMimeType(arrayOf("*/*"), "image/png"))

        // Sticker WebP fallback for hosts that accept WebP over PNG
        assertEquals("image/png", MediaKeyboardSpec.MediaRichContentPolicy.resolveBestStickerMime(arrayOf("image/png", "image/webp")))
        assertEquals("image/webp", MediaKeyboardSpec.MediaRichContentPolicy.resolveBestStickerMime(arrayOf("image/webp")))
        assertNull(MediaKeyboardSpec.MediaRichContentPolicy.resolveBestStickerMime(arrayOf("text/plain")))

        // Field eligibility checks
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.isEligibleRichMediaEditor(InputType.TYPE_CLASS_TEXT, isPasswordTarget = true))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.isEligibleRichMediaEditor(InputType.TYPE_CLASS_NUMBER, isPasswordTarget = false))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.isEligibleRichMediaEditor(InputType.TYPE_CLASS_PHONE, isPasswordTarget = false))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.isEligibleRichMediaEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, isPasswordTarget = false))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.isEligibleRichMediaEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, isPasswordTarget = false))
        assertFalse(MediaKeyboardSpec.MediaRichContentPolicy.isEligibleRichMediaEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, isPasswordTarget = false))
        assertTrue(MediaKeyboardSpec.MediaRichContentPolicy.isEligibleRichMediaEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, isPasswordTarget = false))

        assertEquals(
            MediaKeyboardSpec.MediaRichContentPolicy.MediaFallbackOutcome.NO_ACTIVE_FIELD,
            MediaKeyboardSpec.MediaRichContentPolicy.evaluateMediaEligibility(
                hasActiveConnection = false,
                inputType = InputType.TYPE_CLASS_TEXT,
                isPasswordTarget = false,
                acceptedMimeTypes = arrayOf("image/png")
            )
        )
        assertEquals(
            MediaKeyboardSpec.MediaRichContentPolicy.MediaFallbackOutcome.UNSUPPORTED_FIELD,
            MediaKeyboardSpec.MediaRichContentPolicy.evaluateMediaEligibility(
                hasActiveConnection = true,
                inputType = InputType.TYPE_CLASS_NUMBER,
                isPasswordTarget = false,
                acceptedMimeTypes = arrayOf("image/png")
            )
        )
        assertEquals(
            MediaKeyboardSpec.MediaRichContentPolicy.MediaFallbackOutcome.UNSUPPORTED_MIME,
            MediaKeyboardSpec.MediaRichContentPolicy.evaluateMediaEligibility(
                hasActiveConnection = true,
                inputType = InputType.TYPE_CLASS_TEXT,
                isPasswordTarget = false,
                acceptedMimeTypes = null
            )
        )
        assertEquals(
            MediaKeyboardSpec.MediaRichContentPolicy.MediaFallbackOutcome.ELIGIBLE,
            MediaKeyboardSpec.MediaRichContentPolicy.evaluateMediaEligibility(
                hasActiveConnection = true,
                inputType = InputType.TYPE_CLASS_TEXT,
                isPasswordTarget = false,
                acceptedMimeTypes = arrayOf("image/webp")
            )
        )

        val sticker = StickerCatalog.PACKS.first().items.first()
        assertEquals("sticker_${sticker.id}.png", MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForSticker(sticker, "image/png"))
        assertEquals("sticker_${sticker.id}.webp", MediaKeyboardSpec.MediaRichContentPolicy.cacheFileNameForSticker(sticker, "image/webp"))
    }

    @Test
    fun testRecentHistoriesForStickersAndKaomoji() {
        val stickerHist = MediaKeyboardSpec.RecentStickerHistory(maxEntries = 3)
        stickerHist.record("react_great")
        stickerHist.record("daily_hi")
        stickerHist.record("party_celebrate_party_popper")
        stickerHist.record("react_love")
        stickerHist.record("daily_hi")
        assertEquals(listOf("daily_hi", "react_love", "party_celebrate_party_popper"), stickerHist.items().map { it.id })
        val restoredStickers = MediaKeyboardSpec.RecentStickerHistory(
            MediaKeyboardSpec.RecentStickerHistory.decode(stickerHist.encode())
        )
        assertEquals(listOf("daily_hi", "react_love", "party_celebrate_party_popper"), restoredStickers.items().map { it.id })

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
    }

    @Test
    fun testDeletedStickerStorePersistsSingleDeletionWithoutRemovingPackOrSiblings() {
        val recent = MediaKeyboardSpec.RecentStickerHistory(listOf("react_great", "daily_hi"))
        val favs = MediaKeyboardSpec.StickerFavoritesHistory(listOf("react_great", "react_love"))
        val deleted = MediaKeyboardSpec.DeletedStickerStore()

        assertFalse(deleted.markDeleted(""))
        assertFalse(deleted.markDeleted("nonexistent_sticker_xyz"))
        assertTrue(deleted.markDeleted("react_great"))
        assertFalse(deleted.markDeleted("react_great"))

        assertTrue(recent.remove("react_great"))
        assertTrue(favs.remove("react_great"))

        val restored = MediaKeyboardSpec.DeletedStickerStore(
            MediaKeyboardSpec.DeletedStickerStore.decode(deleted.encode())
        )
        assertTrue(restored.isDeleted("react_great"))
        assertFalse(restored.isDeleted("react_love"))
        assertFalse(restored.isDeleted("daily_hi"))

        val reactionsPack = StickerCatalog.findPack("reactions")!!
        val remainingReactions = reactionsPack.items.filterNot { restored.isDeleted(it.id) }
        assertEquals(reactionsPack.items.size - 1, remainingReactions.size)
        assertTrue(remainingReactions.any { it.id == "react_love" })
        assertEquals(listOf("daily_hi"), recent.items().map { it.id })
        assertEquals(listOf("react_love"), favs.items().map { it.id })
    }

    @Test
    fun testMediaStringsLocalizedInEnglishAndArabic() {
        for (locale in listOf("en", "ar")) {
            assertTrue(AppUiStrings.mediaEmojiTabA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaStickersTabA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaKaomojiTabA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.richMediaUnsupportedStatus(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaShareLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaSaveLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaSavedSuccessStatus(locale).isNotBlank())
            assertTrue(AppUiStrings.mediaSaveFailedStatus(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerFavoritesLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerFavoritesEmpty(locale).isNotBlank())
            assertFalse(AppUiStrings.stickerFavoritesEmpty(locale).contains("Long-press", ignoreCase = true))
            assertFalse(AppUiStrings.stickerFavoritesEmpty(locale).contains("لإضافته"))
            assertTrue(AppUiStrings.stickerAddedFavoriteStatus(locale, true).isNotBlank())
            assertTrue(AppUiStrings.stickerDeletedStatus(locale).isNotBlank())
            assertTrue(AppUiStrings.deleteStickerA11y(locale, "Great").isNotBlank())
            assertTrue(AppUiStrings.dismissStickerDeleteOverlayA11y(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerTileA11y(locale, "Great").isNotBlank())
            assertTrue(AppUiStrings.actionDelete(locale).isNotBlank())
            assertTrue(AppUiStrings.cancelLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerPackStoreTitle(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerPackInstallLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerPackRemoveLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerPackInstalledLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerPackInstallAllLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.stickerPacksNoneInstalled(locale).isNotBlank())
            assertTrue(AppUiStrings.retryLabel(locale).isNotBlank())
            assertTrue(AppUiStrings.emptyStickersMessage(locale).isNotBlank())
            assertTrue(AppUiStrings.searchStickersPlaceholder(locale).isNotBlank())
            assertTrue(AppUiStrings.localizeStickerPackError(locale, "Install failed").isNotBlank())
        }

        assertEquals("حذف", AppUiStrings.actionDelete("ar"))
        assertEquals("Delete", AppUiStrings.actionDelete("en"))
        assertEquals("500 ك.ب", AppUiStrings.formatStorageSize("ar", 500 * 1024L))
        assertEquals("1.5 م.ب", AppUiStrings.formatStorageSize("ar", (1.5 * 1024 * 1024).toLong()))
        assertEquals("500 KB", AppUiStrings.formatStorageSize("en", 500 * 1024L))
        assertEquals("فشل التثبيت.", AppUiStrings.localizeStickerPackError("ar", "Install failed"))

        val great = StickerCatalog.findById("react_great")!!
        assertEquals("Great", StickerCatalog.localizedTitle(great, "en"))
        assertTrue(StickerCatalog.localizedTitle(great, "ar").any { it in '\u0600'..'\u06FF' })
    }
}
