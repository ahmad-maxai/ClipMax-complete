package com.clipmax.app

/**
 * Complete English / Arabic application UI localization table and RTL helpers.
 *
 * Strictly scoped to application-owned chrome and static interface labels
 * (`appUiLocale`). Saved clipboard payloads and active `keyboardLanguage` remain
 * completely isolated and untouched. Pure JVM with zero Android framework imports.
 */
internal object AppUiStrings {

    fun isArabic(locale: String?): Boolean =
        SettingsPanelSpec.normalizeAppUiLocale(locale) == SettingsPanelSpec.UI_LOCALE_ARABIC

    fun isRtl(locale: String?): Boolean = isArabic(locale)

    fun toggleLocale(locale: String?): String =
        if (isArabic(locale)) SettingsPanelSpec.UI_LOCALE_ENGLISH else SettingsPanelSpec.UI_LOCALE_ARABIC

    fun translatorButtonLabel(locale: String?): String =
        if (isArabic(locale)) "ENG" else "عرب"

    fun translatorButtonA11y(locale: String?): String =
        if (isArabic(locale)) {
            "تبديل لغة واجهة التطبيق إلى الإنجليزية"
        } else {
            "Switch app interface language to Arabic"
        }

    // -------------------------------------------------------------------------
    // Toolbar & Navigation
    // -------------------------------------------------------------------------
    fun clipboardHistoryA11y(locale: String?): String =
        if (isArabic(locale)) "سجل الحافظة" else "Clipboard history"

    fun moreActionsA11y(locale: String?): String =
        if (isArabic(locale)) "المزيد من الإجراءات" else "More actions"

    fun backToSnippetsA11y(locale: String?): String =
        if (isArabic(locale)) "العودة إلى المقتطفات" else "Back to clipboard snippets"

    fun backToKeyboardA11y(locale: String?): String =
        if (isArabic(locale)) "العودة إلى لوحة المفاتيح" else "Back to keyboard"

    fun pinnedA11y(locale: String?): String =
        if (isArabic(locale)) "العناصر المثبتة" else "Pinned"

    fun searchClipsA11y(locale: String?): String =
        if (isArabic(locale)) "البحث في المقاطع" else "Search Clips"

    fun settingsA11y(locale: String?): String =
        if (isArabic(locale)) "الإعدادات" else "Settings"

    fun switchKeyboardA11y(locale: String?): String =
        if (isArabic(locale)) "تبديل لوحة المفاتيح" else "Switch keyboard"

    // -------------------------------------------------------------------------
    // Clipboard Panel & Cards
    // -------------------------------------------------------------------------
    fun clipboardTitle(locale: String?): String =
        if (isArabic(locale)) "الحافظة" else "Clipboard"

    fun pinnedClipsTitle(locale: String?): String =
        if (isArabic(locale)) "العناصر المثبتة" else "Pinned Clips"

    fun matchesTitle(locale: String?): String =
        if (isArabic(locale)) "نتائج البحث" else "Matches"

    fun captureToggleOn(locale: String?): String =
        if (isArabic(locale)) "تشغيل" else "ON"

    fun captureToggleOff(locale: String?): String =
        if (isArabic(locale)) "إيقاف" else "OFF"

    fun captureToggleA11y(locale: String?, enabled: Boolean): String = if (isArabic(locale)) {
        if (enabled) "التقاط الحافظة مفعّل. اضغط للإيقاف."
        else "التقاط الحافظة متوقف. اضغط للتفعيل."
    } else {
        if (enabled) "Clipboard capture on. Tap to turn off."
        else "Clipboard capture off. Tap to turn on."
    }

    fun passwordClipsWithheldShort(locale: String?): String =
        if (isArabic(locale)) "حقل كلمة مرور: تم إخفاء المقاطع المحفوظة للخصوصية"
        else "Password field: Saved clips withheld for privacy"

    fun passwordClipsWithheldActive(locale: String?): String =
        if (isArabic(locale)) "حقل كلمة مرور نشط: تم إخفاء المقاطع المحفوظة للخصوصية"
        else "Password field active: Saved clips withheld for privacy"

    fun passwordClipsWithheldDot(locale: String?): String =
        if (isArabic(locale)) "حقل كلمة مرور: تم إخفاء المقاطع المحفوظة للخصوصية."
        else "Password field: Saved clips withheld for privacy."

    fun passwordClipsUnavailable(locale: String?): String =
        if (isArabic(locale)) "العناصر المحفوظة غير متاحة في حقول كلمات المرور."
        else "Saved items are unavailable in password fields."

