import { ClipItem, Retention } from "./types";

export interface ClipMaxNativeBridge {
  isNative(): boolean;
  getItems(): string;
  getItem(id: string): string;
  saveItem(title: string, content: string, pinned: boolean): string;
  updateItem(id: string, title: string, content: string, pinned: boolean): string;
  deleteItem(id: string): boolean;
  setPinned(id: string, pinned: boolean): string;
  syncItems(jsonArrayString: string): boolean;
  clearAllItems(): boolean;
  copyToClipboard(text: string): boolean;
  shareText(text: string, title: string): boolean;
  openImeSettings(): void;
  showImePicker(): void;
  getRetention(): string;
  setRetention(retention: string): void;
  getCaptureEnabled(): boolean;
  setCaptureEnabled(enabled: boolean): void;
}

declare global {
  interface Window {
    ClipMaxNative?: ClipMaxNativeBridge;
  }
}

/** Check if running inside the native Android WebView with ClipStore bridge */
export function isNativeAndroid(): boolean {
  try {
    return typeof window !== "undefined" && Boolean(window.ClipMaxNative?.isNative?.());
  } catch {
    return false;
  }
}

/** Helper to safely invoke native clipboard copy */
export function nativeCopy(text: string): boolean {
  if (isNativeAndroid() && window.ClipMaxNative?.copyToClipboard) {
    try {
      return window.ClipMaxNative.copyToClipboard(text);
    } catch (e) {
      console.warn("Native copy failed, falling back to navigator.clipboard", e);
    }
  }
  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).catch(() => {});
    return true;
  }
  return false;
}

/** Helper to safely invoke native Android share sheet */
export function nativeShare(text: string, title: string = "ClipMax"): boolean {
  if (isNativeAndroid() && window.ClipMaxNative?.shareText) {
    try {
      return window.ClipMaxNative.shareText(text, title);
    } catch (e) {
      console.warn("Native share failed", e);
    }
  }
  if (typeof navigator !== "undefined" && navigator.share) {
    navigator.share({ title, text }).catch(() => {});
    return true;
  }
  return false;
}
