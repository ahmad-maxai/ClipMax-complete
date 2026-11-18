import React, { useState, useEffect } from "react";
import {
  Screen,
  ClipItem,
  Retention,
  ThemeMode,
} from "./lib/types";
import {
  getStoredItems,
  saveStoredItems,
  getStoredRetention,
  getStoredCaptureEnabled,
} from "./lib/storage";
import { ToastProvider } from "./components/ui/Toast";
import { LibraryScreen } from "./components/LibraryScreen";
import { DetailsScreen } from "./components/DetailsScreen";
import { EditorScreen } from "./components/EditorScreen";
import { SettingsScreen } from "./components/SettingsScreen";
import { OnboardingIntroScreen } from "./components/OnboardingIntroScreen";
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
  const [systemDark, setSystemDark] = useState<boolean>(() => readSystemNight());

  const effectiveDark =
    themeMode === "dark" ? true : themeMode === "light" ? false : systemDark;

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
    window.addEventListener("clipmax:imeStatusChanged", handleImeStatusChanged);
    window.addEventListener("clipmax:nav", handleNav);
    return () => {
      window.removeEventListener("clipmax:refresh", handleRefresh);
      window.removeEventListener("clipmax:imeStatusChanged", handleImeStatusChanged);
      window.removeEventListener("clipmax:nav", handleNav);
    };
  }, []);

  // Persist items on state change
  const handleUpdateItems = (updated: ClipItem[]) => {
    setItems(updated);
    saveStoredItems(updated);
  };

  const toggleTheme = () => {
    setThemeMode(effectiveDark ? "light" : "dark");
  };

  const handleNextFromIntro = () => {
    const next = advanceOnboardingFromIntro();
    setOnboardingState(next);
    setScreen({ name: "settings" });
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
    <ToastProvider>
      <div className="w-full h-[100dvh] max-h-[100dvh] flex flex-col bg-background text-foreground overflow-hidden select-none font-sans antialiased">
        {renderScreen()}
      </div>
    </ToastProvider>
  );
}