    fun noMatchingClips(locale: String?, query: String): String =
        if (isArabic(locale)) "لا توجد مقاطع تطابق \"$query\""
        else "No clips match \"$query\""

    fun noPinnedClipsEmpty(locale: String?): String =
        if (isArabic(locale)) "لا توجد عناصر مثبتة بعد. ثبّت أي مقطع لإبقائه في متناول يدك."
        else "No pinned clips yet. Pin a clip to keep it handy."

    fun noPinnedClipsCompact(locale: String?): String =
        if (isArabic(locale)) "لا توجد عناصر مثبتة" else "No pinned clips"

    fun noSavedClipsEmpty(locale: String?): String =
        if (isArabic(locale)) "لا توجد مقاطع محفوظة بعد. انسخ أي نص لإنشاء بطاقات."
        else "No saved clips yet. Copy text to capture cards."

    fun noSavedClipsCompact(locale: String?): String =
        if (isArabic(locale)) "لا توجد مقاطع محفوظة. انسخ نصاً لإنشاء بطاقات."
        else "No saved clips. Copy text to create cards."

    fun protectedFromExpiryA11y(locale: String?): String =
        if (isArabic(locale)) "محمي من انتهاء الصلاحية" else "Protected from expiry"

    fun genericSavedClipTitle(locale: String?): String =
        if (isArabic(locale)) "مقطع محفوظ" else "Saved Clip"

    fun blankSpacesFallback(locale: String?): String =
        if (isArabic(locale)) "(مسافات فارغة)" else "(blank spaces)"

    fun emptyClipFallback(locale: String?): String =
        if (isArabic(locale)) "(مقطع فارغ)" else "(empty clip)"

    fun clipCardA11y(locale: String?, serialLabel: String, previewContent: String): String =
        if (isArabic(locale)) {
            "المقطع $serialLabel. $previewContent. اضغط للإدراج. اضغط مطولاً للخيارات."
        } else {
            "Clip $serialLabel. $previewContent. Tap to insert. Long press for options."
        }

    // -------------------------------------------------------------------------
    // ClipCard Context Menu (6 Actions)
    // -------------------------------------------------------------------------
    fun dismissClipOptionsA11y(locale: String?): String =
        if (isArabic(locale)) "إغلاق خيارات المقطع" else "Dismiss clip options"

    fun actionPin(locale: String?, pinned: Boolean): String = if (isArabic(locale)) {
        if (pinned) "إلغاء التثبيت" else "تثبيت"
    } else {
        if (pinned) "Unpin" else "Pin"
    }

    fun actionLockExpiry(locale: String?, locked: Boolean): String = if (isArabic(locale)) {
        if (locked) "إلغاء قفل الانتهاء" else "قفل الانتهاء"
    } else {
        if (locked) "Unlock expiry" else "Lock expiry"
    }

    fun actionEdit(locale: String?): String = if (isArabic(locale)) "تعديل" else "Edit"
    fun actionDelete(locale: String?): String = if (isArabic(locale)) "حذف" else "Delete"
    fun actionMerge(locale: String?): String = if (isArabic(locale)) "دمج" else "Merge"
    fun actionSplit(locale: String?): String = if (isArabic(locale)) "تقسيم" else "Split"

    fun expiryLockedStatus(locale: String?, locked: Boolean): String = if (isArabic(locale)) {
        if (locked) "تم قفل حماية انتهاء الصلاحية." else "تم فتح حماية انتهاء الصلاحية."
    } else {
        if (locked) "Expiry protection locked." else "Expiry protection unlocked."
    }

    // -------------------------------------------------------------------------
    // Settings Panel (Separate App Language vs Keyboard Language)
    // -------------------------------------------------------------------------
    fun settingsHeaderTitle(locale: String?): String =
        if (isArabic(locale)) "إعدادات ClipMax" else "ClipMax Settings"

    fun closeButton(locale: String?): String =
        if (isArabic(locale)) "إغلاق" else "Close"

    fun appLanguageSettingTitle(locale: String?): String =
        if (isArabic(locale)) "لغة التطبيق" else "App Language"

