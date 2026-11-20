import React, { useState, useEffect, useRef } from "react";
import {
  AppUiLocale,
  Screen,
  ClipItem,
  KeyboardLanguage,
  Retention,
  ThemeMode,
} from "./lib/types";
import {
  getStoredItems,
  saveStoredItems,
  getStoredRetention,
  getStoredCaptureEnabled,
  getStoredAppUiLocale,
  saveStoredAppUiLocale,
  normalizeAppUiLocale,
  getStoredKeyboardLanguage,
  saveStoredKeyboardLanguage,
  hasFiniteRetention,
  lockItemsFromExpiry,
} from "./lib/storage";
import { isNativeAndroid } from "./lib/nativeBridge";
import { AppLocaleContext, getUiText, useAppLocale } from "./lib/i18n";
import { ToastProvider, useToast } from "./components/ui/Toast";
import { LibraryScreen } from "./components/LibraryScreen";
import { DetailsScreen } from "./components/DetailsScreen";
import { EditorScreen } from "./components/EditorScreen";
import { SettingsScreen } from "./components/SettingsScreen";
import { OnboardingIntroScreen } from "./components/OnboardingIntroScreen";
import { ExpiryReminderBanner } from "./components/ExpiryReminderBanner";
import {
  OnboardingState,
  advanceOnboardingFromIntro,
  completeOnboarding,
  getOnboardingState,
  isReducedMotionPreferred,
  parseOnboardingStateJson,
  returnOnboardingToIntro,
} from "./lib/onboarding";

function normalizeThemeMode(raw?: string | null): ThemeMode {
  const val = (raw ?? "").trim().toLowerCase();
  if (val === "light") return "light";
  if (val === "dark" || val === "graphite") return "dark";
  if (val === "classic" || val === "classic_light") return "classic";
  return "system";
}

function readInitialThemeMode(): ThemeMode {
  try {
    if (typeof window !== "undefined" && window.ClipMaxNative?.getTheme) {
      const nativeTheme = window.ClipMaxNative.getTheme();
      if (nativeTheme) return normalizeThemeMode(nativeTheme);
    }
    const saved = localStorage.getItem("clipmax_theme");
    if (saved) return normalizeThemeMode(saved);
  } catch {}
  return "system";
}

function readSystemNight(): boolean {
  try {
    if (typeof window !== "undefined" && window.ClipMaxNative?.isSystemNightMode) {
      return Boolean(window.ClipMaxNative.isSystemNightMode());
    }
    if (typeof window !== "undefined" && window.matchMedia) {
      return window.matchMedia("(prefers-color-scheme: dark)").matches;
    }
  } catch {}
  return false;
}

