import React, { useState, useRef, useEffect } from "react";
import {
  ArrowLeft,
  ArchiveRestore,
  ChevronDown,
  Download,
  Keyboard,
  ShieldCheck,
  Trash2,
  Sliders,
  CheckCircle2,
  Info,
} from "lucide-react";
import { TopBar, Btn, IconBtn, Section, ConfirmModal, ClipMaxLogo } from "./primitives";
import {
  AppUiLocale,
  APP_UI_LOCALE_LABELS,
  ClipItem,
  KeyboardLanguage,
  KEYBOARD_LANGUAGE_LABELS,
  Nav,
  Retention,
  RETENTION_PRESET_KEYS,
  ThemeMode,
} from "../lib/types";
import {
  saveStoredRetention,
  saveStoredCaptureEnabled,
  createClipItem,
  getStoredCustomDuration,
  saveStoredCustomDuration,
  getStoredCustomUnit,
  saveStoredCustomUnit,
} from "../lib/storage";
import { formatBytes } from "../lib/chunker";
import { createZipBackup, restoreZipBackup, downloadFile } from "../lib/backup";
import { useToast } from "./ui/Toast";
import { cn } from "../lib/utils";
import { isNativeAndroid } from "../lib/nativeBridge";
import { OnboardingState } from "../lib/onboarding";
import { KeyboardOnboardingGuide } from "./KeyboardOnboardingGuide";
import { AboutClipMaxModal } from "./AboutClipMaxModal";
import {
  formatRetentionLocalized,
  formatThemeName,
  getUiText,
  useAppLocale,
} from "../lib/i18n";

interface SettingsScreenProps {
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
  retention: Retention;
  onChangeRetention: (r: Retention) => void;
  captureEnabled: boolean;
  onChangeCaptureEnabled: (v: boolean) => void;
  themeMode: ThemeMode;
  systemDark: boolean;
  onChangeThemeMode: (mode: ThemeMode) => void;
  appUiLocale: AppUiLocale;
  onChangeAppUiLocale: (locale: AppUiLocale) => void;
  keyboardLanguage: KeyboardLanguage;
  onChangeKeyboardLanguage: (language: KeyboardLanguage) => void;
  nav: Nav;
  onboardingState?: OnboardingState | null;
  reducedMotion?: boolean;
  onSkipOnboarding?: () => void;
  onCompleteOnboarding?: () => void;
  onOnboardingBack?: () => void;
}