    fun appUiLanguageOptions(locale: String?): List<SettingsPanelSpec.ChoiceOption<String>> =
        if (isArabic(locale)) {
            listOf(
                SettingsPanelSpec.ChoiceOption(SettingsPanelSpec.UI_LOCALE_ENGLISH, "English"),
                SettingsPanelSpec.ChoiceOption(SettingsPanelSpec.UI_LOCALE_ARABIC, "العربية")
            )
        } else {
            listOf(
                SettingsPanelSpec.ChoiceOption(SettingsPanelSpec.UI_LOCALE_ENGLISH, "English"),
                SettingsPanelSpec.ChoiceOption(SettingsPanelSpec.UI_LOCALE_ARABIC, "العربية (Arabic)")
            )
        }

    fun keyboardLanguageSettingTitle(locale: String?): String =
        if (isArabic(locale)) "لغة لوحة المفاتيح" else "Keyboard Language"

    fun themeSettingTitle(locale: String?): String =
        if (isArabic(locale)) "المظهر" else "Theme"

    fun themeOptions(locale: String?): List<SettingsPanelSpec.ChoiceOption<String>> =
        if (isArabic(locale)) {
            listOf(
                SettingsPanelSpec.ChoiceOption("system", "النظام"),
                SettingsPanelSpec.ChoiceOption("light", "فاتح"),
                SettingsPanelSpec.ChoiceOption("dark", "داكن"),
                SettingsPanelSpec.ChoiceOption("classic", "كلاسيكي")
            )
        } else {
            SettingsPanelSpec.THEME_OPTIONS
        }

    fun keyboardHeightSettingTitle(locale: String?): String =
        if (isArabic(locale)) "ارتفاع لوحة المفاتيح" else "Keyboard Height"

    fun heightOptions(locale: String?): List<SettingsPanelSpec.ChoiceOption<String>> =
        if (isArabic(locale)) {
            listOf(
                SettingsPanelSpec.ChoiceOption("compact", "مضغوط"),
                SettingsPanelSpec.ChoiceOption("normal", "عادي"),
                SettingsPanelSpec.ChoiceOption("tall", "طويل")
            )
        } else {
            SettingsPanelSpec.HEIGHT_OPTIONS
        }

    fun oneHandedSettingTitle(locale: String?): String =
        if (isArabic(locale)) "وضع اليد الواحدة" else "One-Handed Mode"

    fun oneHandedOptions(locale: String?): List<SettingsPanelSpec.ChoiceOption<String>> =
        if (isArabic(locale)) {
            listOf(
                SettingsPanelSpec.ChoiceOption("left", "يسار"),
                SettingsPanelSpec.ChoiceOption("off", "إيقاف"),
                SettingsPanelSpec.ChoiceOption("right", "يمين")
            )
        } else {
            SettingsPanelSpec.ONE_HANDED_OPTIONS
        }

    fun typingAssistanceTitle(locale: String?): String =
        if (isArabic(locale)) "مساعدة الكتابة" else "Typing Assistance"

    fun typingSettingTitle(locale: String?, prefKey: String, defaultEnglishTitle: String): String =
        if (isArabic(locale)) {
            when (prefKey) {
                SettingsPanelSpec.PREF_AUTOCOMPLETE -> "الإكمال التلقائي"
                SettingsPanelSpec.PREF_AUTOCORRECT -> "التصحيح التلقائي"
                SettingsPanelSpec.PREF_SUGGESTIONS -> "الاقتراحات"
                else -> defaultEnglishTitle
            }
        } else {
            defaultEnglishTitle
        }

    fun binaryOnOffOptions(locale: String?): List<SettingsPanelSpec.ChoiceOption<Boolean>> =
        if (isArabic(locale)) {
            listOf(
                SettingsPanelSpec.ChoiceOption(true, "تشغيل"),
                SettingsPanelSpec.ChoiceOption(false, "إيقاف")
            )
        } else {
            SettingsPanelSpec.BINARY_ON_OFF_OPTIONS
        }

    fun shareClipMaxLabel(locale: String?): String =
        if (isArabic(locale)) "مشاركة ClipMax" else "Share ClipMax"

    fun shareDialogOpenedStatus(locale: String?): String =
        if (isArabic(locale)) "تم فتح نافذة المشاركة." else "Share dialog opened."

    fun shareClipPromptTitle(locale: String?): String =
        if (isArabic(locale)) "مشاركة المقطع" else "Share clip"

    fun sharePromoMessage(locale: String?): String =
        if (isArabic(locale)) {
            "جرّب ClipMax — مدير الحافظة ولوحة المفاتيح لنظام أندرويد لإدراج النصوص الطويلة بسلاسة!"
        } else {
            "Check out ClipMax - Android clipboard manager with large-text keyboard insertion!"
        }

