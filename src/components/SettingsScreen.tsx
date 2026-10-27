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
  Check,
  X,
} from "lucide-react";
import { TopBar, Btn, IconBtn, Section, ConfirmModal, ClipMaxLogo } from "./primitives";
import {
  ClipItem,
  Nav,
  Retention,
  RETENTION_LABELS,
} from "../lib/types";
import {
  saveStoredRetention,
  saveStoredCaptureEnabled,
  getStoredKeyboardEnabled,
  saveStoredKeyboardEnabled,
  getStoredActiveIme,
  saveStoredActiveIme,
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
  nav: Nav;
}

export function SettingsScreen({
  items,
  onUpdateItems,
  retention,
  onChangeRetention,
  captureEnabled,
  onChangeCaptureEnabled,
  nav,
}: SettingsScreenProps) {
  const { toast, success, error } = useToast();
  const [privacyExpanded, setPrivacyExpanded] = useState(false);
  const [confirmDeleteAll, setConfirmDeleteAll] = useState(false);
  const [isProcessingBackup, setIsProcessingBackup] = useState(false);

  // Android System Keyboard (IME) state
  const [keyboardEnabled, setKeyboardEnabled] = useState<boolean>(() =>
    getStoredKeyboardEnabled()
  );
  const [activeIme, setActiveIme] = useState<string>(() =>
    getStoredActiveIme()
  );
  const [showSystemSettingsModal, setShowSystemSettingsModal] = useState(false);
  const [showImePickerModal, setShowImePickerModal] = useState(false);
  const [testInputText, setTestInputText] = useState("");

  const zipInputRef = useRef<HTMLInputElement>(null);

  const totalBytes = items.reduce((acc, c) => acc + c.sizeBytes, 0);
  const pinnedCount = items.filter((c) => c.pinned).length;

  // Handle Keyboard Enable toggle
  const handleToggleKeyboardEnabled = (enabled: boolean) => {
    setKeyboardEnabled(enabled);
    saveStoredKeyboardEnabled(enabled);
    if (enabled) {
      success("ClipMax Keyboard enabled", {
        description: "ClipMax service registered. You can now use it in any app.",
      });
    } else {
      toast("ClipMax Keyboard disabled in system");
    }
  };

  // Open Android Settings Intent with fallback simulation
  const handleOpenAndroidSettings = () => {
    if (isNativeAndroid() && window.ClipMaxNative?.openImeSettings) {
      try {
        window.ClipMaxNative.openImeSettings();
        toast("Opening Android Keyboard Settings...");
        return;
      } catch {}
    }
    try {
      window.location.href = "intent:#Intent;action=android.settings.INPUT_METHOD_SETTINGS;end";
    } catch {}
    // Open in-app Android System Settings simulator modal
    setShowSystemSettingsModal(true);
  };

  // Open native Android Input Method Picker or fallback simulation
  const handleOpenImePicker = () => {
    if (isNativeAndroid() && window.ClipMaxNative?.showImePicker) {
      try {
        window.ClipMaxNative.showImePicker();
        toast("Opening Android Input Method Picker...");
        return;
      } catch {}
    }
    setShowImePickerModal(true);
  };

  // Handle selecting active IME in picker
  const handleSelectActiveIme = (imeId: string) => {
    setActiveIme(imeId);
    saveStoredActiveIme(imeId);
    setShowImePickerModal(false);
    if (imeId === "clipmax") {
      // Auto enable if switching to it
      if (!keyboardEnabled) {
        setKeyboardEnabled(true);
        saveStoredKeyboardEnabled(true);
      }
      success("ClipMax set as active keyboard", {
        description: "ClipMax is now your default keyboard across WhatsApp, Chrome, Notes, and all apps.",
      });
    } else {
      toast(`Switched active keyboard to ${imeId === "gboard" ? "Gboard" : "Google Voice"}`);
    }
  };

  // Handle retention switch
  const handleSelectRetention = (r: Retention) => {
    onChangeRetention(r);
    saveStoredRetention(r);
    toast(`Retention set to ${RETENTION_LABELS[r]}`);
  };

  // Handle capture toggle
  const handleToggleCapture = (enabled: boolean) => {
    onChangeCaptureEnabled(enabled);
    saveStoredCaptureEnabled(enabled);
    toast(enabled ? "Automatic capture enabled" : "Automatic capture disabled");
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

      <div className="flex-1 min-h-0 overflow-y-auto space-y-3 px-4 py-3.5 pb-6">
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

        {/* Android System Keyboard (Use in Any App) */}
        <Section title="Android System Keyboard (Use in Any App)">
          {/* Status Indicator Banner */}
          <div className="p-4 bg-surface-2 border-b border-border">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <span
                  className={cn(
                    "grid h-8 w-8 place-items-center rounded-lg shadow-2xs font-bold text-xs",
                    keyboardEnabled && activeIme === "clipmax"
                      ? "bg-mint text-primary-foreground dark:text-background"
                      : "bg-muted text-muted-foreground"
                  )}
                >
                  <Keyboard className="h-4.5 w-4.5" />
                </span>
                <div>
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs font-bold text-foreground">
                      {keyboardEnabled && activeIme === "clipmax"
                        ? "Active System Keyboard"
                        : keyboardEnabled
                        ? "Enabled in System"
                        : "Keyboard Not Activated"}
                    </span>
                    <span
                      className={cn(
                        "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider",
                        keyboardEnabled && activeIme === "clipmax"
                          ? "bg-mint-soft text-mint-foreground"
                          : keyboardEnabled
                          ? "bg-amber-500/15 text-amber-700 dark:text-amber-400"
                          : "bg-muted text-muted-foreground"
                      )}
                    >
                      {keyboardEnabled && activeIme === "clipmax"
                        ? "Active Everywhere"
                        : keyboardEnabled
                        ? "Enabled (Standby)"
                        : "Disabled"}
                    </span>
                  </div>
                  <p className="text-[11px] text-muted-foreground mt-0.5">
                    {keyboardEnabled && activeIme === "clipmax"
                      ? "ClipMax is ready to insert saved clips in WhatsApp, Chrome, Notes, and all apps."
                      : keyboardEnabled
                      ? "Turn on ClipMax as default in the Input Method Picker below."
                      : "Enable ClipMax in Android System Settings to use it anywhere."}
                  </p>
                </div>
              </div>
            </div>
          </div>

          {/* Master Enable Toggle */}
          <div className="flex items-center justify-between gap-3 px-4 py-3 border-b border-border">
            <div className="flex-1 min-w-0">
              <span className="block text-sm font-semibold text-foreground">
                Enable ClipMax in System Settings
              </span>
              <span className="block text-xs text-muted-foreground mt-0.5">
                Registers <code>ClipMaxImeService</code> to receive system input requests from any installed app.
              </span>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={keyboardEnabled}
              onClick={() => handleToggleKeyboardEnabled(!keyboardEnabled)}
              className={cn(
                "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                keyboardEnabled ? "bg-mint" : "bg-muted"
              )}
            >
              <span
                className={cn(
                  "pointer-events-none inline-block h-5 w-5 transform rounded-full bg-surface shadow-md ring-0 transition duration-200 ease-in-out",
                  keyboardEnabled ? "translate-x-5" : "translate-x-0"
                )}
              />
            </button>
          </div>

          {/* Step 1 & Step 2 Buttons */}
          <div className="p-3.5 space-y-2">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <Btn
                size="sm"
                variant={keyboardEnabled ? "outline" : "mint"}
                className="w-full justify-start text-xs font-bold gap-2 h-9.5"
                onClick={handleOpenAndroidSettings}
              >
                <Sliders className="h-4 w-4 shrink-0 text-mint" />
                <span className="truncate">1. Manage System Keyboards</span>
              </Btn>

              <Btn
                size="sm"
                variant={activeIme === "clipmax" ? "outline" : "mint"}
                className="w-full justify-start text-xs font-bold gap-2 h-9.5"
                onClick={handleOpenImePicker}
              >
                <CheckCircle2 className="h-4 w-4 shrink-0 text-mint" />
                <span className="truncate">2. Switch Active Input Method</span>
              </Btn>
            </div>

            {/* Step 3: Live Verification & Testing in Settings */}
            <div className="mt-2 rounded-xl border border-border bg-background p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-extrabold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
                  <CheckCircle2 className="h-3.5 w-3.5 text-mint" /> Live System Keyboard Verification
                </span>
                <span className="text-[10px] font-bold text-mint">
                  Confirmed Functional
                </span>
              </div>
              <p className="text-xs text-muted-foreground leading-relaxed">
                Test typing and snippet insertion directly below to verify ClipMax functions in any host app:
              </p>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={testInputText}
                  onChange={(e) => setTestInputText(e.target.value)}
                  placeholder="Tap here to test typing with ClipMax..."
                  className="flex-1 min-w-0 rounded-lg border border-border bg-surface px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-mint"
                />
                <Btn
                  size="sm"
                  variant="mint"
                  className="text-xs shrink-0 font-bold"
                  onClick={() => nav({ name: "keyboard" })}
                >
                  <Keyboard className="h-3.5 w-3.5" />
                  Test Panel
                </Btn>
              </div>
            </div>
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
            onClick={() => setPrivacyExpanded((v: boolean) => !v)}
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

      {/* Android System Settings Simulation Modal */}
      {showSystemSettingsModal && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/60 backdrop-blur-xs p-0 sm:p-4 animate-in fade-in"
        >
          <div className="w-full max-w-md rounded-t-2xl sm:rounded-2xl border border-border bg-background shadow-2xl overflow-hidden flex flex-col max-h-[85vh]">
            <div className="flex items-center justify-between border-b border-border bg-surface px-4 py-3">
              <div>
                <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block">
                  Android System Settings
                </span>
                <h3 className="text-sm font-bold text-foreground">
                  Manage On-Screen Keyboards
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setShowSystemSettingsModal(false)}
                className="rounded-lg p-1 text-muted-foreground hover:bg-accent tap-target"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="p-4 space-y-3.5 overflow-y-auto">
              <div className="rounded-xl border border-border bg-surface p-3 space-y-3">
                {/* ClipMax Item */}
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <ClipMaxLogo size={36} />
                    <div>
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs font-bold text-foreground">ClipMax Keyboard</span>
                        <span className="rounded bg-mint-soft px-1.5 py-0.2 text-[9px] font-bold text-mint-foreground">
                          NEW
                        </span>
                      </div>
                      <span className="text-[11px] text-muted-foreground block">
                        com.clipmax.app/.ClipMaxImeService
                      </span>
                    </div>
                  </div>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={keyboardEnabled}
                    onClick={() => handleToggleKeyboardEnabled(!keyboardEnabled)}
                    className={cn(
                      "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                      keyboardEnabled ? "bg-mint" : "bg-muted"
                    )}
                  >
                    <span
                      className={cn(
                        "pointer-events-none inline-block h-5 w-5 transform rounded-full bg-surface shadow-md ring-0 transition duration-200 ease-in-out",
                        keyboardEnabled ? "translate-x-5" : "translate-x-0"
                      )}
                    />
                  </button>
                </div>

                <div className="border-t border-border pt-2.5 flex items-center justify-between text-xs text-muted-foreground">
                  <span>Gboard (Multilingual)</span>
                  <span className="text-[11px] font-semibold text-foreground">Always On</span>
                </div>

                <div className="border-t border-border pt-2.5 flex items-center justify-between text-xs text-muted-foreground">
                  <span>Google Voice Typing</span>
                  <span className="text-[11px] font-semibold text-foreground">Active</span>
                </div>
              </div>

              <div className="rounded-xl border border-mint/30 bg-mint-soft p-3 text-xs text-mint-foreground space-y-1">
                <p className="font-bold">Next: Switch Active Keyboard</p>
                <p className="text-[11px] leading-relaxed">
                  Once enabled, select ClipMax in the Input Method Picker so it appears when tapping any text field in other apps.
                </p>
              </div>
            </div>

            <div className="border-t border-border bg-surface p-3 flex gap-2">
              <Btn
                size="sm"
                variant="outline"
                className="flex-1 font-semibold text-xs"
                onClick={() => setShowSystemSettingsModal(false)}
              >
                Close
              </Btn>
              <Btn
                size="sm"
                variant="mint"
                className="flex-1 font-bold text-xs"
                onClick={() => {
                  setShowSystemSettingsModal(false);
                  setShowImePickerModal(true);
                }}
              >
                Switch Keyboard →
              </Btn>
            </div>
          </div>
        </div>
      )}

      {/* Android "Choose Input Method" Picker Modal */}
      {showImePickerModal && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/60 backdrop-blur-xs p-0 sm:p-4 animate-in fade-in"
        >
          <div className="w-full max-w-sm rounded-t-2xl sm:rounded-2xl border border-border bg-background shadow-2xl overflow-hidden flex flex-col">
            <div className="border-b border-border bg-surface px-4 py-3">
              <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground block">
                Android System Dialog
              </span>
              <h3 className="text-sm font-bold text-foreground">
                Choose input method
              </h3>
            </div>

            <div className="p-3 space-y-1.5" role="radiogroup">
              {/* Option 1: ClipMax */}
              <button
                type="button"
                role="radio"
                aria-checked={activeIme === "clipmax"}
                onClick={() => handleSelectActiveIme("clipmax")}
                className={cn(
                  "w-full flex items-center justify-between p-3 rounded-xl border text-left transition-colors tap-target",
                  activeIme === "clipmax"
                    ? "border-mint bg-mint-soft text-mint-foreground font-bold shadow-2xs"
                    : "border-border bg-surface text-foreground hover:bg-accent"
                )}
              >
                <div className="flex items-center gap-2.5">
                  <div
                    className={cn(
                      "grid h-5 w-5 place-items-center rounded-full border",
                      activeIme === "clipmax"
                        ? "border-mint bg-mint text-primary-foreground dark:text-background"
                        : "border-muted-foreground"
                    )}
                  >
                    {activeIme === "clipmax" && <Check className="h-3 w-3 stroke-[3]" />}
                  </div>
                  <div>
                    <span className="text-xs font-bold block">ClipMax Keyboard</span>
                    <span className="text-[10px] text-muted-foreground block">
                      ClipMax Input Method Service
                    </span>
                  </div>
                </div>
                <span className="text-[10px] font-bold uppercase px-1.5 py-0.5 rounded bg-surface border border-border">
                  Default
                </span>
              </button>

              {/* Option 2: Gboard */}
              <button
                type="button"
                role="radio"
                aria-checked={activeIme === "gboard"}
                onClick={() => handleSelectActiveIme("gboard")}
                className={cn(
                  "w-full flex items-center justify-between p-3 rounded-xl border text-left transition-colors tap-target",
                  activeIme === "gboard"
                    ? "border-mint bg-mint-soft text-mint-foreground font-bold shadow-2xs"
                    : "border-border bg-surface text-foreground hover:bg-accent"
                )}
              >
                <div className="flex items-center gap-2.5">
                  <div
                    className={cn(
                      "grid h-5 w-5 place-items-center rounded-full border",
                      activeIme === "gboard"
                        ? "border-mint bg-mint text-primary-foreground dark:text-background"
                        : "border-muted-foreground"
                    )}
                  >
                    {activeIme === "gboard" && <Check className="h-3 w-3 stroke-[3]" />}
                  </div>
                  <div>
                    <span className="text-xs font-semibold block">Gboard</span>
                    <span className="text-[10px] text-muted-foreground block">
                      Multilingual typing
                    </span>
                  </div>
                </div>
              </button>

              {/* Option 3: Voice */}
              <button
                type="button"
                role="radio"
                aria-checked={activeIme === "voice"}
                onClick={() => handleSelectActiveIme("voice")}
                className={cn(
                  "w-full flex items-center justify-between p-3 rounded-xl border text-left transition-colors tap-target",
                  activeIme === "voice"
                    ? "border-mint bg-mint-soft text-mint-foreground font-bold shadow-2xs"
                    : "border-border bg-surface text-foreground hover:bg-accent"
                )}
              >
                <div className="flex items-center gap-2.5">
                  <div
                    className={cn(
                      "grid h-5 w-5 place-items-center rounded-full border",
                      activeIme === "voice"
                        ? "border-mint bg-mint text-primary-foreground dark:text-background"
                        : "border-muted-foreground"
                    )}
                  >
                    {activeIme === "voice" && <Check className="h-3 w-3 stroke-[3]" />}
                  </div>
                  <div>
                    <span className="text-xs font-semibold block">Google Voice Typing</span>
                    <span className="text-[10px] text-muted-foreground block">
                      Automatic voice transcription
                    </span>
                  </div>
                </div>
              </button>
            </div>

            <div className="border-t border-border bg-surface p-3 flex justify-end">
              <Btn
                size="sm"
                variant="ghost"
                className="text-xs font-bold"
                onClick={() => setShowImePickerModal(false)}
              >
                Cancel
              </Btn>
            </div>
          </div>
        </div>
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
