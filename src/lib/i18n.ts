import React, { createContext, useContext } from "react";
import { AppUiLocale, Retention, SortKey, ThemeMode } from "./types";

export const AppLocaleContext = createContext<AppUiLocale>("en");

export function useAppLocale(): AppUiLocale {
  return useContext(AppLocaleContext);
}

export function isArabicLocale(locale: AppUiLocale): boolean {
  return locale === "ar";
}

export function formatSortLabel(sort: SortKey, locale: AppUiLocale): string {
  if (locale === "ar") {
    switch (sort) {
      case "modified":
        return "المعدّلة مؤخراً";
      case "newest":
        return "الأحدث أولاً";
      case "oldest":
        return "الأقدم أولاً";
      case "title":
        return "العنوان أ–ي";
    }
  }
  switch (sort) {
    case "modified":
      return "Recently modified";
    case "newest":
      return "Newest first";
    case "oldest":
      return "Oldest first";
    case "title":
      return "Title A–Z";
  }
}

export function formatRetentionLocalized(
  retention: Retention,
  locale: AppUiLocale,
  customDuration = 15,
  customUnit: "m" | "h" = "m"
): string {
  const isAr = locale === "ar";
  if (retention === "off") return isAr ? "إيقاف" : "Off";
  if (retention === "1h") return isAr ? "ساعة واحدة" : "1 hour";
  if (retention === "6h") return isAr ? "٦ ساعات" : "6 hours";
  if (retention === "24h") return isAr ? "٢٤ ساعة" : "24 hours";
  if (retention === "72h") return isAr ? "٧٢ ساعة" : "72 hours";
  if (retention === "forever") return isAr ? "للأبد" : "Forever";

  const customPart = retention.startsWith("custom:") ? retention.slice(7) : retention;
  const match = customPart.match(/^(\d+)\s*([mh])$/i);
  const num = match ? parseInt(match[1], 10) : customDuration;
  const isHours = match ? match[2].toLowerCase().startsWith("h") : customUnit.startsWith("h");

  if (match || retention === "custom") {
    if (isAr) {
      const unitLabel = isHours
        ? num === 1
          ? "ساعة واحدة"
          : num === 2
            ? "ساعتين"
            : num <= 10
              ? `${num} ساعات`
              : `${num} ساعة`
        : num === 1
          ? "دقيقة واحدة"
          : num === 2
            ? "دقيقتين"
            : num <= 10
              ? `${num} دقائق`
              : `${num} دقيقة`;
      return `مخصص (${unitLabel})`;
    }
    const label = isHours
      ? num === 1
        ? "1 hour"
        : `${num} hours`
      : num === 1
        ? "1 minute"
        : `${num} minutes`;
    return `Custom (${label})`;
  }
  return isAr ? "مخصص" : "Custom";
}

export function formatThemeName(mode: ThemeMode, systemDark: boolean, locale: AppUiLocale): string {
  if (locale === "ar") {
    switch (mode) {
      case "system":
        return `النظام (${systemDark ? "داكن" : "فاتح"})`;
      case "light":
        return "فاتح";
      case "dark":
        return "داكن";
      case "classic":
        return "كلاسيكي";
    }
  }
  switch (mode) {
    case "system":
      return `System (${systemDark ? "Dark" : "Light"})`;
    case "light":
      return "Light";
    case "dark":
      return "Dark";
    case "classic":
      return "Classic";
  }
}