    // -------------------------------------------------------------------------
    // Search Panel
    // -------------------------------------------------------------------------
    fun searchPlaceholder(locale: String?): String =
        if (isArabic(locale)) "اكتب أدناه للبحث في المقاطع…" else "Type below to search clips…"

    fun clearSearchQueryA11y(locale: String?): String =
        if (isArabic(locale)) "مسح نص البحث" else "Clear search query"

    fun doneButton(locale: String?): String =
        if (isArabic(locale)) "تم" else "Done"

    fun searchModeFilterLabel(locale: String?): String =
        if (isArabic(locale)) "تصفية" else "Filter"

    fun searchModeHostLabel(locale: String?): String =
        if (isArabic(locale)) "المضيف" else "Host"

    fun passwordSearchDisabledShort(locale: String?): String =
        if (isArabic(locale)) "حقل كلمة مرور: البحث معطّل للخصوصية"
        else "Password field: Search disabled for privacy"

    fun passwordSearchDisabledDot(locale: String?): String =
        if (isArabic(locale)) "حقل كلمة مرور: البحث معطّل للخصوصية."
        else "Password field: Search disabled for privacy."

    fun noSavedClipsToSearch(locale: String?): String =
        if (isArabic(locale)) "لا توجد مقاطع محفوظة للبحث"
        else "No saved clips to search"

    // -------------------------------------------------------------------------
    // Emoji Picker
    // -------------------------------------------------------------------------
    fun emojiRecentLabel(locale: String?): String =
        if (isArabic(locale)) "الأخيرة" else "Recent"

    fun emojiCategoryLabel(locale: String?, category: EmojiCatalog.Category): String =
        if (isArabic(locale)) {
            when (category.id) {
                EmojiCatalog.CategoryId.SMILEYS -> "الوجوه التعبيرية"
                EmojiCatalog.CategoryId.PEOPLE -> "الأشخاص"
                EmojiCatalog.CategoryId.NATURE -> "الطبيعة"
                EmojiCatalog.CategoryId.FOOD -> "الطعام"
                EmojiCatalog.CategoryId.ACTIVITIES -> "الأنشطة"
                EmojiCatalog.CategoryId.TRAVEL -> "السفر"
                EmojiCatalog.CategoryId.OBJECTS -> "الأشياء"
                EmojiCatalog.CategoryId.SYMBOLS -> "الرموز"
                EmojiCatalog.CategoryId.FLAGS -> "الأعلام"
            }
        } else {
            category.label
        }

    fun emojiEmptyRecents(locale: String?): String =
        if (isArabic(locale)) "لا توجد رموز تعبيرية حديثة بعد. اضغط على أي رمز لحفظه هنا."
        else "No recent emojis yet. Tap any emoji to keep it here."

    fun removeFromRecents(locale: String?): String =
        if (isArabic(locale)) "إزالة من الأخيرة" else "Remove from recents"

    fun cancelLabel(locale: String?): String =
        if (isArabic(locale)) "إلغاء" else "Cancel"

    fun returnToKeyboardA11y(locale: String?): String =
        if (isArabic(locale)) "العودة إلى لوحة المفاتيح" else "Return to keyboard"

    fun deleteKeyA11y(locale: String?): String =
        if (isArabic(locale)) "حذف" else "Delete"

    fun mediaEmojiTabA11y(locale: String?): String =
        if (isArabic(locale)) "الرموز التعبيرية" else "Emoji"

    fun mediaStickersTabA11y(locale: String?): String =
        if (isArabic(locale)) "ملصقات" else "Stickers"

    fun mediaKaomojiTabA11y(locale: String?): String =
        if (isArabic(locale)) "كاوماوجي" else "Kaomoji"

    fun richMediaUnsupportedStatus(locale: String?): String =
        if (isArabic(locale)) "هذا الحقل لا يدعم إدراج الصور مباشرة — يمكنك المشاركة أو الحفظ."
        else "This field doesn't allow direct image insert — share or save instead."

    fun mediaShareLabel(locale: String?): String =
        if (isArabic(locale)) "مشاركة" else "Share"

    fun mediaSaveLabel(locale: String?): String =
        if (isArabic(locale)) "حفظ" else "Save"

    fun mediaSavedSuccessStatus(locale: String?): String =
        if (isArabic(locale)) "تم الحفظ في المعرض (Pictures/ClipMax)."
        else "Saved to Pictures/ClipMax."

    fun mediaSaveFailedStatus(locale: String?): String =
        if (isArabic(locale)) "تعذر حفظ الملف." else "Could not save media."