export default function App() {
  const [onboardingState, setOnboardingState] = useState<OnboardingState>(() =>
    getOnboardingState()
  );
  const [reducedMotion, setReducedMotion] = useState<boolean>(() =>
    isReducedMotionPreferred()
  );
  const [screen, setScreen] = useState<Screen>(() => {
    const initial = getOnboardingState();
    return !initial.completed && initial.step !== "INTRODUCTION"
      ? { name: "settings" }
      : { name: "library" };
  });
  const [items, setItems] = useState<ClipItem[]>(() => getStoredItems());
  const [retention, setRetention] = useState<Retention>(() => getStoredRetention());
  const [captureEnabled, setCaptureEnabled] = useState<boolean>(() =>
    getStoredCaptureEnabled()
  );
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => readInitialThemeMode());
  const [appUiLocale, setAppUiLocale] = useState<AppUiLocale>(() => getStoredAppUiLocale());
  const [keyboardLanguage, setKeyboardLanguage] = useState<KeyboardLanguage>(() =>
    getStoredKeyboardLanguage()
  );
  const [systemDark, setSystemDark] = useState<boolean>(() => readSystemNight());
  const [pendingExpiryIds, setPendingExpiryIds] = useState<string[]>([]);

  const effectiveDark =
    themeMode === "dark" ? true : themeMode === "light" ? false : systemDark;

  // Apply RTL/LTR layout direction and document lang strictly from independent appUiLocale
  useEffect(() => {
    document.documentElement.dir = appUiLocale === "ar" ? "rtl" : "ltr";
    document.documentElement.lang = appUiLocale;
  }, [appUiLocale]);

  // Apply resolved theme class to DOM and keep local/native preferences synced
  useEffect(() => {
    if (effectiveDark) {
      document.documentElement.classList.add("dark");
    } else {
      document.documentElement.classList.remove("dark");
    }
    try {
      localStorage.setItem("clipmax_theme", themeMode);
    } catch {}
    if (typeof window !== "undefined" && window.ClipMaxNative?.setTheme) {
      try {
        window.ClipMaxNative.setTheme(themeMode);
      } catch {}
    }
  }, [themeMode, effectiveDark]);

  // Live Android & browser system theme synchronisation
  useEffect(() => {
    const syncFromSystem = (explicitDark?: boolean) => {
      if (typeof explicitDark === "boolean") {
        setSystemDark(explicitDark);
      } else {
        setSystemDark(readSystemNight());
      }
    };

    const handleSystemEvent = (e: Event) => {
      const detail = (e as CustomEvent<{ systemDark?: boolean }>).detail;
      syncFromSystem(detail?.systemDark);
    };

    window.addEventListener("clipmax:systemThemeChanged", handleSystemEvent);

    const mq =
      typeof window !== "undefined" && window.matchMedia
        ? window.matchMedia("(prefers-color-scheme: dark)")
        : null;
    const handleMq = (e: MediaQueryListEvent) => {
      // Only let CSS media query drive if native bridge isn't overriding with Android uiMode
      if (!window.ClipMaxNative?.isSystemNightMode) {
        setSystemDark(e.matches);
      } else {
        syncFromSystem();
      }
    };
    mq?.addEventListener?.("change", handleMq);

    return () => {
      window.removeEventListener("clipmax:systemThemeChanged", handleSystemEvent);
      mq?.removeEventListener?.("change", handleMq);
    };
  }, []);

  // Listen for native Android events (resume refresh, IME onboarding verification, edit intent from IME)
  useEffect(() => {
    const syncOnboardingState = (next: OnboardingState) => {
      setOnboardingState(next);
      setReducedMotion(isReducedMotionPreferred());
      if (!next.completed && next.step !== "INTRODUCTION") {
        setScreen((prev) => (prev.name === "settings" ? prev : { name: "settings" }));
      }
    };

    const handleRefresh = () => {
      setItems(getStoredItems());
      setSystemDark(readSystemNight());
      setAppUiLocale(getStoredAppUiLocale());
      setKeyboardLanguage(getStoredKeyboardLanguage());
      if (typeof window !== "undefined" && window.ClipMaxNative?.getTheme) {
        try {
          const nativeTheme = window.ClipMaxNative.getTheme();
          if (nativeTheme) {
            setThemeMode(normalizeThemeMode(nativeTheme));
          }
        } catch {}
      }
      syncOnboardingState(getOnboardingState());
    };

    const handleUiLocaleChanged = (e: Event) => {
      const detail = (e as CustomEvent<{ locale?: string }>).detail;
      const next = detail?.locale
        ? normalizeAppUiLocale(detail.locale)
        : getStoredAppUiLocale();
      setAppUiLocale(next);
    };

    const handleImeStatusChanged = (e: Event) => {
      const detail = (e as CustomEvent<unknown>).detail;
      const next = detail ? parseOnboardingStateJson(detail) : getOnboardingState();
      syncOnboardingState(next);
    };

    const handleNav = (e: Event) => {
      const custom = e as CustomEvent<{ screen: string; id?: string }>;
      if (custom.detail?.screen === "editor") {
        setScreen({ name: "editor", id: custom.detail.id });
      } else if (custom.detail?.screen === "details" && custom.detail.id) {
        setScreen({ name: "details", id: custom.detail.id });
      } else if (custom.detail?.screen === "library") {
        setScreen({ name: "library" });
      }
    };

    window.addEventListener("clipmax:refresh", handleRefresh);
    window.addEventListener("clipmax:uiLocaleChanged", handleUiLocaleChanged);
    window.addEventListener("clipmax:imeStatusChanged", handleImeStatusChanged);
    window.addEventListener("clipmax:nav", handleNav);
    return () => {
      window.removeEventListener("clipmax:refresh", handleRefresh);
      window.removeEventListener("clipmax:uiLocaleChanged", handleUiLocaleChanged);
      window.removeEventListener("clipmax:imeStatusChanged", handleImeStatusChanged);
      window.removeEventListener("clipmax:nav", handleNav);
    };
  }, []);

  // Persist items on state change
  const handleUpdateItems = (updated: ClipItem[]) => {
    setItems(updated);
    saveStoredItems(updated);
  };

  // Trigger contextual expiry reminder when finite retention is active
  const handleClipsAdded = (addedIds: string[]) => {
    if (addedIds.length === 0) return;
    const currentRetention = getStoredRetention();
    setRetention(currentRetention);
    if (hasFiniteRetention(currentRetention)) {
      setPendingExpiryIds(addedIds);
    }
  };

  // Clear stale reminder if retention becomes non-finite
  useEffect(() => {
    if (!hasFiniteRetention(retention) && pendingExpiryIds.length > 0) {
      setPendingExpiryIds([]);
    }
  }, [retention, pendingExpiryIds.length]);

  const toggleTheme = () => {
    setThemeMode(effectiveDark ? "light" : "dark");
  };

  const handleNextFromIntro = () => {
    const next = advanceOnboardingFromIntro();
    setOnboardingState(next);
    setScreen({ name: "settings" });
  };

  const handleChangeAppUiLocale = (nextLocale: AppUiLocale) => {
    saveStoredAppUiLocale(nextLocale);
    setAppUiLocale(nextLocale);
  };

  const handleChangeKeyboardLanguage = (nextLanguage: KeyboardLanguage) => {
    saveStoredKeyboardLanguage(nextLanguage);
    setKeyboardLanguage(nextLanguage);
  };

  const handleFinishOrSkipOnboarding = () => {
    const done = completeOnboarding();
    setOnboardingState(done);
    setScreen({ name: "library" });
  };

  const handleBackFromSettingsOnboarding = () => {
    setOnboardingState(returnOnboardingToIntro());
  };

  const renderScreen = () => {
    if (!onboardingState.completed && onboardingState.step === "INTRODUCTION") {
      return (
        <OnboardingIntroScreen
          reducedMotion={reducedMotion}
          keyboardLanguage={keyboardLanguage}
          onChangeKeyboardLanguage={handleChangeKeyboardLanguage}
          onNext={handleNextFromIntro}
          onSkip={handleFinishOrSkipOnboarding}
        />
      );
    }

    switch (screen.name) {
      case "library":
        return (
          <LibraryScreen
            items={items}
            onUpdateItems={handleUpdateItems}
            onClipsAdded={handleClipsAdded}
            nav={setScreen}
            dark={effectiveDark}
            onToggleTheme={toggleTheme}
          />
        );
      case "details":
        return (
          <DetailsScreen
            id={screen.id}
            items={items}
            retention={retention}
            onUpdateItems={handleUpdateItems}
            nav={setScreen}
          />
        );
      case "editor":
        return (
          <EditorScreen
            id={screen.id}
            items={items}
            onUpdateItems={handleUpdateItems}
            onClipsAdded={handleClipsAdded}
            nav={setScreen}
          />
        );
      case "settings":
        return (
          <SettingsScreen
            items={items}
            onUpdateItems={handleUpdateItems}
            retention={retention}
            onChangeRetention={setRetention}
            captureEnabled={captureEnabled}
            onChangeCaptureEnabled={setCaptureEnabled}
            themeMode={themeMode}
            systemDark={systemDark}
            onChangeThemeMode={setThemeMode}
            appUiLocale={appUiLocale}
            onChangeAppUiLocale={handleChangeAppUiLocale}
            keyboardLanguage={keyboardLanguage}
            onChangeKeyboardLanguage={handleChangeKeyboardLanguage}
            nav={setScreen}
            onboardingState={onboardingState}
            reducedMotion={reducedMotion}
            onSkipOnboarding={handleFinishOrSkipOnboarding}
            onCompleteOnboarding={handleFinishOrSkipOnboarding}
            onOnboardingBack={handleBackFromSettingsOnboarding}
          />
        );
      default:
        return null;
    }
  };

  return (
    <AppLocaleContext.Provider value={appUiLocale}>
      <ToastProvider>
        <AppShell
          items={items}
          pendingExpiryIds={pendingExpiryIds}
          hasFiniteExpiry={hasFiniteRetention(retention)}
          onClearPendingExpiry={() => setPendingExpiryIds([])}
          onUpdateItems={handleUpdateItems}
          onToggleUiLocale={() =>
            handleChangeAppUiLocale(appUiLocale === "ar" ? "en" : "ar")
          }
        >
          <div className="w-full h-full max-h-full flex flex-col bg-background text-foreground overflow-hidden select-none font-sans antialiased">
            {renderScreen()}
          </div>
        </AppShell>
      </ToastProvider>
    </AppLocaleContext.Provider>
  );
}

