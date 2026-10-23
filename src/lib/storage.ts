import { ClipItem, ClipDraft, Retention, SortKey } from "./types";
import { utf8ByteLength, MAX_ITEM_BYTES } from "./chunker";

const STORAGE_KEY_ITEMS = "clipmax_items_v1";
const STORAGE_KEY_RETENTION = "clipmax_retention_v1";
const STORAGE_KEY_CAPTURE = "clipmax_capture_v1";
const STORAGE_KEY_DRAFT = "clipmax_draft_v1";
const STORAGE_KEY_IME_ENABLED = "clipmax_ime_enabled_v1";
const STORAGE_KEY_IME_ACTIVE = "clipmax_ime_active_v1";

const H = 3600_000;
const now = Date.now();

export function getStoredItems(): ClipItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_ITEMS);
    if (!raw) {
      localStorage.setItem(STORAGE_KEY_ITEMS, JSON.stringify([]));
      return [];
    }
    const parsed = JSON.parse(raw) as ClipItem[];
    return cleanExpiredItems(parsed);
  } catch {
    return [];
  }
}

export function saveStoredItems(items: ClipItem[]): void {
  try {
    localStorage.setItem(STORAGE_KEY_ITEMS, JSON.stringify(items));
  } catch (e) {
    console.error("Failed to persist items to localStorage", e);
  }
}

export function getStoredRetention(): Retention {
  try {
    const r = localStorage.getItem(STORAGE_KEY_RETENTION) as Retention;
    if (r && ["off", "1h", "6h", "24h", "72h", "forever"].includes(r)) {
      return r;
    }
  } catch {}
  return "24h";
}

export function saveStoredRetention(retention: Retention): void {
  try {
    localStorage.setItem(STORAGE_KEY_RETENTION, retention);
  } catch {}
}

export function getStoredCaptureEnabled(): boolean {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_CAPTURE);
    if (raw !== null) {
      return raw === "true";
    }
  } catch {}
  return true; // Enabled by default in ClipMax v0.5.1
}

export function saveStoredCaptureEnabled(enabled: boolean): void {
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

export function getStoredKeyboardEnabled(): boolean {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_IME_ENABLED);
    if (raw !== null) return raw === "true";
  } catch {}
  return true; // Default to enabled so user can immediately use it
}

export function saveStoredKeyboardEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY_IME_ENABLED, String(enabled));
  } catch {}
}

export function getStoredActiveIme(): string {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_IME_ACTIVE);
    if (raw) return raw;
  } catch {}
  return "clipmax";
}

export function saveStoredActiveIme(ime: string): void {
  try {
    localStorage.setItem(STORAGE_KEY_IME_ACTIVE, ime);
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
  const msMap: Record<Retention, number | null> = {
    off: null,
    "1h": 1 * H,
    "6h": 6 * H,
    "24h": 24 * H,
    "72h": 72 * H,
    forever: null,
  };
  const duration = msMap[retention];
  return duration ? Date.now() + duration : null;
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

export function sortClips(list: ClipItem[], sort: SortKey): ClipItem[] {
  const copy = [...list];
  copy.sort((a, b) => {
    // Pinned items always float to the top
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    switch (sort) {
      case "modified":
        return b.modifiedAt - a.modifiedAt;
      case "newest":
        return b.createdAt - a.createdAt;
      case "oldest":
        return a.createdAt - b.createdAt;
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