    fun stickerFavoritesLabel(locale: String?): String =
        if (isArabic(locale)) "المفضلة" else "Favourites"

    fun stickerFavoritesEmpty(locale: String?): String =
        if (isArabic(locale)) "لا توجد ملصقات مفضلة بعد."
        else "No favourite stickers yet."

    fun stickerAddedFavoriteStatus(locale: String?, added: Boolean): String = if (isArabic(locale)) {
        if (added) "تمت إضافة الملصق إلى المفضلة ★" else "تمت إزالة الملصق من المفضلة"
    } else {
        if (added) "Added sticker to Favourites ★" else "Removed sticker from Favourites"
    }

    fun stickerDeletedStatus(locale: String?): String =
        if (isArabic(locale)) "تم حذف الملصق." else "Sticker deleted."

    fun deleteStickerA11y(locale: String?, label: String): String =
        if (isArabic(locale)) "حذف ملصق $label" else "Delete $label sticker"

    fun dismissStickerDeleteOverlayA11y(locale: String?): String =
        if (isArabic(locale)) "إغلاق خيارات حذف الملصق" else "Dismiss sticker delete options"

    fun stickerTileA11y(locale: String?, label: String): String =
        if (isArabic(locale)) {
            "ملصق $label. اضغط للإرسال. اضغط مطولاً للحذف."
        } else {
            "$label sticker. Tap to send. Long press to delete."
        }

    fun formatStorageSize(locale: String?, bytes: Long): String {
        val base = MediaKeyboardSpec.StickerPackStorePolicy.formatStorageSize(bytes)
        if (!isArabic(locale)) return base
        return base
            .replace(" KB", " ك.ب")
            .replace(" MB", " م.ب")
    }

    fun localizeStickerPackError(locale: String?, rawError: String?): String {
        val msg = rawError?.trim().orEmpty()
        if (!isArabic(locale)) {
            return msg.ifEmpty { "Failed to save pack locally." }
        }
        return when (msg) {
            "", "Failed to save pack locally." -> "تعذر حفظ الحزمة محلياً."
            "Unknown sticker pack." -> "حزمة ملصقات غير معروفة."
            "Install failed" -> "فشل التثبيت."
            "Cannot create pack directory" -> "تعذر إنشاء مجلد الحزم."
            else -> msg
        }
    }

    fun stickerPackStoreTitle(locale: String?): String =
        if (isArabic(locale)) "حزم الملصقات المحلية" else "Sticker Packs"

    fun stickerPackInstallLabel(locale: String?): String =
        if (isArabic(locale)) "تثبيت" else "Install"

    fun stickerPackRemoveLabel(locale: String?): String =
        if (isArabic(locale)) "إزالة" else "Remove"

    fun stickerPackInstalledLabel(locale: String?): String =
        if (isArabic(locale)) "مثبتة محلياً" else "Installed"

    fun stickerPackInstallAllLabel(locale: String?): String =
        if (isArabic(locale)) "تثبيت الكل" else "Install All"

    fun stickerPacksNoneInstalled(locale: String?): String =
        if (isArabic(locale)) "لا توجد حزم مثبتة حالياً. افتح إدارة الحزم (+) لتثبيتها محلياً."
        else "No packs installed locally yet. Tap + to install packs."

    fun searchStickersPlaceholder(locale: String?): String =
        if (isArabic(locale)) "بحث في الملصقات..." else "Search stickers..."

    fun searchEmojisPlaceholder(locale: String?): String =
        if (isArabic(locale)) "بحث في الرموز التعبيرية..." else "Search emojis..."

    fun searchKaomojiPlaceholder(locale: String?): String =
        if (isArabic(locale)) "بحث في كاوماوجي..." else "Search kaomoji..."

    fun emptyStickersMessage(locale: String?): String =
        if (isArabic(locale)) "لا توجد ملصقات مطابقة" else "No stickers found"

    fun retryLabel(locale: String?): String =
        if (isArabic(locale)) "إعادة المحاولة" else "Retry"

    // -------------------------------------------------------------------------
    // Paste Undo / Redo Capsule & Floating Undo/Redo
    // -------------------------------------------------------------------------
    fun undoLabel(locale: String?): String = if (isArabic(locale)) "تراجع" else "Undo"
    fun redoLabel(locale: String?): String = if (isArabic(locale)) "إعادة" else "Redo"

    fun undoSuggestionChipLabel(locale: String?, originalWord: String): String =
        if (isArabic(locale)) "تراجع عن \"$originalWord\"" else "Undo \"$originalWord\""

