package com.clipmax.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {

    @Test
    fun testAllEmojiCategoriesAreNonEmptyAndDistinct() {
        assertEquals(EmojiCatalog.CategoryId.values().size, EmojiCatalog.CATEGORIES.size)
        val seenIds = HashSet<EmojiCatalog.CategoryId>()
        for (cat in EmojiCatalog.CATEGORIES) {
            assertTrue("Duplicate category ${cat.id}", seenIds.add(cat.id))
            assertTrue("Category ${cat.id} label must not be blank", cat.label.isNotBlank())
            assertTrue("Category ${cat.id} must have emojis", cat.emojis.size >= 24)
            assertTrue(cat.emojis.all { it.isNotBlank() })
        }
    }
}
