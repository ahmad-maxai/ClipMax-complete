import React, { useState, useRef } from "react";
import {
  ArrowLeft,
  ArchiveRestore,
  ChevronDown,
  Download,
  Keyboard,
  ShieldCheck,
  Trash2,
  Package,
  Sliders,
  CheckCircle2,
  Smartphone,
  ExternalLink,
} from "lucide-react";
import { TopBar, Btn, IconBtn, Section, ConfirmModal, ClipMaxLogo } from "./primitives";
import {
  ClipItem,
  Nav,
  Retention,
  RETENTION_LABELS,
  ThemeMode,
} from "../lib/types";
import {
  saveStoredRetention,
  saveStoredCaptureEnabled,
  createClipItem,
} from "../lib/storage";
import { formatBytes } from "../lib/chunker";
import { createZipBackup, restoreZipBackup, downloadFile } from "../lib/backup";
import { useToast } from "./ui/Toast";
import { cn } from "../lib/utils";
import { isNativeAndroid } from "../lib/nativeBridge";

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
  nav: Nav;
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
  nav,
}: SettingsScreenProps) {
  const { toast, success, error } = useToast();
  const [privacyExpanded, setPrivacyExpanded] = useState(false);
  const [confirmDeleteAll, setConfirmDeleteAll] = useState(false);
  const [isProcessingBackup, setIsProcessingBackup] = useState(false);



  const zipInputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const scrollPosRef = useRef<number>(0);

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
        toast("Opening Android Keyboard Settings...");
        return;
      } catch {}
    }
    toast("Open Android Settings → System → Keyboard to enable ClipMax.");
  };

  // Open native Android Input Method Picker
  const handleOpenImePicker = () => {
    if (isNativeAndroid() && window.ClipMaxNative?.showImePicker) {
      try {
        window.ClipMaxNative.showImePicker();
        toast("Opening Android Input Method Picker...");
        return;
      } catch {}
    }
    toast("Use Android's keyboard switcher to select ClipMax Keyboard.");
  };

  const handleSelectTheme = (mode: ThemeMode) => {
    preserveScroll(() => {
      onChangeThemeMode(mode);
      const label =
        mode === "system"
          ? `System (${systemDark ? "Dark" : "Light"})`
          : mode.charAt(0).toUpperCase() + mode.slice(1);
      toast(`Theme set to ${label}`);
    });
  };

  // Handle retention switch
  const handleSelectRetention = (r: Retention) => {
    preserveScroll(() => {
      onChangeRetention(r);
      saveStoredRetention(r);
      toast(`Retention set to ${RETENTION_LABELS[r]}`);
    });
  };

  // Handle capture toggle
  const handleToggleCapture = (enabled: boolean) => {
    preserveScroll(() => {
      onChangeCaptureEnabled(enabled);
      saveStoredCaptureEnabled(enabled);
      toast(enabled ? "Automatic capture enabled" : "Automatic capture disabled");
    });
  };

  // Export ZIP backup
  const handleExportBackup = async () => {
    try {
      setIsProcessingBackup(true);
      const blob = await createZipBackup(items);
      const dateStr = new Date().toISOString().slice(0, 10);
      downloadFile(blob, `clipmax-backup-${dateStr}.zip`);
      success("Backup ZIP created", {
        description: `Exported ${items.length} items (${formatBytes(blob.size)}) in ClipMax format.`,
      });
    } catch (err: any) {
      error("Backup failed", { description: err?.message });
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
            createClipItem(p.title || "Imported clip", p.content || "", Boolean(p.pinned))
          );
          onUpdateItems([...newItems, ...items]);
          success(`Restored ${newItems.length} items from JSON backup`, {
            description: `${newItems.length} items added to device library.`,
          });
          return;
        }
      }

      // Standard ZIP backup
      const res = await restoreZipBackup(file, items);
      onUpdateItems(res.items);
      success(`Restored ${res.restoredCount} items from backup`, {
        description: res.warnings.length
          ? `Warnings: ${res.warnings.join(", ")}`
          : "Archive verified against 128 MiB boundary and metadata schemas.",
      });
    } catch (err: any) {
      error("Restore failed", {
        description: err?.message || "Invalid or corrupt backup file.",
      });
    } finally {
      setIsProcessingBackup(false);
      if (zipInputRef.current) zipInputRef.current.value = "";
    }
  };

  // Delete all items
  const handleDeleteAll = () => {
    onUpdateItems([]);
    toast("All saved items have been cleared from local storage.");
  };

  return (
    <div className="flex h-[100dvh] max-h-[100dvh] w-full flex-col bg-background overflow-hidden">
      <input
        ref={zipInputRef}
        type="file"
        accept=".zip,.json,application/zip,application/json"
        className="hidden"
        onChange={handleRestoreFile}
      />

      <TopBar
        left={
          <IconBtn label="Back" onClick={() => nav({ name: "library" })}>
            <ArrowLeft className="h-5 w-5" />
          </IconBtn>
        }
        title="Settings"
      />

      <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto space-y-3 px-4 py-3.5 pb-6">
        {/* App Info Card */}
        <Section>
          <div className="flex items-center gap-3.5 p-3.5">
            <ClipMaxLogo size={46} className="shadow-sm" />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-bold text-foreground">ClipMax</h3>
                <span className="rounded-xs bg-mint-soft px-1.5 py-0.5 text-[10px] font-bold text-mint-foreground uppercase tracking-wider">
                  v0.5.1
                </span>
              </div>
              <p className="text-xs text-muted-foreground mt-0.5">
                com.clipmax.app · Native Android Redesign
              </p>
              <p className="text-[11px] text-muted-foreground/75 mt-0.5">
                Private, local-first clipboard manager & IME
              </p>
            </div>
          </div>
        </Section>

        {/* Storage Overview */}
        <Section title="Device Storage">
          <div className="grid grid-cols-3 divide-x divide-border px-1 py-3 text-center">
            <StatCard value={String(items.length)} label="Items" />
            <StatCard value={String(pinnedCount)} label="Pinned" />
            <StatCard value={formatBytes(totalBytes)} label="On device" />
          </div>
        </Section>

        {/* Appearance & Theme */}
        <Section title="Appearance">
          <div className="px-4 py-3">
            <p className="text-sm font-semibold text-foreground">Theme</p>
            <p className="text-xs text-muted-foreground mt-0.5">
              {themeMode === "system"
                ? `Syncs automatically with Android system theme (currently ${systemDark ? "Dark" : "Light"}).`
                : themeMode === "classic"
                ? `Classic ClipMax styling synced with Android system (${systemDark ? "Dark" : "Light"}).`
                : themeMode === "light"
                ? "Always uses Light theme across companion app and keyboard."
                : "Always uses Dark theme across companion app and keyboard."}
            </p>
            <div
              role="radiogroup"
              aria-label="App and keyboard theme"
              className="mt-2.5 grid grid-cols-4 gap-1.5"
            >
              {(
                [
                  { value: "system", label: "System" },
                  { value: "light", label: "Light" },
                  { value: "dark", label: "Dark" },
                  { value: "classic", label: "Classic" },
                ] as const
              ).map((opt) => (
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
        <Section title="Android System Keyboard">
          <div className="p-4 bg-surface-2 border-b border-border flex items-start gap-2.5">
            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-mint text-primary-foreground dark:text-background shadow-2xs">
              <Keyboard className="h-4.5 w-4.5" />
            </span>
            <div className="min-w-0">
              <span className="text-xs font-bold text-foreground block">
                ClipMax Native System Keyboard
              </span>
              <p className="text-[11px] text-muted-foreground mt-0.5 leading-relaxed">
                Enable ClipMax in Android System Settings and select it in the Input Method Picker to insert saved clips directly inside Chrome, YouTube, messaging apps, and any other Android field.
              </p>
            </div>
          </div>

          <div className="p-3.5 grid grid-cols-1 sm:grid-cols-2 gap-2">
            <Btn
              size="sm"
              variant="outline"
              className="w-full justify-start text-xs font-bold gap-2 h-9.5"
              onClick={handleOpenAndroidSettings}
            >
              <Sliders className="h-4 w-4 shrink-0 text-mint" />
              <span className="truncate">1. Manage System Keyboards</span>
            </Btn>

            <Btn
              size="sm"
              variant="mint"
              className="w-full justify-start text-xs font-bold gap-2 h-9.5"
              onClick={handleOpenImePicker}
            >
              <CheckCircle2 className="h-4 w-4 shrink-0" />
              <span className="truncate">2. Switch Active Input Method</span>
            </Btn>
          </div>
        </Section>

        {/* Capture & Retention */}
        <Section title="Automatic Capture & Retention">
          <div className="flex items-center justify-between gap-3 px-4 py-3">
            <div className="flex-1">
              <span className="block text-sm font-semibold text-foreground">
                Automatic capture
              </span>
              <span className="block text-xs text-muted-foreground">
                Captures selections/copies while ClipMax is active. Automatically suppressed on password fields.
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
                  captureEnabled ? "translate-x-5" : "translate-x-0"
                )}
              />
            </button>
          </div>

          <div className="border-t border-border px-4 py-3">
            <p className="text-sm font-semibold text-foreground">Retention period</p>
            <p className="text-xs text-muted-foreground mt-0.5">
              Pinned and manually edited items are never auto-removed.
            </p>
            <div
              role="radiogroup"
              aria-label="Retention duration"
              className="mt-2.5 grid grid-cols-3 gap-1.5"
            >
              {(Object.keys(RETENTION_LABELS) as Retention[]).map((r) => (
                <button
                  key={r}
                  type="button"
                  role="radio"
                  aria-checked={retention === r}
                  onClick={() => handleSelectRetention(r)}
                  className={cn(
                    "h-9 rounded-lg border text-xs font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring tap-target",
                    retention === r
                      ? "border-mint bg-mint-soft text-mint-foreground"
                      : "border-border bg-surface text-foreground hover:bg-accent"
                  )}
                >
                  {RETENTION_LABELS[r]}
                </button>
              ))}
            </div>
          </div>
        </Section>

        {/* Backup & Restore */}
        <Section title="Backup & Archiving">
          <div className="p-4 space-y-2 text-xs text-muted-foreground">
            <p>
              ZIP backups use ClipMax's structured <code>metadata/</code> and <code>content/</code> layout. Restores are protected by a 128 MiB extraction safety limit and path traversal checks.
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
              Create ZIP
            </Btn>
            <Btn
              size="sm"
              className="flex-1"
              variant="outline"
              disabled={isProcessingBackup}
              onClick={() => zipInputRef.current?.click()}
            >
              <ArchiveRestore className="h-4 w-4" />
              Restore ZIP
            </Btn>
          </div>
        </Section>

        {/* Privacy & Guarantees */}
        <Section>
          <button
            type="button"
            aria-expanded={privacyExpanded}
            onClick={() => preserveScroll(() => setPrivacyExpanded((v: boolean) => !v))}
            className="flex w-full items-center gap-3 px-4 py-3.5 text-left tap-target"
          >
            <ShieldCheck className="h-5 w-5 text-mint shrink-0" />
            <div className="flex-1 min-w-0">
              <span className="block text-sm font-semibold text-foreground">
                Privacy & Data Architecture
              </span>
              <span className="block text-xs text-muted-foreground">
                Local-first, zero telemetry, no accounts or cloud sync
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
            <div className="border-t border-border px-4 py-3 text-xs leading-relaxed text-muted-foreground space-y-2">
              <p>
                <strong>1. Device-Only Storage:</strong> All data is stored in the app's sandboxed local storage. No accounts, advertising, or external network transmissions are permitted.
              </p>
              <p>
                <strong>2. Password Protection:</strong> When an editable field is marked with <code>TYPE_TEXT_VARIATION_PASSWORD</code>, ClipMax suppresses automatic capture and hides card content.
              </p>
              <p>
                <strong>3. Surrogate-Safe Chunking:</strong> Text inserts adhere to the strict 50,000 UTF-16 code units limit without splitting high/low surrogate pairs (e.g. emoji or CJK).
              </p>
              <p>
                <strong>4. Independent Backups:</strong> Android Auto Backup is explicitly disabled in the manifest; user-controlled ZIP archives give complete ownership without cloud leaks.
              </p>
            </div>
          )}
        </Section>


        {/* Danger Zone */}
        <Section title="Danger Zone">
          <div className="p-4">
            <Btn
              variant="dangerOutline"
              size="sm"
              onClick={() => setConfirmDeleteAll(true)}
              disabled={items.length === 0}
              className="w-full gap-2"
            >
              <Trash2 className="h-4 w-4" />
              Delete All Saved Data
            </Btn>
          </div>
        </Section>

        <p className="text-center text-[11px] text-muted-foreground/70 pt-2">
          ClipMax v0.5.1 Redesign · Native Android Experimental
        </p>
      </div>

      {/* Confirm Delete All Modal */}
      <ConfirmModal
        open={confirmDeleteAll}
        title="Delete all saved items?"
        description={`This will permanently remove all ${items.length} saved text clips, including pinned ones, from local storage. This action cannot be reversed.`}
        confirmLabel="Delete all"
        destructive={true}
        onConfirm={handleDeleteAll}
        onClose={() => setConfirmDeleteAll(false)}
      />

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