    fun pasteCapsuleLabel(locale: String?, action: PasteUndoCapsuleSpec.CapsuleAction?): String =
        when (action) {
            PasteUndoCapsuleSpec.CapsuleAction.UNDO -> undoLabel(locale)
            PasteUndoCapsuleSpec.CapsuleAction.REDO -> redoLabel(locale)
            null -> ""
        }

    fun pasteCapsuleA11y(locale: String?, action: PasteUndoCapsuleSpec.CapsuleAction?): String =
        if (action == PasteUndoCapsuleSpec.CapsuleAction.REDO) {
            if (isArabic(locale)) "إعادة المقطع الملصوق" else "Redo pasted clip"
        } else {
            if (isArabic(locale)) "تراجع عن لصق المقطع" else "Undo pasted clip"
        }

    // -------------------------------------------------------------------------
    // Merge / Split / History / Insertion / Auto-Capture Status Messages
    // -------------------------------------------------------------------------
    fun mergeModeBannerText(locale: String?): String =
        if (isArabic(locale)) "وضع الدمج: اسحب البطاقة المصدر إلى البطاقة الهدف أو اضغط على بطاقة أخرى للدمج."
        else "Merge Mode: Drag source card onto target card or tap another card to merge."

    fun mergeModeStatusWithTitle(locale: String?, title: String): String =
        if (isArabic(locale)) "وضع الدمج: اسحب أو اضغط على بطاقة أخرى للدمج مع \"$title\""
        else "Merge Mode: Drag or tap another card to merge with \"$title\""

    fun mergeCancelledStatus(locale: String?): String =
        if (isArabic(locale)) "تم إلغاء الدمج." else "Merge cancelled."

    fun mergeSelfErrorStatus(locale: String?): String =
        if (isArabic(locale)) "لا يمكن دمج البطاقة مع نفسها." else "Cannot merge a card with itself."

    fun operationBusyStatus(locale: String?): String =
        if (isArabic(locale)) "يرجى الانتظار حتى تكتمل العملية السابقة." else "Please wait for the previous operation to finish."

    fun mergedSuccessStatus(locale: String?): String =
        if (isArabic(locale)) "تم دمج مقطعين في بطاقة واحدة." else "Merged 2 clips into 1."

    fun mergeFailedStatus(locale: String?, error: String?): String =
        if (isArabic(locale)) "فشل الدمج: ${error.orEmpty()}" else "Merge failed: ${error.orEmpty()}"

    fun splitTooShortStatus(locale: String?): String =
        if (isArabic(locale)) "نص المقطع قصير جداً للتقسيم." else "Clip text is too short to split."

    fun splitSuccessStatus(locale: String?): String =
        if (isArabic(locale)) "تم تقسيم المقطع إلى بطاقتين." else "Split clip into 2 cards."

    fun splitFailedStatus(locale: String?, error: String?): String =
        if (isArabic(locale)) "فشل التقسيم: ${error.orEmpty()}" else "Split failed: ${error.orEmpty()}"

    fun historySnapshotName(locale: String?, rawDescription: String): String =
        if (isArabic(locale)) {
            when (rawDescription) {
                "Merge" -> "الدمج"
                "Split" -> "التقسيم"
                else -> rawDescription
            }
        } else {
            rawDescription
        }

    fun undidOperationStatus(locale: String?, rawDescription: String): String =
        if (isArabic(locale)) "تم التراجع عن ${historySnapshotName(locale, rawDescription)}."
        else "Undid $rawDescription."

    fun redidOperationStatus(locale: String?, rawDescription: String): String =
        if (isArabic(locale)) "تمت إعادة ${historySnapshotName(locale, rawDescription)}."
        else "Redid $rawDescription."

    fun undoFailedStatus(locale: String?, error: String?): String =
        if (isArabic(locale)) "فشل التراجع: ${error.orEmpty()}" else "Undo failed: ${error.orEmpty()}"

    fun redoFailedStatus(locale: String?, error: String?): String =
        if (isArabic(locale)) "فشلت الإعادة: ${error.orEmpty()}" else "Redo failed: ${error.orEmpty()}"

    fun undoWaitingForFieldStatus(locale: String?): String =
        if (isArabic(locale)) "التراجع بانتظار حقل إدخال نشط." else "Undo waiting for active input field."

    fun redoWaitingForFieldStatus(locale: String?): String =
        if (isArabic(locale)) "الإعادة بانتظار حقل إدخال نشط." else "Redo waiting for active input field."

