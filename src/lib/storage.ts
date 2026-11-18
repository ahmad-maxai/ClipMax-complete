import { ClipItem, ClipDraft, Retention, SortKey } from "./types";
import { utf8ByteLength, MAX_ITEM_BYTES } from "./chunker";
import { isNativeAndroid } from "./nativeBridge";

const STORAGE_KEY_ITEMS = "clipmax_items_v1";
const STORAGE_KEY_RETENTION = "clipmax_retention_v1";
const STORAGE_KEY_CUSTOM_DURATION = "clipmax_custom_retention_duration";
const STORAGE_KEY_CUSTOM_UNIT = "clipmax_custom_retention_unit";
const STORAGE_KEY_CAPTURE = "clipmax_capture_v1";
const STORAGE_KEY_DRAFT = "clipmax_draft_v1";

const H = 3600_000;
const now = Date.now();

export function getStoredItems(): ClipItem[] {
  // Read directly from native Kotlin ClipStore when in Android WebView
  if (isNativeAndroid() && window.ClipMaxNative?.getItems) {
    try {
      const raw = window.ClipMaxNative.getItems();
      if (raw) {
        const parsed = JSON.parse(raw) as ClipItem[];
        if (Array.isArray(parsed)) {
          try {
            localStorage.setItem(STORAGE_KEY_ITEMS, raw);
          } catch {}
          return sortClips(cleanExpiredItems(parsed), "newest");
        }
      }
    } catch (e) {
      console.warn("Failed to getItems from native ClipStore bridge, falling back to cache", e);
    }
  }

  try {
    const raw = localStorage.getItem(STORAGE_KEY_ITEMS);
    if (!raw) {
      localStorage.setItem(STORAGE_KEY_ITEMS, JSON.stringify([]));
      return [];
    }
    const parsed = JSON.parse(raw) as ClipItem[];
    return sortClips(cleanExpiredItems(parsed), "newest");
  } catch {
    return [];
  }
}

export function saveStoredItems(items: ClipItem[]): void {
  // Sync to native Kotlin ClipStore when in Android WebView
  if (isNativeAndroid() && window.ClipMaxNative?.syncItems) {
    try {
      window.ClipMaxNative.syncItems(JSON.stringify(items));
    } catch (e) {
      console.error("Failed to sync items to native ClipStore", e);
    }
  }
  try {
    localStorage.setItem(STORAGE_KEY_ITEMS, JSON.stringify(items));
  } catch (e) {
    console.error("Failed to persist items to localStorage", e);
  }
}

export function getStoredCustomDuration(): number {
  if (isNativeAndroid() && window.ClipMaxNative?.getCustomRetentionDuration) {
    try {
      const d = window.ClipMaxNative.getCustomRetentionDuration();
      if (typeof d === "number" && d > 0) return d;
    } catch {}
  }
  try {
    const raw = localStorage.getItem(STORAGE_KEY_CUSTOM_DURATION);
    if (raw) {
      const d = parseInt(raw, 10);
      if (d > 0) return d;
    }
  } catch {}
  return 15;
}

export function saveStoredCustomDuration(duration: number): void {
  const safe = duration > 0 ? Math.floor(duration) : 15;
  const unit = getStoredCustomUnit();
  if (isNativeAndroid() && window.ClipMaxNative?.setCustomRetention) {
    try {
      window.ClipMaxNative.setCustomRetention(safe, unit);
    } catch {}
  }
  try {
    localStorage.setItem(STORAGE_KEY_CUSTOM_DURATION, String(safe));
  } catch {}
}

export function getStoredCustomUnit(): "m" | "h" {
  if (isNativeAndroid() && window.ClipMaxNative?.getCustomRetentionUnit) {
    try {
      const u = window.ClipMaxNative.getCustomRetentionUnit();
      if (u === "m" || u === "h") return u;
    } catch {}
  }
  try {
    const u = localStorage.getItem(STORAGE_KEY_CUSTOM_UNIT);
    if (u === "m" || u === "h") return u;
  } catch {}
  return "m";
}

export function saveStoredCustomUnit(unit: "m" | "h"): void {
  const duration = getStoredCustomDuration();
  if (isNativeAndroid() && window.ClipMaxNative?.setCustomRetention) {
    try {
      window.ClipMaxNative.setCustomRetention(duration, unit);
    } catch {}
  }
  try {
    localStorage.setItem(STORAGE_KEY_CUSTOM_UNIT, unit);
  } catch {}
}

export function isRetentionValid(r: string | null | undefined): boolean {
  if (!r) return false;
  if (["off", "1h", "6h", "24h", "72h", "forever", "custom"].includes(r)) return true;
  const clean = r.startsWith("custom:") ? r.slice(7) : r;
  return /^(\d+)\s*([mh])$/i.test(clean);
}

export function getStoredRetention(): Retention {
  if (isNativeAndroid() && window.ClipMaxNative?.getRetention) {
    try {
      const r = window.ClipMaxNative.getRetention();
      if (isRetentionValid(r)) {
        return r as Retention;
      }
    } catch {}
  }
  try {
    const r = localStorage.getItem(STORAGE_KEY_RETENTION);
    if (isRetentionValid(r)) {
      return r as Retention;
    }
  } catch {}
  return "24h";
}

export function saveStoredRetention(retention: Retention): void {
  if (isNativeAndroid() && window.ClipMaxNative?.setRetention) {
    try {
      window.ClipMaxNative.setRetention(retention);
    } catch {}
  }
  try {
    localStorage.setItem(STORAGE_KEY_RETENTION, retention);
  } catch {}
}