function AppShell({
  items,
  pendingExpiryIds,
  hasFiniteExpiry,
  onClearPendingExpiry,
  onUpdateItems,
  onToggleUiLocale,
  children,
}: {
  items: ClipItem[];
  pendingExpiryIds: string[];
  hasFiniteExpiry: boolean;
  onClearPendingExpiry: () => void;
  onUpdateItems: (items: ClipItem[]) => void;
  onToggleUiLocale: () => void;
  children: React.ReactNode;
}) {
  const locale = useAppLocale();
  const t = getUiText(locale).expiryReminder;
  const { success } = useToast();

  const existingPendingIds = pendingExpiryIds.filter((pid) =>
    items.some((item) => item.id === pid && !item.isExpiryLocked && !item.pinned)
  );

  const handleLockClips = () => {
    if (existingPendingIds.length === 0) {
      onClearPendingExpiry();
      return;
    }
    const updated = lockItemsFromExpiry(items, existingPendingIds);
    onUpdateItems(updated);
    onClearPendingExpiry();
    success(
      existingPendingIds.length > 1
        ? t.lockedBatchToast(existingPendingIds.length)
        : t.lockedSingleToast,
      {
        description: t.lockedToastDesc,
      }
    );
  };

  return (
    <>
      {children}
      {hasFiniteExpiry && existingPendingIds.length > 0 && (
        <ExpiryReminderBanner
          itemIds={existingPendingIds}
          onLockClips={handleLockClips}
          onKeepExpiry={onClearPendingExpiry}
          onDismiss={onClearPendingExpiry}
        />
      )}
      {!isNativeAndroid() && (
        <WebPreviewTranslatorButton locale={locale} onToggle={onToggleUiLocale} />
      )}
    </>
  );
}