    fun confirmingUndoStatus(locale: String?): String =
        if (isArabic(locale)) "جارٍ تأكيد التراجع…" else "Confirming undo…"

    fun confirmingRedoStatus(locale: String?): String =
        if (isArabic(locale)) "جارٍ تأكيد الإعادة…" else "Confirming redo…"

    fun undoRejectedStatus(locale: String?, canPreserveRetry: Boolean): String = if (isArabic(locale)) {
        if (canPreserveRetry) "رفض المحرر الهدف التراجع؛ اضغط تراجع للمحاولة مجدداً."
        else "رفض المحرر الهدف التراجع."
    } else {
        if (canPreserveRetry) "Destination editor rejected undo; tap Undo to retry."
        else "Destination editor rejected undo."
    }

    fun redoRejectedStatus(locale: String?, canPreserveRetry: Boolean): String = if (isArabic(locale)) {
        if (canPreserveRetry) "رفض المحرر الهدف الإعادة؛ اضغط إعادة للمحاولة مجدداً."
        else "رفض المحرر الهدف الإعادة."
    } else {
        if (canPreserveRetry) "Destination editor rejected redo; tap Redo to retry."
        else "Destination editor rejected redo."
    }

    fun localizeHistoryMessage(locale: String?, rawEnglishMessage: String): String {
        if (!isArabic(locale)) return rawEnglishMessage
        return when (rawEnglishMessage) {
            "Nothing to undo." -> "لا يوجد شيء للتراجع عنه."
            "Undo unavailable in password fields." -> "التراجع غير متاح في حقول كلمات المرور."
            "Switch back to the original editor field to undo." -> "ارجع إلى حقل المحرر الأصلي للتراجع."
            "Undo unavailable: surrounding text changed." -> "التراجع غير متاح: تغيّر النص المحيط."
            "Destination editor rejected undo." -> "رفض المحرر الهدف التراجع."
            "Undid autocorrect." -> "تم التراجع عن التصحيح التلقائي."
            "Undid paste." -> "تم التراجع عن اللصق."
            "Nothing to redo." -> "لا يوجد شيء لإعادته."
            "Redo unavailable in password fields." -> "الإعادة غير متاحة في حقول كلمات المرور."
            "Switch back to the original editor field to redo." -> "ارجع إلى حقل المحرر الأصلي للإعادة."
            "Redo unavailable: cursor position changed." -> "الإعادة غير متاحة: تغيّر موضع المؤشر."
            "Destination editor rejected redo." -> "رفض المحرر الهدف الإعادة."
            "Redid autocorrect." -> "تمت إعادة التصحيح التلقائي."
            "Redid paste." -> "تمت إعادة اللصق."
            else -> rawEnglishMessage
        }
    }

    fun noActiveTextFieldStatus(locale: String?): String =
        if (isArabic(locale)) "لا يوجد حقل نصي نشط في التطبيق الهدف."
        else "No active text field in destination app."

    fun insertingClipStatus(locale: String?, title: String): String =
        if (isArabic(locale)) "جارٍ إدراج $title…" else "Inserting $title…"

    fun insertingClipWithCountStatus(locale: String?, title: String, formattedCount: String): String =
        if (isArabic(locale)) "جارٍ إدراج $title ($formattedCount حرف)…"
        else "Inserting $title ($formattedCount chars)…"

    fun insertedProgressStatus(locale: String?, insertedFormatted: String, totalFormatted: String): String =
        if (isArabic(locale)) "تم إدراج $insertedFormatted / $totalFormatted حرف…"
        else "Inserted $insertedFormatted / $totalFormatted chars…"

    fun insertionCompletedOrPausedStatus(locale: String?, complete: Boolean, insertedFormatted: String): String =
        if (isArabic(locale)) {
            if (complete) "تم إدراج $insertedFormatted حرف." else "توقف الإدراج مؤقتاً."
        } else {
            if (complete) "Inserted $insertedFormatted chars." else "Insertion paused."
        }

    fun insertionEndedStatus(locale: String?, reason: String?): String {
        val fallbackReason = if (isArabic(locale)) "تم إغلاق الوجهة" else "destination closed"
        val effectiveReason = reason?.takeIf { it.isNotBlank() } ?: fallbackReason
        return if (isArabic(locale)) "انتهى الإدراج: $effectiveReason"
        else "Insertion ended: $effectiveReason"
    }