export function SettingsScreen({
  items,
  onUpdateItems,
  retention,
  onChangeRetention,
  captureEnabled,
  onChangeCaptureEnabled,
  themeMode,
  systemDark,
  onChangeThemeMode,
  appUiLocale,
  onChangeAppUiLocale,
  keyboardLanguage,
  onChangeKeyboardLanguage,
  nav,
  onboardingState,
  reducedMotion = false,
  onSkipOnboarding,
  onCompleteOnboarding,
  onOnboardingBack,
}: SettingsScreenProps) {
  const locale = useAppLocale();
  const ui = getUiText(locale);
  const t = ui.settings;
  const { toast, success, error } = useToast();
  const [privacyExpanded, setPrivacyExpanded] = useState(false);
  const [confirmDeleteAll, setConfirmDeleteAll] = useState(false);
  const [aboutOpen, setAboutOpen] = useState(false);
  const [isProcessingBackup, setIsProcessingBackup] = useState(false);
  const isCustomActive = retention === "custom" || retention.startsWith("custom:") || /^(\d+)[mh]$/i.test(retention);
  const [customDurationInput, setCustomDurationInput] = useState<string>(() => {
    const match = retention.match(/^(?:custom:)?(\d+)\s*([mh])$/i);
    if (match) return match[1];
    return String(getStoredCustomDuration());
  });
  const [customUnit, setCustomUnit] = useState<"m" | "h">(() => {
    const match = retention.match(/^(?:custom:)?(\d+)\s*([mh])$/i);
    if (match) return match[2].toLowerCase() === "h" ? "h" : "m";
    return getStoredCustomUnit();
  });
  const [customInputError, setCustomInputError] = useState<string | null>(null);

  const customDurationDebounceRef = useRef<{
    timer: number;
    duration: number;
    unit: "m" | "h";
    onChange: (r: Retention) => void;
  } | null>(null);

  const clearPendingCustomDuration = () => {
    const pending = customDurationDebounceRef.current;
    if (pending) {
      window.clearTimeout(pending.timer);
      customDurationDebounceRef.current = null;
    }
  };

  const commitCustomRetentionNow = (
    duration: number,
    unit: "m" | "h",
    commitRetention: (r: Retention) => void = onChangeRetention
  ) => {
    saveStoredCustomDuration(duration);
    saveStoredCustomUnit(unit);
    const customVal = `custom:${duration}${unit}`;
    commitRetention(customVal);
    saveStoredRetention(customVal);
  };

  const flushPendingCustomDuration = () => {
    const pending = customDurationDebounceRef.current;
    if (!pending) return;
    window.clearTimeout(pending.timer);
    customDurationDebounceRef.current = null;
    commitCustomRetentionNow(pending.duration, pending.unit, pending.onChange);
  };

  useEffect(() => {
    return () => {
      flushPendingCustomDuration();
    };
  }, []);

  const handleSelectPreset = (r: Retention) => {
    clearPendingCustomDuration();
    preserveScroll(() => {
      setCustomInputError(null);
      onChangeRetention(r);
      saveStoredRetention(r);
      toast(t.retentionToast(formatRetentionLocalized(r, locale)));
    });
  };

  const handleSelectCustom = () => {
    clearPendingCustomDuration();
    preserveScroll(() => {
      const parsed = parseInt(customDurationInput, 10);
      const safeDuration = Number.isInteger(parsed) && parsed > 0 ? parsed : 15;
      if (!Number.isInteger(parsed) || parsed <= 0) {
        setCustomDurationInput("15");
        setCustomInputError(null);
      }
      commitCustomRetentionNow(safeDuration, customUnit);
      const customVal = `custom:${safeDuration}${customUnit}`;
      toast(t.retentionToast(formatRetentionLocalized(customVal, locale, safeDuration, customUnit)));
    });
  };

  const handleCustomDurationChange = (valStr: string) => {
    setCustomDurationInput(valStr);
    const parsed = parseInt(valStr, 10);
    if (!valStr.trim() || isNaN(parsed) || parsed <= 0) {
      clearPendingCustomDuration();
      setCustomInputError(t.customInputErr);
      return;
    }
    setCustomInputError(null);
    clearPendingCustomDuration();
    const targetUnit = customUnit;
    const commitFn = onChangeRetention;
    const timer = window.setTimeout(() => {
      customDurationDebounceRef.current = null;
      commitCustomRetentionNow(parsed, targetUnit, commitFn);
    }, 140);
    customDurationDebounceRef.current = {
      timer,
      duration: parsed,
      unit: targetUnit,
      onChange: commitFn,
    };
  };

  const handleCustomUnitChange = (newUnit: "m" | "h") => {
    clearPendingCustomDuration();
    setCustomUnit(newUnit);
    const parsed = parseInt(customDurationInput, 10);
    const safeDuration = Number.isInteger(parsed) && parsed > 0 ? parsed : 15;
    commitCustomRetentionNow(safeDuration, newUnit);
    const customVal = `custom:${safeDuration}${newUnit}`;
    toast(t.retentionToast(formatRetentionLocalized(customVal, locale, safeDuration, newUnit)));
  };



  const zipInputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const scrollPosRef = useRef<number>(0);
  const keyboardSectionRef = useRef<HTMLDivElement>(null);
  const enableImeBtnRef = useRef<HTMLDivElement>(null);
  const selectImeBtnRef = useRef<HTMLDivElement>(null);
  const isOnboardingActive =
    Boolean(onboardingState) &&
    !onboardingState?.completed &&
    onboardingState?.step !== "INTRODUCTION";

  const preserveScroll = (action: () => void) => {
    if (scrollRef.current) scrollPosRef.current = scrollRef.current.scrollTop;
    action();
    requestAnimationFrame(() => {
      if (scrollRef.current) scrollRef.current.scrollTop = scrollPosRef.current;
    });
  };

  const totalBytes = items.reduce((acc, c) => acc + c.sizeBytes, 0);
  const pinnedCount = items.filter((c) => c.pinned).length;

  // Open native Android Keyboard Settings
  const handleOpenAndroidSettings = () => {
    if (isNativeAndroid() && window.ClipMaxNative?.openImeSettings) {
      try {
        window.ClipMaxNative.openImeSettings();
        toast(t.openingAndroidSettingsToast);
        return;
      } catch {}
    }
    toast(t.fallbackAndroidSettingsToast);
  };

  // Open native Android Input Method Picker
  const handleOpenImePicker = () => {
    if (isNativeAndroid() && window.ClipMaxNative?.showImePicker) {
      try {
        window.ClipMaxNative.showImePicker();
        toast(t.openingImePickerToast);
        return;
      } catch {}
    }
    toast(t.fallbackImePickerToast);
  };

  const handleSelectTheme = (mode: ThemeMode) => {
    preserveScroll(() => {
      onChangeThemeMode(mode);
      toast(t.themeToast(formatThemeName(mode, systemDark, locale)));
    });
  };

  const handleSelectAppUiLocale = (nextLocale: AppUiLocale) => {
    preserveScroll(() => {
      onChangeAppUiLocale(nextLocale);
      const nextText = getUiText(nextLocale).settings;
      toast(nextText.appLanguageToast(nextLocale));
    });
  };

  const handleSelectKeyboardLanguage = (language: KeyboardLanguage) => {
    preserveScroll(() => {
      onChangeKeyboardLanguage(language);
      toast(t.keyboardLanguageToast(KEYBOARD_LANGUAGE_LABELS[language]));
    });
  };

  // Handle capture toggle
  const handleToggleCapture = (enabled: boolean) => {
    preserveScroll(() => {
      onChangeCaptureEnabled(enabled);
      saveStoredCaptureEnabled(enabled);
      toast(enabled ? t.captureEnabledToast : t.captureDisabledToast);
    });
  };

  // Export ZIP backup
  const handleExportBackup = async () => {
    try {
      setIsProcessingBackup(true);
      const blob = await createZipBackup(items);
      const dateStr = new Date().toISOString().slice(0, 10);
      downloadFile(blob, `clipmax-backup-${dateStr}.zip`);
      success(t.backupCreatedTitle, {
        description: t.backupCreatedDesc(items.length, formatBytes(blob.size)),
      });
    } catch (err: any) {
      error(t.backupFailed, { description: err?.message });
    } finally {
      setIsProcessingBackup(false);
    }
  };

  // Restore ZIP or JSON backup
  const handleRestoreFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    try {
      setIsProcessingBackup(true);
      const lowerName = file.name.toLowerCase();

      // Check if JSON backup
      if (lowerName.endsWith(".json")) {
        const text = await file.text();
        const parsed = JSON.parse(text);
        if (Array.isArray(parsed) && parsed.length > 0) {
          const newItems = parsed.map((p: any) =>
            createClipItem(
              p.title || "Imported clip",
              p.content || "",
              Boolean(p.pinned),
              retention,
              Boolean(p.isExpiryLocked)
            )
          );
          onUpdateItems([...newItems, ...items]);
          success(t.restoredJsonTitle(newItems.length), {
            description: t.restoredJsonDesc(newItems.length),
          });
          return;
        }
      }

      // Standard ZIP backup
      const res = await restoreZipBackup(file, items);
      onUpdateItems(res.items);
      success(ui.library.restoredBackup(res.restoredCount), {
        description: res.warnings.length
          ? ui.library.restoreWarnings(res.warnings.join(", "))
          : t.restoredZipClean,
      });
    } catch (err: any) {
      error(ui.library.restoreFailed, {
        description: err?.message || ui.library.restoreFailedDesc,
      });
    } finally {
      setIsProcessingBackup(false);
      if (zipInputRef.current) zipInputRef.current.value = "";
    }
  };

  // Delete all items
  const handleDeleteAll = () => {
    onUpdateItems([]);
    toast(t.deletedAllToast);
  };

  return (
    <div className="flex h-full max-h-full w-full flex-col bg-background overflow-hidden">
      <input
        ref={zipInputRef}
        type="file"
        accept=".zip,.json,application/zip,application/json"
        className="hidden"
        onChange={handleRestoreFile}
      />

      <TopBar
        left={
          <IconBtn
            label={ui.common.back}
            onClick={() => {
              if (isOnboardingActive && onOnboardingBack) {
                onOnboardingBack();
              } else {
                nav({ name: "library" });
              }
            }}
          >
            <ArrowLeft className="h-5 w-5 rtl:rotate-180" />
          </IconBtn>
        }
        title={t.title}
      />

      <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto space-y-3 px-4 py-3.5 pb-6 text-start">
        {/* App Info Card */}
        <Section>
          <div className="flex items-center gap-3.5 p-3.5" dir="ltr">
            <ClipMaxLogo size={46} className="shadow-sm" />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-bold text-foreground">ClipMax</h3>
                <span className="rounded-xs bg-mint-soft px-1.5 py-0.5 text-[10px] font-bold text-mint-foreground uppercase tracking-wider">
                  v0.5.1
                </span>
              </div>
              <p className="text-xs text-muted-foreground mt-0.5">
                {t.subtitleMeta}
              </p>
              <p className="text-[11px] text-muted-foreground/75 mt-0.5">
                {t.subtitleDesc}
              </p>
            </div>
          </div>
        </Section>

        {/* Storage Overview */}
        <Section title={t.deviceStorage}>
          <div className="grid grid-cols-3 divide-x rtl:divide-x-reverse divide-border px-1 py-3 text-center">
            <StatCard value={String(items.length)} label={t.statItems} />
            <StatCard value={String(pinnedCount)} label={t.statPinned} />
            <StatCard value={formatBytes(totalBytes)} label={t.statOnDevice} />
          </div>
        </Section>

        {/* Appearance & Language */}
        <Section title={t.appearance}>
          <div className="border-b border-border px-4 py-3">
            <p className="text-sm font-semibold text-foreground">{t.appLanguageTitle}</p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {t.appLanguageDesc}
            </p>
            <div
              role="radiogroup"
              aria-label={t.appLanguageTitle}
              className="mt-2.5 grid grid-cols-2 gap-1.5"
            >
              {(["en", "ar"] as const).map((uiLoc) => (
                <button
                  key={uiLoc}
                  type="button"
                  role="radio"
                  aria-checked={appUiLocale === uiLoc}
                  onClick={() => handleSelectAppUiLocale(uiLoc)}
                  className={cn(
                    "h-9 rounded-lg border text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring tap-target",
                    appUiLocale === uiLoc
                      ? "border-mint bg-mint-soft text-mint-foreground"
                      : "border-border bg-surface text-foreground hover:bg-accent"
                  )}
                >
                  {APP_UI_LOCALE_LABELS[uiLoc]}
                </button>
              ))}
            </div>
          </div>

          <div className="px-4 py-3">
            <p className="text-sm font-semibold text-foreground">{t.themeTitle}</p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {t.themeDesc(themeMode, systemDark)}
            </p>
            <div
              role="radiogroup"
              aria-label={t.themeTitle}
              className="mt-2.5 grid grid-cols-4 gap-1.5"
            >
              {t.themeOptions.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  role="radio"
                  aria-checked={themeMode === opt.value}
                  onClick={() => handleSelectTheme(opt.value)}
                  className={cn(
                    "h-9 rounded-lg border text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring tap-target",
                    themeMode === opt.value
                      ? "border-mint bg-mint-soft text-mint-foreground"
                      : "border-border bg-surface text-foreground hover:bg-accent"
                  )}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
        </Section>

        {/* Native Android System Keyboard Setup */}
        <div ref={keyboardSectionRef}>
          <Section title={t.androidKeyboardSection}>
            <div className="p-4 bg-surface-2 border-b border-border flex items-start gap-2.5">
              <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-mint text-primary-foreground dark:text-background shadow-2xs">
                <Keyboard className="h-4.5 w-4.5" />
              </span>
              <div className="min-w-0">
                <span className="text-xs font-bold text-foreground block">
                  {t.nativeKeyboardTitle}
                </span>
                <p className="text-[11px] text-muted-foreground mt-0.5 leading-relaxed">
                  {t.nativeKeyboardDesc}
                </p>
              </div>
            </div>

            <div className="p-3.5 grid grid-cols-1 sm:grid-cols-2 gap-2">
              <div ref={enableImeBtnRef} className="w-full">
                <Btn
                  size="sm"
                  variant="outline"
                  className="w-full justify-start text-xs font-bold gap-2 h-9.5"
                  onClick={handleOpenAndroidSettings}
                >
                  <Sliders className="h-4 w-4 shrink-0 text-mint" />
                  <span className="truncate">{t.step1ManageKeyboards}</span>
                </Btn>
              </div>

              <div ref={selectImeBtnRef} className="w-full">
                <Btn
                  size="sm"
                  variant="mint"
                  className="w-full justify-start text-xs font-bold gap-2 h-9.5"
                  onClick={handleOpenImePicker}
                >
                  <CheckCircle2 className="h-4 w-4 shrink-0" />
                  <span className="truncate">{t.step2SwitchIme}</span>
                </Btn>
              </div>
            </div>

            <div className="border-t border-border px-4 py-3">
              <p className="text-sm font-semibold text-foreground">{t.keyboardLanguageTitle}</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                {t.keyboardLanguageDesc}
              </p>
              <div
                role="radiogroup"
                aria-label={t.keyboardLanguageTitle}
                className="mt-2.5 grid grid-cols-2 gap-1.5"
              >
                {(["en", "ar"] as const).map((lang) => (
                  <button
                    key={lang}
                    type="button"
                    role="radio"
                    aria-checked={keyboardLanguage === lang}
                    onClick={() => handleSelectKeyboardLanguage(lang)}
                    className={cn(
                      "h-9 rounded-lg border text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring tap-target",
                      keyboardLanguage === lang
                        ? "border-mint bg-mint-soft text-mint-foreground"
                        : "border-border bg-surface text-foreground hover:bg-accent"
                    )}
                  >
                    {KEYBOARD_LANGUAGE_LABELS[lang]}
                  </button>
                ))}
              </div>
            </div>
          </Section>
        </div>

        {/* Capture & Retention */}
        <Section title={t.captureSection}>
          <div className="flex items-center justify-between gap-3 px-4 py-3">
            <div className="flex-1">
              <span className="block text-sm font-semibold text-foreground">
                {t.autoCaptureTitle}
              </span>
              <span className="block text-xs text-muted-foreground">
                {t.autoCaptureDesc}
              </span>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={captureEnabled}
              onClick={() => handleToggleCapture(!captureEnabled)}
              className={cn(
                "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                captureEnabled ? "bg-mint" : "bg-muted"
              )}
            >
              <span
                className={cn(
                  "pointer-events-none inline-block h-5 w-5 transform rounded-full bg-surface shadow-md ring-0 transition duration-200 ease-in-out",
                  captureEnabled ? "translate-x-5 rtl:-translate-x-5" : "translate-x-0"
                )}
              />
            </button>
          </div>

          <div className="border-t border-border px-4 py-3">
            <p className="text-sm font-semibold text-foreground">{t.retentionTitle}</p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {t.retentionDesc}
            </p>
            <div
              role="radiogroup"
              aria-label={t.retentionTitle}
              className="mt-2.5 grid grid-cols-3 sm:grid-cols-4 gap-1.5"
            >
              {RETENTION_PRESET_KEYS.map((r) => (
                <button
                  key={r}
                  type="button"
                  role="radio"
                  aria-checked={!isCustomActive && retention === r}
                  onClick={() => handleSelectPreset(r)}
                  className={cn(
                    "h-9 rounded-lg border text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring tap-target",
                    !isCustomActive && retention === r
                      ? "border-mint bg-mint-soft text-mint-foreground"
                      : "border-border bg-surface text-foreground hover:bg-accent"
                  )}
                >
                  {formatRetentionLocalized(r, locale)}
                </button>
              ))}
              <button
                type="button"
                role="radio"
                aria-checked={isCustomActive}
                onClick={handleSelectCustom}
                className={cn(
                  "h-9 rounded-lg border text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring tap-target",
                  isCustomActive
                    ? "border-mint bg-mint-soft text-mint-foreground"
                    : "border-border bg-surface text-foreground hover:bg-accent"
                )}
              >
                {t.customBtn}
              </button>
            </div>

            {isCustomActive && (
              <div className="mt-3 rounded-xl border border-border bg-surface-2 p-3 space-y-2.5 animate-in fade-in slide-in-from-top-1 duration-150">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-foreground">{t.customDurationLabel}</span>
                  <span className="text-[11px] font-medium text-mint">
                    {formatRetentionLocalized(retention, locale, parseInt(customDurationInput, 10) || 15, customUnit)}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="relative flex-1">
                    <input
                      type="number"
                      min="1"
                      step="1"
                      value={customDurationInput}
                      onChange={(e) => handleCustomDurationChange(e.target.value)}
                      onBlur={flushPendingCustomDuration}
                      placeholder="15"
                      className={cn(
                        "w-full h-9 rounded-lg border bg-surface px-3 text-sm font-semibold text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-mint tap-target",
                        customInputError ? "border-danger focus:ring-danger" : "border-border"
                      )}
                      aria-label={t.customDurationLabel}
                    />
                  </div>
                  <div className="flex rounded-lg border border-border bg-surface p-0.5" role="radiogroup" aria-label={t.customDurationLabel}>
                    <button
                      type="button"
                      role="radio"
                      aria-checked={customUnit === "m"}
                      onClick={() => handleCustomUnitChange("m")}
                      className={cn(
                        "h-8 px-3 rounded-md text-xs font-semibold transition-colors tap-target",
                        customUnit === "m"
                          ? "bg-mint-soft text-mint-foreground font-bold shadow-2xs"
                          : "text-muted-foreground hover:text-foreground"
                      )}
                    >
                      {t.minutesUnit}
                    </button>
                    <button
                      type="button"
                      role="radio"
                      aria-checked={customUnit === "h"}
                      onClick={() => handleCustomUnitChange("h")}
                      className={cn(
                        "h-8 px-3 rounded-md text-xs font-semibold transition-colors tap-target",
                        customUnit === "h"
                          ? "bg-mint-soft text-mint-foreground font-bold shadow-2xs"
                          : "text-muted-foreground hover:text-foreground"
                      )}
                    >
                      {t.hoursUnit}
                    </button>
                  </div>
                </div>
                {customInputError ? (
                  <p className="text-[11px] text-danger font-medium">{customInputError}</p>
                ) : (
                  <p className="text-[11px] text-muted-foreground">
                    {t.customExpiryHint(parseInt(customDurationInput, 10) || 15, customUnit)}
                  </p>
                )}
              </div>
            )}
          </div>
        </Section>

        {/* Backup & Restore */}
        <Section title={t.backupSection}>
          <div className="p-4 space-y-2 text-xs text-muted-foreground">
            <p>
              {t.backupDescPrefix}
              <code dir="ltr">metadata/</code>
              {t.backupDescMid}
              <code dir="ltr">content/</code>
              {t.backupDescSuffix}
            </p>
          </div>
          <div className="flex gap-2 px-4 pb-3">
            <Btn
              size="sm"
              className="flex-1"
              variant="outline"
              disabled={isProcessingBackup}
              onClick={handleExportBackup}
            >
              <Download className="h-4 w-4" />
              {t.createZip}
            </Btn>
            <Btn
              size="sm"
              className="flex-1"
              variant="outline"
              disabled={isProcessingBackup}
              onClick={() => zipInputRef.current?.click()}
            >
              <ArchiveRestore className="h-4 w-4" />
              {t.restoreZip}
            </Btn>
          </div>
        </Section>

        {/* Privacy & Guarantees */}
        <Section>
          <button
            type="button"
            aria-expanded={privacyExpanded}
            onClick={() => preserveScroll(() => setPrivacyExpanded((v: boolean) => !v))}
            className="flex w-full items-center gap-3 px-4 py-3.5 text-start tap-target"
          >
            <ShieldCheck className="h-5 w-5 text-mint shrink-0" />
            <div className="flex-1 min-w-0">
              <span className="block text-sm font-semibold text-foreground">
                {t.privacyTitle}
              </span>
              <span className="block text-xs text-muted-foreground">
                {t.privacySubtitle}
              </span>
            </div>
            <ChevronDown
              className={cn(
                "h-4 w-4 text-muted-foreground transition-transform duration-200",
                privacyExpanded && "rotate-180"
              )}
            />
          </button>

          {privacyExpanded && (
            <div className="border-t border-border px-4 py-3 text-xs leading-relaxed text-muted-foreground space-y-2 text-start">
              <p>
                <strong>{t.privacyP1Title}</strong> {t.privacyP1Body}
              </p>
              <p>
                <strong>{t.privacyP2Title}</strong> {t.privacyP2Body}
              </p>
              <p>
                <strong>{t.privacyP3Title}</strong> {t.privacyP3Body}
              </p>
              <p>
                <strong>{t.privacyP4Title}</strong> {t.privacyP4Body}
              </p>
            </div>
          )}
        </Section>


        {/* Danger Zone */}
        <Section title={t.dangerZone}>
          <div className="p-4">
            <Btn
              variant="dangerOutline"
              size="sm"
              onClick={() => setConfirmDeleteAll(true)}
              disabled={items.length === 0}
              className="w-full gap-2"
            >
              <Trash2 className="h-4 w-4" />
              {t.deleteAllBtn}
            </Btn>
          </div>
        </Section>

        <div className="pt-1 flex justify-center">
          <button
            type="button"
            onClick={() => setAboutOpen(true)}
            className="inline-flex min-h-[44px] items-center justify-center gap-1.5 rounded-xl px-4 py-2 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-accent/70 active:scale-[0.98] transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Info className="h-3.5 w-3.5 opacity-80" />
            <span>{t.aboutClipMax}</span>
          </button>
        </div>
      </div>

      {/* Confirm Delete All Modal */}
      <ConfirmModal
        open={confirmDeleteAll}
        title={t.deleteAllModalTitle}
        description={t.deleteAllModalDesc(items.length)}
        confirmLabel={t.deleteAllConfirmBtn}
        destructive={true}
        onConfirm={handleDeleteAll}
        onClose={() => setConfirmDeleteAll(false)}
      />

      {/* About ClipMax Modal */}
      <AboutClipMaxModal open={aboutOpen} onClose={() => setAboutOpen(false)} />

      {isOnboardingActive && onboardingState && (
        <KeyboardOnboardingGuide
          state={onboardingState}
          scrollContainerRef={scrollRef}
          sectionRef={keyboardSectionRef}
          enableBtnRef={enableImeBtnRef}
          selectBtnRef={selectImeBtnRef}
          reducedMotion={reducedMotion}
          onSkip={() => onSkipOnboarding?.()}
          onComplete={() => onCompleteOnboarding?.()}
        />
      )}
    </div>
  );
}

function StatCard({ value, label }: { value: string; label: string }) {
  return (
    <div className="px-1">
      <div className="text-base font-bold tracking-tight text-foreground">{value}</div>
      <div className="text-[11px] text-muted-foreground mt-0.5">{label}</div>
    </div>
  );
}
