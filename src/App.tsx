import React, { useState, useEffect } from "react";
import {
  Screen,
  ClipItem,
  Retention,
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
import { KeyboardPanelScreen } from "./components/KeyboardPanelScreen";

export default function App() {
  const [screen, setScreen] = useState<Screen>({ name: "library" });
  const [items, setItems] = useState<ClipItem[]>(() => getStoredItems());
  const [retention, setRetention] = useState<Retention>(() => getStoredRetention());
  const [captureEnabled, setCaptureEnabled] = useState<boolean>(() =>
    getStoredCaptureEnabled()
  );
  const [dark, setDark] = useState<boolean>(() => {
    try {
      const saved = localStorage.getItem("clipmax_theme");
      if (saved) return saved === "dark";
      return window.matchMedia("(prefers-color-scheme: dark)").matches;
    } catch {
      return false;
    }
  });

  // Sync dark class on document element
  useEffect(() => {
    if (dark) {
      document.documentElement.classList.add("dark");
    } else {
      document.documentElement.classList.remove("dark");
    }
    try {
      localStorage.setItem("clipmax_theme", dark ? "dark" : "light");
    } catch {}
  }, [dark]);

  // Listen for native Android events (resume refresh, edit intent from IME)
  useEffect(() => {
    const handleRefresh = () => {
      setItems(getStoredItems());
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
    window.addEventListener("clipmax:nav", handleNav);
    return () => {
      window.removeEventListener("clipmax:refresh", handleRefresh);
      window.removeEventListener("clipmax:nav", handleNav);
    };
  }, []);

  // Persist items on state change
  const handleUpdateItems = (updated: ClipItem[]) => {
    setItems(updated);
    saveStoredItems(updated);
  };

  const toggleTheme = () => setDark((prev) => !prev);

  // Render active screen
  const renderScreen = () => {
    switch (screen.name) {
      case "library":
        return (
          <LibraryScreen
            items={items}
            onUpdateItems={handleUpdateItems}
            nav={setScreen}
            dark={dark}
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
            nav={setScreen}
          />
        );
      case "keyboard":
        return (
          <KeyboardPanelScreen
            items={items}
            onUpdateItems={handleUpdateItems}
            captureEnabled={captureEnabled}
            nav={setScreen}
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