export function getUiText(locale: AppUiLocale) {
  const ar = locale === "ar";
  return {
    common: {
      back: ar ? "رجوع" : "Back",
      cancel: ar ? "إلغاء" : "Cancel",
      confirm: ar ? "تأكيد" : "Confirm",
      close: ar ? "إغلاق" : "Close",
      save: ar ? "حفظ" : "Save",
      delete: ar ? "حذف" : "Delete",
      skip: ar ? "تخطي" : "Skip",
      next: ar ? "التالي" : "Next",
      closeNotification: ar ? "إغلاق الإشعار" : "Close notification",
      untitled: ar ? "بدون عنوان" : "Untitled",
      translatorButtonLabel: ar ? "ENG" : "عرب",
      translatorButtonAria: ar
        ? "تبديل لغة واجهة التطبيق إلى الإنجليزية"
        : "Switch app interface language to Arabic",
    },
    library: {
      switchToLight: ar ? "التبديل إلى المظهر الفاتح" : "Switch to light theme",
      switchToDark: ar ? "التبديل إلى المظهر الداكن" : "Switch to dark theme",
      settings: ar ? "الإعدادات" : "Settings",
      searchSrLabel: ar ? "البحث في المقاطع المحفوظة" : "Search saved clipboard items by text content",
      searchPlaceholder: ar ? "ابحث في نصوص الحافظة والمقتطفات…" : "Search clipboard text & snippets…",
      foundBadge: (n: number) => (ar ? `${n} نتيجة` : `${n} found`),
      clearSearch: ar ? "مسح البحث" : "Clear search",
      savedItemsTitle: ar ? "الحافظة" : "Saved items",
      searchTitle: (q: string) => (ar ? `البحث: "${q}"` : `Search: "${q}"`),
      searchCountSubtitle: (visible: number, total: number) =>
        ar ? `${visible} من أصل ${total} مقطعاً مطابقاً` : `${visible} of ${total} clips match`,
      statsSubtitle: (count: number, pinned: number, size: string) =>
        ar
          ? `${count} ${count === 1 ? "عنصر" : "عناصر"} · ${pinned} مثبّت · ${size}`
          : `${count} ${count === 1 ? "item" : "items"} · ${pinned} pinned · ${size}`,
      sortByLabel: ar ? "ترتيب حسب" : "Sort by",
      sortSelectorAria: ar ? "محدد ترتيب العناصر" : "Sort items selector",
      allFilter: (n: number) => (ar ? `الكل (${n})` : `All (${n})`),
      pinnedFilter: (n: number) => (ar ? `العناصر المثبتة (${n})` : `Pinned (${n})`),
      resetFilter: ar ? "إعادة ضبط التصفية" : "Reset filter",
      emptyTitle: ar ? "لا توجد عناصر محفوظة بعد" : "No saved items yet",
      emptySubtitle: ar
        ? "اضغط على \"عنصر جديد\" أو استورد ملفاً نصياً لحفظ النصوص هنا."
        : "Tap \"New item\" or import a .txt file below to save text.",
      noMatchTitle: (q: string) => (ar ? `لا توجد مقاطع تطابق "${q}"` : `No clips match "${q}"`),
      noMatchSubtitle: ar
        ? "جرّب كلمات بحث مختلفة، أو تحقق مما إذا كان المقطع غير مثبت."
        : "Try searching for different keywords, or check if the clip is unpinned.",
      clearSearchFilterBtn: ar ? "مسح عامل تصفية البحث" : "Clear Search Filter",
      importFile: ar ? "استيراد ملف" : "Import file",
      newItem: ar ? "عنصر جديد" : "New item",
      protectedFromExpiry: ar ? "محمي من انتهاء الصلاحية" : "Protected from expiry",
      pinnedBadge: ar ? "مثبّت" : "Pinned",
      emptyTextFallback: ar ? "نص فارغ" : "Empty text",
      charsCount: (n: number) => (ar ? `${n.toLocaleString()} حرف` : `${n.toLocaleString()} chars`),
      restoredBackup: (count: number) =>
        ar ? `تمت استعادة ${count} عنصراً من النسخة الاحتياطية` : `Restored ${count} items from backup`,
      restoreWarnings: (w: string) => (ar ? `تحذيرات: ${w}` : `Warnings: ${w}`),
      restoreSuccessDesc: (total: number) =>
        ar ? `تمت استعادة الأرشيف. إجمالي المقاطع: ${total}` : `Archive restored. Total clips: ${total}`,
      restoreFailed: ar ? "فشلت الاستعادة" : "Restore failed",
      restoreFailedDesc: ar ? "ملف نسخ احتياطي تالف أو غير صالح." : "Invalid or corrupt backup ZIP archive.",
      importedFileTitle: (name: string) => (ar ? `تم استيراد "${name}"` : `Imported "${name}"`),
      importedFileDesc: (chars: number, size: string) =>
        ar ? `${chars.toLocaleString()} حرفاً (${size})` : `${chars.toLocaleString()} characters (${size})`,
      viewAction: ar ? "عرض" : "View",
      importedBatchTitle: (n: number) => (ar ? `تم استيراد ${n} مقاطع` : `Imported ${n} clips`),
      importedBatchDesc: (n: number) =>
        ar ? `تمت إضافة ${n} عنصراً بنجاح إلى الحافظة.` : `Successfully added ${n} items to library.`,
      importFailed: ar ? "فشل الاستيراد" : "Import failed",
      importFailedDesc: ar ? "تعذرت معالجة الملف المستورد." : "Could not process imported file.",
    },
    details: {
      missingItem: ar ? "لم يعد هذا العنصر موجوداً." : "This item no longer exists.",
      backToLibrary: ar ? "العودة إلى الحافظة" : "Back to library",
      title: ar ? "التفاصيل" : "Details",
      deleteItemAria: ar ? "حذف العنصر" : "Delete item",
      lockedBadge: ar ? "مقفول" : "Locked",
      pinnedBadge: ar ? "مثبّت" : "Pinned",
      modified: ar ? "آخر تعديل" : "Modified",
      created: ar ? "تاريخ الإنشاء" : "Created",
      size: ar ? "الحجم" : "Size",
      contentMeta: ar ? "المحتوى" : "Content",
      sizeValue: (chars: number, size: string) =>
        ar ? `${chars.toLocaleString()} حرف · ${size}` : `${chars.toLocaleString()} chars · ${size}`,
      contentValue: (words: number, lines: number) =>
        ar ? `${words.toLocaleString()} كلمة · ${lines.toLocaleString()} سطر` : `${words.toLocaleString()} words · ${lines.toLocaleString()} lines`,
      expiryLockedTitle: ar ? "الحماية من الانتهاء مفعّلة" : "Expiry protection locked",
      expiryTitle: ar ? "الحماية من انتهاء الصلاحية" : "Expiry protection",
      expiryLockedDesc: ar ? "لن يزيل انتهاء الصلاحية التلقائي هذا المقطع" : "Automatic expiry will not remove this clip",
      expiryPinnedDesc: ar
        ? "محمي أثناء التثبيت · اقفله لحماية مستقلة"
        : "Protected while pinned · lock for independent protection",
      expiresAtDesc: (dt: string) => (ar ? `ينتهي في ${dt}` : `Expires ${dt}`),
      followsRetentionDesc: ar ? "يتبع إعدادات الاحتفاظ النشطة" : "Follows active retention settings",
      foreverRetentionDesc: ar ? "مدة الاحتفاظ للأبد (لا انتهاء تلقائي)" : "Retention is Forever (no auto-expiry)",
      unlockExpiry: ar ? "إلغاء القفل" : "Unlock expiry protection",
      lockFromExpiry: ar ? "قفل الصلاحية" : "Lock from expiry",
      storedUtf8Content: ar ? "محتوى UTF-8 المحفوظ" : "Stored UTF-8 content",
      emptyTextSnippet: ar ? "مقتطف نصي فارغ" : "Empty text snippet",
      actionEdit: ar ? "تعديل" : "Edit",
      actionPin: ar ? "تثبيت" : "Pin",
      actionUnpin: ar ? "إلغاء التثبيت" : "Unpin",
      actionRename: ar ? "إعادة تسمية" : "Rename",
      actionDuplicate: ar ? "نسخ" : "Duplicate",
      actionExportTxt: ar ? "تصدير .txt" : "Export .txt",
      actionShare: ar ? "مشاركة" : "Share",
      copiedBtn: ar ? "تم النسخ" : "Copied",
      copyToClipboard: ar ? "نسخ إلى الحافظة" : "Copy to clipboard",
      renameTitle: ar ? "إعادة تسمية العنصر" : "Rename item",
      titleLabel: ar ? "العنوان" : "Title",
      deleteModalTitle: ar ? "هل تريد حذف هذا العنصر؟" : "Delete this item?",
      deleteModalDesc: (title: string) =>
        ar
          ? `سيتم إزالة "${title}" نهائياً من التخزين الخاص على هذا الجهاز.`
          : `"${title}" will be permanently removed from this device's private storage.`,
      unpinnedToast: ar ? "تم إلغاء التثبيت من الأعلى" : "Unpinned from top",
      pinnedToast: ar ? "تم التثبيت في الأعلى" : "Pinned to top",
      lockedToast: ar ? "تم القفل ضد الانتهاء" : "Locked from expiry",
      lockedToastDesc: ar ? "لن يزيل انتهاء الصلاحية التلقائي هذا المقطع." : "Automatic expiry will not remove this clip.",
      unlockedToast: ar ? "تم إلغاء قفل حماية الصلاحية" : "Expiry protection unlocked",
      unlockedFreshDesc: ar ? "بدأت فترة صلاحية جديدة من الآن." : "A fresh expiry window has started from now.",
      unlockedDoneDesc: ar ? "تم إلغاء قفل هذا المقطع." : "This clip is now unlocked.",
      duplicatedToast: ar ? "تم نسخ العنصر" : "Item duplicated",
      openCopyAction: ar ? "فتح النسخة" : "Open copy",
      copySuffix: ar ? "(نسخة)" : "(copy)",
      exportedToast: (filename: string) => (ar ? `تم تصدير "${filename}"` : `Exported "${filename}"`),
      exportedDesc: ar ? "تم التنزيل عبر تصدير المستندات." : "Downloaded using document export.",
      copiedToast: ar ? "تم النسخ إلى الحافظة" : "Copied to clipboard",
      copiedToastDesc: (chars: number) =>
        ar ? `تم نسخ ${chars.toLocaleString()} حرفاً.` : `${chars.toLocaleString()} characters copied.`,
      renamedToast: ar ? "تمت إعادة تسمية العنصر" : "Item renamed",
      deletedToast: ar ? "تم حذف العنصر" : "Item deleted",
    },
    editor: {
      editItem: ar ? "تعديل العنصر" : "Edit item",
      newItem: ar ? "عنصر جديد" : "New item",
      unsaved: ar ? "غير محفوظ" : "Unsaved",
      paste: ar ? "لصق" : "Paste",
      find: ar ? "بحث" : "Find",
      importTxt: ar ? "استيراد .txt" : "Import .txt",
      findPlaceholder: ar ? "ابحث في المحتوى…" : "Find in content…",
      findInputAria: ar ? "البحث داخل المحتوى النصي" : "Find in text content",
      prevOccurrence: ar ? "النتيجة السابقة" : "Previous occurrence",
      nextOccurrence: ar ? "النتيجة التالية" : "Next occurrence",
      closeFindBar: ar ? "إغلاق شريط البحث" : "Close find bar",
      titlePlaceholder: ar ? "العنوان (اختياري)" : "Title (optional)",
      contentPlaceholder: ar ? "اكتب أو الصق المقتطف النصي هنا…" : "Type or paste text snippet here…",
      charsCount: (n: number) => (ar ? `${n.toLocaleString()} حرف` : `${n.toLocaleString()} characters`),
      storedBytes: (size: string) => (ar ? `${size} محفوظة` : `${size} stored`),
      discardTitle: ar ? "تجاهل التغييرات غير المحفوظة؟" : "Discard unsaved changes?",
      discardDesc: ar
        ? "لديك تعديلات لم تُحفظ في التخزين المحلي. المغادرة الآن ستؤدي إلى تجاهلها."
        : "You have edits that haven't been saved to local storage. Leaving now will discard them.",
      discardBtn: ar ? "تجاهل" : "Discard",
      recoveredDraft: ar ? "تمت استعادة مسودة غير محفوظة" : "Recovered unsaved draft",
      recoveredDraftDesc: (time: string) =>
        ar ? `تم تحميل المسودة السابقة من ${time}` : `Loaded previous draft from ${time}`,
      pastedToast: ar ? "تم لصق نص الحافظة" : "Pasted clipboard text",
      pastedToastDesc: (n: number) => (ar ? `${n.toLocaleString()} حرفاً` : `${n.toLocaleString()} characters`),
      manualPastePrompt: ar ? "الصق النص أدناه:" : "Paste text below:",
      importedFromTxt: (name: string) => (ar ? `تم استيراد النص من "${name}"` : `Imported text from "${name}"`),
      cannotSaveEmpty: ar ? "لا يمكن حفظ مقطع فارغ" : "Cannot save empty clip",
      cannotSaveEmptyDesc: ar ? "يرجى إدخال عنوان أو بعض المحتوى النصي." : "Please provide either a title or some text content.",
      storageLimitTitle: ar ? "تم تجاوز حد التخزين" : "Storage limit exceeded",
      storageLimitDesc: (size: string) =>
        ar
          ? `حجم هذا العنصر ${size}، مما يتجاوز الحد الأقصى 16 ميجابايت.`
          : `This item is ${size}, exceeding the 16 MiB maximum.`,
      savedLocally: ar ? "تم حفظ التغييرات محلياً" : "Changes saved locally",
    },
    settings: {
      title: ar ? "الإعدادات" : "Settings",
      subtitleMeta: ar ? "com.clipmax.app · تطبيق أندرويد أصيل" : "com.clipmax.app · Native Android Redesign",
      subtitleDesc: ar ? "مدير حافظة ولوحة مفاتيح خاصة على الجهاز" : "Private, local-first clipboard manager & IME",
      deviceStorage: ar ? "تخزين الجهاز" : "Device Storage",
      statItems: ar ? "العناصر" : "Items",
      statPinned: ar ? "المثبتة" : "Pinned",
      statOnDevice: ar ? "على الجهاز" : "On device",
      appearance: ar ? "المظهر واللغة" : "Appearance",
      appLanguageTitle: ar ? "لغة التطبيق" : "App language",
      appLanguageDesc: ar
        ? "تغيير لغة واجهة التطبيق والقوائم (مستقلة عن لغة لوحة المفاتيح)."
        : "Change the application interface language (independent of keyboard typing language).",
      appLanguageToast: (loc: AppUiLocale) =>
        ar
          ? `تم تعيين لغة التطبيق إلى ${loc === "ar" ? "العربية" : "English"}`
          : `App language set to ${loc === "ar" ? "العربية" : "English"}`,
      themeTitle: ar ? "المظهر" : "Theme",
      themeDesc: (mode: ThemeMode, systemDark: boolean) => {
        if (ar) {
          switch (mode) {
            case "system":
              return `يتزامن تلقائياً مع مظهر نظام أندرويد (حالياً ${systemDark ? "داكن" : "فاتح"}).`;
            case "classic":
              return `مظهر ClipMax الكلاسيكي متزامن مع النظام (${systemDark ? "داكن" : "فاتح"}).`;
            case "light":
              return "يستخدم دائماً المظهر الفاتح عبر التطبيق ولوحة المفاتيح.";
            case "dark":
              return "يستخدم دائماً المظهر الداكن عبر التطبيق ولوحة المفاتيح.";
          }
        }
        switch (mode) {
          case "system":
            return `Syncs automatically with Android system theme (currently ${systemDark ? "Dark" : "Light"}).`;
          case "classic":
            return `Classic ClipMax styling synced with Android system (${systemDark ? "Dark" : "Light"}).`;
          case "light":
            return "Always uses Light theme across companion app and keyboard.";
          case "dark":
            return "Always uses Dark theme across companion app and keyboard.";
        }
      },
      themeOptions: [
        { value: "system" as const, label: ar ? "النظام" : "System" },
        { value: "light" as const, label: ar ? "فاتح" : "Light" },
        { value: "dark" as const, label: ar ? "داكن" : "Dark" },
        { value: "classic" as const, label: ar ? "كلاسيكي" : "Classic" },
      ],
      themeToast: (label: string) => (ar ? `تم تعيين المظهر إلى ${label}` : `Theme set to ${label}`),
      androidKeyboardSection: ar ? "لوحة مفاتيح نظام أندرويد" : "Android System Keyboard",
      nativeKeyboardTitle: ar ? "لوحة مفاتيح ClipMax الأصلية" : "ClipMax Native System Keyboard",
      nativeKeyboardDesc: ar
        ? "فعّل ClipMax في إعدادات نظام أندرويد واخترها من منتقي أسلوب الإدخال لإدراج المقاطع المحفوظة مباشرة داخل كروم ويوتيوب وتطبيقات المراسلة وأي حقل إدخال."
        : "Enable ClipMax in Android System Settings and select it in the Input Method Picker to insert saved clips directly inside Chrome, YouTube, messaging apps, and any other Android field.",
      step1ManageKeyboards: ar ? "١. إدارة لوحات المفاتيح" : "1. Manage System Keyboards",
      step2SwitchIme: ar ? "٢. تبديل أسلوب الإدخال" : "2. Switch Active Input Method",
      keyboardLanguageTitle: ar ? "لغة لوحة المفاتيح" : "Keyboard language",
      keyboardLanguageDesc: ar
        ? "بدّل بين الإنجليزية والعربية أثناء الكتابة. يمكنك أيضاً الضغط مطولاً على مفتاح المسافة."
        : "Switch between English and Arabic. You can also hold the Spacebar on the keyboard.",
      keyboardLanguageToast: (label: string) =>
        ar ? `تم تعيين لغة لوحة المفاتيح إلى ${label}` : `Keyboard language set to ${label}`,
      captureSection: ar ? "الالتقاط التلقائي والاحتفاظ" : "Automatic Capture & Retention",
      autoCaptureTitle: ar ? "الالتقاط التلقائي" : "Automatic capture",
      autoCaptureDesc: ar
        ? "يلتقط النصوص المحددة والمنسوخة أثناء نشاط ClipMax، ويتوقف تلقائياً في حقول كلمات المرور."
        : "Captures selections/copies while ClipMax is active. Automatically suppressed on password fields.",
      captureEnabledToast: ar ? "تم تفعيل الالتقاط التلقائي" : "Automatic capture enabled",
      captureDisabledToast: ar ? "تم إيقاف الالتقاط التلقائي" : "Automatic capture disabled",
      retentionTitle: ar ? "مدة الاحتفاظ" : "Retention period",
      retentionDesc: ar
        ? "العناصر المثبتة والمحررة يدوياً أو المقفولة لا تُحذف تلقائياً أبداً."
        : "Pinned and manually edited items are never auto-removed.",
      customBtn: ar ? "مخصص" : "Custom",
      customDurationLabel: ar ? "المدة المخصصة" : "Custom duration",
      minutesUnit: ar ? "دقائق" : "Minutes",
      hoursUnit: ar ? "ساعات" : "Hours",
      customInputErr: ar
        ? "يجب أن تكون المدة عدداً صحيحاً موجباً (١ على الأقل)"
        : "Duration must be a positive whole number (min 1)",
      customExpiryHint: (n: number, unit: "m" | "h") =>
        ar
          ? `ستنتهي صلاحية المقاطع تلقائياً بعد ${n} ${unit === "m" ? (n === 1 ? "دقيقة" : "دقائق") : n === 1 ? "ساعة" : "ساعات"}.`
          : `Clips will automatically expire after ${n} ${unit === "m" ? (n === 1 ? "minute" : "minutes") : n === 1 ? "hour" : "hours"}.`,
      retentionToast: (label: string) => (ar ? `تم تعيين مدة الاحتفاظ إلى ${label}` : `Retention set to ${label}`),
      backupSection: ar ? "النسخ الاحتياطي والأرشفة" : "Backup & Archiving",
      backupDescPrefix: ar ? "تستخدم النسخ الاحتياطية ZIP بنية ClipMax المنظمة بمجلدَي " : "ZIP backups use ClipMax's structured ",
      backupDescMid: ar ? " و " : " and ",
      backupDescSuffix: ar
        ? ". الاستعادة محمية بحد أمان ١٢٨ ميجابايت وفحوصات أمان المسارات."
        : " layout. Restores are protected by a 128 MiB extraction safety limit and path traversal checks.",
      createZip: ar ? "إنشاء نسخة ZIP" : "Create ZIP",
      restoreZip: ar ? "استعادة ZIP" : "Restore ZIP",
      backupCreatedTitle: ar ? "تم إنشاء نسخة ZIP الاحتياطية" : "Backup ZIP created",
      backupCreatedDesc: (count: number, size: string) =>
        ar ? `تم تصدير ${count} عنصراً (${size}) بتنسيق ClipMax.` : `Exported ${count} items (${size}) in ClipMax format.`,
      backupFailed: ar ? "فشل النسخ الاحتياطي" : "Backup failed",
      restoredJsonTitle: (count: number) =>
        ar ? `تمت استعادة ${count} عنصراً من ملف JSON` : `Restored ${count} items from JSON backup`,
      restoredJsonDesc: (count: number) =>
        ar ? `تمت إضافة ${count} عنصراً إلى مكتبة الجهاز.` : `${count} items added to device library.`,
      restoredZipClean: ar
        ? "تم التحقق من الأرشيف ضمن حد ١٢٨ ميجابايت ومخطط البيانات."
        : "Archive verified against 128 MiB boundary and metadata schemas.",
      privacyTitle: ar ? "الخصوصية وبنية البيانات" : "Privacy & Data Architecture",
      privacySubtitle: ar
        ? "تخزين محلي أولاً، بدون تتبع أو حسابات أو مزامنة سحابية"
        : "Local-first, zero telemetry, no accounts or cloud sync",
      privacyP1Title: ar ? "١. تخزين على الجهاز فقط:" : "1. Device-Only Storage:",
      privacyP1Body: ar
        ? "تُحفظ جميع البيانات في مساحة التخزين المحلية المعزولة للتطبيق دون حسابات أو إعلانات أو إرسال عبر الشبكة."
        : "All data is stored in the app's sandboxed local storage. No accounts, advertising, or external network transmissions are permitted.",
      privacyP2Title: ar ? "٢. حماية كلمات المرور:" : "2. Password Protection:",
      privacyP2Body: ar
        ? "عند التركيز على حقل كلمة مرور، يوقف ClipMax الالتقاط التلقائي ويخفي البطاقات المحفوظة."
        : "When focused on a password field, ClipMax suppresses automatic capture and hides card content.",
      privacyP3Title: ar ? "٣. تقسيم آمن للنصوص:" : "3. Surrogate-Safe Chunking:",
      privacyP3Body: ar
        ? "تلتزم عمليات الإدراج بحد 50,000 وحدة UTF-16 دون تقطيع الرموز التعبيرية أو النصوص العربية."
        : "Text inserts adhere to the strict 50,000 UTF-16 code units limit without splitting high/low surrogate pairs (e.g. emoji or CJK).",
      privacyP4Title: ar ? "٤. نسخ احتياطية مستقلة:" : "4. Independent Backups:",
      privacyP4Body: ar
        ? "النسخ الاحتياطي التلقائي من أندرويد معطّل؛ وتمنحك ملفات ZIP المحلية تحكماً كاملاً."
        : "Android Auto Backup is explicitly disabled in the manifest; user-controlled ZIP archives give complete ownership without cloud leaks.",
      dangerZone: ar ? "منطقة الخطر" : "Danger Zone",
      deleteAllBtn: ar ? "حذف جميع البيانات المحفوظة" : "Delete All Saved Data",
      deleteAllModalTitle: ar ? "هل تريد حذف جميع العناصر المحفوظة؟" : "Delete all saved items?",
      deleteAllModalDesc: (count: number) =>
        ar
          ? `سيؤدي هذا إلى إزالة جميع المقاطع المحفوظة (${count})، بما فيها المثبتة، نهائياً من التخزين المحلي. لا يمكن التراجع عن هذا الإجراء.`
          : `This will permanently remove all ${count} saved text clips, including pinned ones, from local storage. This action cannot be reversed.`,
      deleteAllConfirmBtn: ar ? "حذف الكل" : "Delete all",
      deletedAllToast: ar ? "تم مسح جميع العناصر المحفوظة من التخزين المحلي." : "All saved items have been cleared from local storage.",
      aboutClipMax: ar ? "حول ClipMax" : "About ClipMax",
      openingAndroidSettingsToast: ar ? "جارٍ فتح إعدادات لوحة مفاتيح أندرويد..." : "Opening Android Keyboard Settings...",
      fallbackAndroidSettingsToast: ar
        ? "افتح إعدادات أندرويد ← النظام ← لوحة المفاتيح لتفعيل ClipMax."
        : "Open Android Settings → System → Keyboard to enable ClipMax.",
      openingImePickerToast: ar ? "جارٍ فتح منتقي أسلوب الإدخال..." : "Opening Android Input Method Picker...",
      fallbackImePickerToast: ar
        ? "استخدم مبدّل لوحة المفاتيح في أندرويد لاختيار ClipMax."
        : "Use Android's keyboard switcher to select ClipMax Keyboard.",
    },
    onboardingIntro: {
      welcomeTitle: ar ? "مرحباً بك في ClipMax" : "Welcome to ClipMax",
      welcomeDesc: ar
        ? "احفظ النصوص التي تعيد استخدامها يومياً والصقها بضغطة واحدة مباشرة من لوحة المفاتيح."
        : "Save text you reuse every day and paste it in a single tap right from your keyboard.",
      highlights: [
        {
          id: "clips",
          title: ar ? "مقاطعك جاهزة دائماً" : "Your clips, always ready",
          description: ar
            ? "احفظ المقتطفات والروابط والقوالب مرة واحدة واعثر عليها فوراً."
            : "Save snippets, links, and templates once and find them instantly.",
        },
        {
          id: "keyboard",
          title: ar ? "مدمجة مباشرة في لوحة المفاتيح" : "Built right into your keyboard",
          description: ar
            ? "الصق المقاطع المحفوظة مباشرة داخل كروم ويوتيوب والرسائل وغيرها."
            : "Paste saved clips directly inside Chrome, YouTube, messages, and more.",
        },
        {
          id: "privacy",
          title: ar ? "خصوصية تامة على جهازك" : "Private on your device",
          description: ar
            ? "بدون حسابات أو رفع سحابي — كل شيء يبقى بأمان على هاتفك."
            : "No accounts, no cloud uploads — everything stays safely on your phone.",
        },
      ],
      keyboardLangLabel: ar ? "لغة لوحة المفاتيح" : "Keyboard language",
      defaultEnglishBadge: ar ? "الافتراضي: الإنجليزية" : "Default: English",
    },
    onboardingGuide: {
      setupCompleteAria: ar ? "اكتمل إعداد لوحة مفاتيح ClipMax" : "ClipMax keyboard setup complete",
      allSetTitle: ar ? "أنت جاهز الآن!" : "You're all set!",
      allSetDesc: ar
        ? "لوحة مفاتيح ClipMax جاهزة. أصبحت مقاطعك المحفوظة في متناول يدك كلما استخدمت لوحة المفاتيح."
        : "ClipMax is ready. Your saved clips are now within reach whenever you use your keyboard.",
      startUsingBtn: ar ? "ابدأ استخدام ClipMax" : "Start using ClipMax",
      finishSetupBtn: ar ? "إنهاء الإعداد" : "Finish setup",
      step1ReturnTitle: ar ? "الخطوة ١: تفعيل لوحة مفاتيح ClipMax" : "Step 1: Enable ClipMax keyboard",
      step2ReturnTitle: ar ? "الخطوة ٢: اختيار لوحة المفاتيح النشطة" : "Step 2: Choose active keyboard",
      step3ReturnTitle: ar ? "الخطوة ٣: ضبط انتهاء صلاحية الحافظة" : "Step 3: Configure clipboard expiry",
      scrollBackSubtitle: ar ? "مرّر للعودة لمتابعة الإعداد" : "Scroll back to continue setup",
      returnBtn: ar ? "عودة" : "Return",
      step1Badge: ar ? "الخطوة ١ من ٣" : "Step 1 of 3",
      step2Badge: ar ? "الخطوة ٢ من ٣" : "Step 2 of 3",
      step3Badge: ar ? "الخطوة ٣ من ٣" : "Step 3 of 3",
      step1Heading: ar ? "لنجعل ClipMax متاحة كلوحة مفاتيح." : "Let's make ClipMax available as your keyboard.",
      step2Heading: ar ? "الآن اختر ClipMax كلوحة مفاتيح نشطة!" : "Now switch to ClipMax!",
      step3Heading: ar ? "تحكّم بمدة الاحتفاظ بالمقاطع المنسوخة." : "Choose how long your clips stay saved.",
      step1Body: ar
        ? "افتح إعدادات لوحة المفاتيح وفعّل ClipMax."
        : "Open your keyboard settings and switch on ClipMax.",
      step2Body: ar
        ? "اختر ClipMax متى أردت استخدام مقاطعك المحفوظة من لوحة المفاتيح."
        : "Choose ClipMax whenever you want to use your saved clips from your keyboard.",
      step3Body: ar
        ? "تُحذف المقاطع غير المثبتة تلقائياً بعد مدة الاحتفاظ المختارة للحفاظ على نظافة وخصوصية الحافظة. اختر المدة أدناه أو اضغط إنهاء الإعداد للمتابعة بالافتراضي."
        : "Unpinned clips expire automatically after your selected retention duration to keep your clipboard tidy and private. Customize below or tap Finish setup to keep defaults.",
      retryEnableNotice: ar
        ? "لا تزال ClipMax غير مفعّلة. اضغط على الزر المميز أدناه، ثم فعّل ClipMax وارجع هنا."
        : "ClipMax is still switched off. Tap the highlighted button below, turn ClipMax on, then return here.",
      retrySelectNotice: ar
        ? "لم يتم اختيار ClipMax بعد. اضغط على \"٢. تبديل أسلوب الإدخال\" أدناه واختر ClipMax."
        : "ClipMax isn't selected yet. Tap \"2. Switch Active Input Method\" below and choose ClipMax.",
      justEnabledAck: ar ? "رائع! أصبحت ClipMax متاحة الآن." : "Great! ClipMax is now available.",
      justSelectedAck: ar
        ? "تم التفعيل! لوحة مفاتيح ClipMax نشطة الآن."
        : "Awesome! ClipMax is now your active keyboard.",
    },
    about: {
      title: ar ? "حول ClipMax" : "About ClipMax",
      licenseSubtitle: ar ? "مفتوح المصدر · GPL-3.0-or-later" : "Open-source · GPL-3.0-or-later",
      closeAria: ar ? "إغلاق نافذة حول ClipMax" : "Close About ClipMax",
      introP1: ar
        ? "ClipMax هو مشروع مستقل ومفتوح المصدر للحافظة ولوحة المفاتيح، صُمم لجعل النسخ واللصق اليومي أبسط وأسرع."
        : "ClipMax is an independent, open-source clipboard and keyboard project built to make everyday copying and pasting simpler.",
      introP2: ar
        ? "يمنحك مكاناً موثوقاً لنصوصك وروابطك ومقتطفاتك المتكررة، مع إتاحتها مباشرة داخل لوحة مفاتيح أندرويد."
        : "It gives your frequently used text, links, and snippets a reliable home, while bringing your saved clips directly into your Android keyboard.",
      localFirstTitle: ar ? "مصمم بمبدأ المحلية أولاً" : "Built with a local-first philosophy",
      localFirstDesc: ar
        ? "تبقى مقاطعك على جهازك فقط. ClipMax مصمم بدون حسابات أو رفع سحابي أو تتبع."
        : "Your clips stay on your device. ClipMax is designed without accounts, unnecessary cloud uploads, or telemetry.",
      openSourceTitle: ar ? "مفتوح المصدر وقابل للتطوير" : "Open source. Built to improve.",
      openSourceDesc: ar
        ? "يُطوّر ClipMax كمشروع مفتوح المصدر؛ شيفرته البرمجية متاحة للمراجعة والتعلّم والمساهمة في تحسينه."
        : "ClipMax is developed as an open-source project. Its source code is available for people to inspect, learn from, contribute to, and help improve.",
      licenseDetail: ar
        ? "مرخّص بموجب رخصة جنو العمومية الإصدار ٣.٠ أو أحدث (GPL-3.0-or-later)."
        : "Licensed under GNU General Public License v3.0 or later (GPL-3.0-or-later).",
      viewOnGithub: ar ? "عرض المصدر على GitHub" : "View source on GitHub",
      githubCaption: ar
        ? "تصفّح الشيفرة أو بلّغ عن مشكلة أو ساهم معنا."
        : "Explore the code, report issues, or contribute.",
    },
    expiryReminder: {
      batchTitle: (n: number) =>
        ar ? `لهذا المقطع وقت انتهاء (${n} عناصر)` : `This clip has an expiry time (${n} items)`,
      singleTitle: ar ? "لهذا المقطع وقت انتهاء" : "This clip has an expiry time",
      subtitle: ar
        ? "سيُحذف هذا المقطع تلقائياً وفقاً لإعدادات الاحتفاظ الحالية."
        : "This clip will be automatically removed according to your current retention settings.",
      lockAllBtn: (n: number) => (ar ? `قفل الكل (${n})` : `Lock all (${n})`),
      lockSingleBtn: ar ? "قفل المقطع" : "Lock clip",
      keepExpiryBtn: ar ? "الإبقاء على الصلاحية" : "Keep expiry",
      dismissAria: ar ? "إغلاق تذكير الصلاحية" : "Dismiss expiry reminder",
      lockedBatchToast: (n: number) =>
        ar ? `تم قفل ${n} مقاطع من انتهاء الصلاحية` : `Locked ${n} clips from expiry`,
      lockedSingleToast: ar ? "تم قفل المقطع من انتهاء الصلاحية" : "Clip locked from expiry",
      lockedToastDesc: ar
        ? "لن يزيل انتهاء الصلاحية التلقائي العناصر المقفولة."
        : "Automatic expiry will not remove locked items.",
    },
  };
}