const PREF_WEB_BTN_X = "clipmax_translator_btn_x_fraction";
const PREF_WEB_BTN_Y = "clipmax_translator_btn_y_fraction";
const DEFAULT_WEB_BTN_X = 1.0;
const DEFAULT_WEB_BTN_Y = 0.60;

function WebPreviewTranslatorButton({
  locale,
  onToggle,
}: {
  locale: AppUiLocale;
  onToggle: () => void;
}) {
  const common = getUiText(locale).common;
  const btnSize = 48;
  const margin = 12;
  const [tapAnimating, setTapAnimating] = useState(false);
  const [fractions, setFractions] = useState<{ x: number; y: number }>(() => {
    try {
      const sx = Number(localStorage.getItem(PREF_WEB_BTN_X));
      const sy = Number(localStorage.getItem(PREF_WEB_BTN_Y));
      return {
        x: Number.isFinite(sx) && localStorage.getItem(PREF_WEB_BTN_X) !== null ? Math.min(1, Math.max(0, sx)) : DEFAULT_WEB_BTN_X,
        y: Number.isFinite(sy) && localStorage.getItem(PREF_WEB_BTN_Y) !== null ? Math.min(1, Math.max(0, sy)) : DEFAULT_WEB_BTN_Y,
      };
    } catch {
      return { x: DEFAULT_WEB_BTN_X, y: DEFAULT_WEB_BTN_Y };
    }
  });
  const [viewport, setViewport] = useState(() => ({
    w: typeof window !== "undefined" ? window.innerWidth : 360,
    h: typeof window !== "undefined" ? window.innerHeight : 720,
  }));
  const dragRef = useRef<{
    active: boolean;
    moved: boolean;
    startX: number;
    startY: number;
    origLeft: number;
    origTop: number;
  }>({ active: false, moved: false, startX: 0, startY: 0, origLeft: 0, origTop: 0 });

  useEffect(() => {
    const onResize = () => {
      setViewport({ w: window.innerWidth, h: window.innerHeight });
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  const minX = margin;
  const maxX = Math.max(margin, viewport.w - btnSize - margin);
  const minY = margin;
  const maxY = Math.max(margin, viewport.h - btnSize - margin);
  const left = minX + fractions.x * Math.max(0, maxX - minX);
  const top = minY + fractions.y * Math.max(0, maxY - minY);

  return (
    <button
      type="button"
      aria-label={common.translatorButtonAria}
      onAnimationEnd={() => setTapAnimating(false)}
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        dragRef.current = {
          active: true,
          moved: false,
          startX: e.clientX,
          startY: e.clientY,
          origLeft: left,
          origTop: top,
        };
      }}
      onPointerMove={(e) => {
        if (!dragRef.current.active) return;
        const dx = e.clientX - dragRef.current.startX;
        const dy = e.clientY - dragRef.current.startY;
        if (!dragRef.current.moved && Math.hypot(dx, dy) > 8) {
          dragRef.current.moved = true;
        }
        if (dragRef.current.moved) {
          const nextLeft = Math.min(maxX, Math.max(minX, dragRef.current.origLeft + dx));
          const nextTop = Math.min(maxY, Math.max(minY, dragRef.current.origTop + dy));
          const fx = maxX > minX ? (nextLeft - minX) / (maxX - minX) : 0;
          const fy = maxY > minY ? (nextTop - minY) / (maxY - minY) : 0;
          setFractions({ x: fx, y: fy });
        }
      }}
      onPointerUp={(e) => {
        if (!dragRef.current.active) return;
        try {
          e.currentTarget.releasePointerCapture(e.pointerId);
        } catch {}
        const wasMoved = dragRef.current.moved;
        dragRef.current.active = false;
        if (wasMoved) {
          try {
            localStorage.setItem(PREF_WEB_BTN_X, String(fractions.x));
            localStorage.setItem(PREF_WEB_BTN_Y, String(fractions.y));
          } catch {}
        } else {
          setTapAnimating(false);
          requestAnimationFrame(() => setTapAnimating(true));
          onToggle();
        }
      }}
      onPointerCancel={() => {
        dragRef.current.active = false;
      }}
      style={{
        left: `${left}px`,
        top: `${top}px`,
        width: `${btnSize}px`,
        height: `${btnSize}px`,
      }}
      className={`fixed z-45 flex items-center justify-center rounded-full touch-none select-none cursor-grab active:cursor-grabbing focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-transform duration-150 ${
        tapAnimating ? "clipmax-tap-pulse-anim" : "active:scale-95"
      }`}
    >
      {/* Turquoise outer ambient halo */}
      <span
        aria-hidden
        className="pointer-events-none absolute -inset-2 rounded-full clipmax-glass-glow-anim"
        style={{
          background:
            "radial-gradient(circle, rgba(20, 184, 166, 0.34) 0%, rgba(13, 148, 136, 0.14) 55%, transparent 75%)",
        }}
      />
      {/* Translucent liquid-glass body */}
      <span
        aria-hidden
        className="pointer-events-none relative flex h-full w-full items-center justify-center overflow-hidden rounded-full border border-[#0d9488]/70 dark:border-[#2dd4bf]/70 backdrop-blur-md shadow-[0_6px_18px_rgba(13,148,136,0.28)] bg-gradient-to-br from-white/90 via-[#effaf7]/80 to-[#d8ede7]/85 dark:from-[#3a4b4a]/80 dark:via-[#1e292b]/75 dark:to-[#11191b]/82"
      >
        {/* Inner light rim bevel */}
        <span className="pointer-events-none absolute inset-[1px] rounded-full border border-white/80 dark:border-[#ccfbf1]/30" />
        {/* Curved top specular sheen */}
        <span className="pointer-events-none absolute inset-x-1 top-[2px] h-[56%] rounded-full bg-gradient-to-b from-white/60 dark:from-white/20 to-transparent" />
        {/* Periodic diagonal light sweep */}
        <span className="pointer-events-none absolute -inset-y-3 w-5 bg-gradient-to-r from-transparent via-white/65 dark:via-[#ccfbf1]/45 to-transparent clipmax-glass-shine-anim" />
        {/* Crisp label */}
        <span className="relative z-10 text-[12.5px] font-bold tracking-tight text-[#111916] dark:text-[#f8fafc]">
          {common.translatorButtonLabel}
        </span>
      </span>
    </button>
  );
}