export function getStoredCaptureEnabled(): boolean {
  if (isNativeAndroid() && window.ClipMaxNative?.getCaptureEnabled) {
    try {
      return Boolean(window.ClipMaxNative.getCaptureEnabled());
    } catch {}
  }
  try {
    const raw = localStorage.getItem(STORAGE_KEY_CAPTURE);
    if (raw !== null) {
      return raw === "true";
    }
  } catch {}
  return true; // Enabled by default in ClipMax v0.5.1
}

export function saveStoredCaptureEnabled(enabled: boolean): void {
  if (isNativeAndroid() && window.ClipMaxNative?.setCaptureEnabled) {
    try {
      window.ClipMaxNative.setCaptureEnabled(enabled);
    } catch {}
  }
  try {
    localStorage.setItem(STORAGE_KEY_CAPTURE, String(enabled));
  } catch {}
}

export function getStoredDraft(): ClipDraft | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_DRAFT);
    if (raw) return JSON.parse(raw);
  } catch {}
  return null;
}

export function saveStoredDraft(draft: ClipDraft): void {
  try {
    localStorage.setItem(STORAGE_KEY_DRAFT, JSON.stringify(draft));
  } catch {}
}

export function clearStoredDraft(): void {
  try {
    localStorage.removeItem(STORAGE_KEY_DRAFT);
  } catch {}
}

function cleanExpiredItems(items: ClipItem[]): ClipItem[] {
  const current = Date.now();
  let changed = false;
  const filtered = items.filter((item) => {
    // Pinned items are never removed by retention
    if (item.pinned) return true;
    if (item.expiresAt && item.expiresAt <= current) {
      changed = true;
      return false;
    }
    return true;
  });
  if (changed) {
    saveStoredItems(filtered);
  }
  return filtered;
}

export function computeExpiry(retention: Retention): number | null {
  if (retention === "off" || retention === "forever") return null;
  if (retention === "1h") return Date.now() + 1 * H;
  if (retention === "6h") return Date.now() + 6 * H;
  if (retention === "24h") return Date.now() + 24 * H;
  if (retention === "72h") return Date.now() + 72 * H;

  const clean = retention.startsWith("custom:") ? retention.slice(7) : retention;
  const match = clean.match(/^(\d+)\s*([mh])$/i);
  if (match) {
    const num = parseInt(match[1], 10);
    const unit = match[2].toLowerCase();
    if (num > 0) {
      const ms = unit.startsWith("h") ? num * 3600 * 1000 : num * 60 * 1000;
      return Date.now() + ms;
    }
  }
  if (retention === "custom") {
    const num = getStoredCustomDuration();
    const unit = getStoredCustomUnit();
    const ms = unit === "h" ? num * 3600 * 1000 : num * 60 * 1000;
    return Date.now() + ms;
  }
  return Date.now() + 24 * H;
}

export function createClipItem(
  title: string,
  content: string,
  pinned = false,
  retention: Retention = "forever"
): ClipItem {
  const t = Date.now();
  const bytes = utf8ByteLength(content);
  if (bytes > MAX_ITEM_BYTES) {
    throw new Error(`Item exceeds 16 MiB limit (${bytes} bytes).`);
  }
  const cleanTitle = (title.trim() || "Untitled").slice(0, 200);
  const id = `clip-${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 9)}`;

  return {
    id,
    title: cleanTitle,
    content,
    createdAt: t,
    modifiedAt: t,
    characterCount: content.length,
    sizeBytes: bytes,
    pinned,
    expiresAt: pinned ? null : computeExpiry(retention),
  };
}

export function sortClips(list: ClipItem[], sort: SortKey = "newest"): ClipItem[] {
  const copy = [...list];
  copy.sort((a, b) => {
    // Pinned items always float to the top
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    const aCreated = a.createdAt || 0;
    const bCreated = b.createdAt || 0;
    const aModified = a.modifiedAt || aCreated;
    const bModified = b.modifiedAt || bCreated;
    switch (sort) {
      case "newest":
        if (bCreated !== aCreated) return bCreated - aCreated;
        return a.title.localeCompare(b.title, undefined, { sensitivity: "base" });
      case "modified":
        if (bModified !== aModified) return bModified - aModified;
        if (bCreated !== aCreated) return bCreated - aCreated;
        return a.title.localeCompare(b.title, undefined, { sensitivity: "base" });
      case "oldest":
        if (aCreated !== bCreated) return aCreated - bCreated;
        return a.title.localeCompare(b.title, undefined, { sensitivity: "base" });
      case "title":
        return a.title.localeCompare(b.title, undefined, { sensitivity: "base" });
    }
  });
  return copy;
}

export function filterClips(list: ClipItem[], query: string, pinnedOnly: boolean): ClipItem[] {
  const q = query.trim().toLowerCase();
  return list.filter((item) => {
    if (pinnedOnly && !item.pinned) return false;
    if (!q) return true;
    return item.title.toLowerCase().includes(q) || item.content.toLowerCase().includes(q);
  });
}

export function relTime(timestamp: number): string {
  const delta = Date.now() - timestamp;
  const m = Math.round(delta / 60000);
  if (m < 1) return "Just now";
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} h ago`;
  const days = Math.round(h / 24);
  if (days < 7) return `${days} d ago`;
  return new Date(timestamp).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export function fullDateTime(timestamp: number): string {
  return new Date(timestamp).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