    fun insertionCancelledStatus(locale: String?, userInitiated: Boolean): String =
        if (isArabic(locale)) {
            if (userInitiated) "تم إلغاء الإدراج." else "توقف الإدراج."
        } else {
            if (userInitiated) "Insertion cancelled." else "Insertion stopped."
        }

    fun localizedRetentionModeLabel(locale: String?, mode: String): String {
        if (!isArabic(locale)) return CapturePreferences.modeLabel(mode)
        val trimmed = mode.trim().lowercase()
        return when (trimmed) {
            "off" -> "إيقاف"
            "1h" -> "ساعة واحدة"
            "6h" -> "٦ ساعات"
            "24h" -> "٢٤ ساعة"
            "72h" -> "٧٢ ساعة"
            "forever" -> "للأبد"
            else -> CapturePreferences.modeLabel(mode)
        }
    }

    fun capturedCardsStatus(locale: String?, count: Int, mode: String): String {
        val modeLbl = localizedRetentionModeLabel(locale, mode)
        return if (isArabic(locale)) {
            val noun = if (count == 1) "بطاقة واحدة" else "$count بطاقات"
            "تم التقاط $noun • $modeLbl"
        } else {
            "Captured $count ${if (count == 1) "card" else "cards"} • $modeLbl"
        }
    }

    fun autoCaptureStatus(locale: String?, enabled: Boolean, mode: String): String =
        if (isArabic(locale)) {
            if (enabled) "الالتقاط التلقائي مفعّل • ${localizedRetentionModeLabel(locale, mode)}"
            else "الالتقاط التلقائي متوقف"
        } else {
            if (enabled) "Auto-capture active • ${CapturePreferences.modeLabel(mode)}"
            else "Auto-capture off"
        }

    // -------------------------------------------------------------------------
    // Keyboard Key & Popup Accessibility Labels
    // -------------------------------------------------------------------------
    fun switchToNumpadA11y(locale: String?): String =
        if (isArabic(locale)) "التبديل إلى لوحة الأرقام" else "Switch to numeric keypad"

    fun spaceKeyA11y(locale: String?): String =
        if (isArabic(locale)) "مسافة" else "Space"

    fun spaceKeyFullA11y(locale: String?, spacebarLabel: String): String =
        if (isArabic(locale)) "مسافة، $spacebarLabel، اضغط مطولاً لتبديل اللغة"
        else "Space, $spacebarLabel, hold to switch language"

    fun commaEmojiA11y(locale: String?): String =
        if (isArabic(locale)) "فاصلة، اضغط مطولاً للرموز التعبيرية" else "Comma, hold for emoji"

    fun arabicPeriodA11y(locale: String?): String =
        if (isArabic(locale)) "نقطة، اضغط مطولاً للتشكيل وعلامات الترقيم"
        else "Period, hold for diacritics and punctuation"

    fun switchInputMethodA11y(locale: String?): String =
        if (isArabic(locale)) "تبديل أسلوب الإدخال" else "Switch input method"

    fun actionKeyA11y(locale: String?, kind: InputFieldPolicy.ActionKeyKind): String =
        if (isArabic(locale)) {
            when (kind) {
                InputFieldPolicy.ActionKeyKind.SEARCH -> "بحث"
                InputFieldPolicy.ActionKeyKind.SEND -> "إرسال"
                InputFieldPolicy.ActionKeyKind.GO -> "انتقال"
                InputFieldPolicy.ActionKeyKind.NEXT -> "التالي"
                InputFieldPolicy.ActionKeyKind.DONE -> "تم"
                InputFieldPolicy.ActionKeyKind.ENTER -> "إدخال"
            }
        } else {
            when (kind) {
                InputFieldPolicy.ActionKeyKind.SEARCH -> "Search"
                InputFieldPolicy.ActionKeyKind.SEND -> "Send"
                InputFieldPolicy.ActionKeyKind.GO -> "Go"
                InputFieldPolicy.ActionKeyKind.NEXT -> "Next"
                InputFieldPolicy.ActionKeyKind.DONE -> "Done"
                InputFieldPolicy.ActionKeyKind.ENTER -> "Enter"
            }
        }

    fun stickerTabLabel(locale: String?): String =
        if (isArabic(locale)) "الملصقات" else "Stickers"

    fun kaomojiTabLabel(locale: String?): String =
        if (isArabic(locale)) "الرموز التعبيرية النصية" else "Kaomoji"

    fun searchMediaPlaceholder(locale: String?): String =
        if (isArabic(locale)) "بحث…" else "Search…"
}
