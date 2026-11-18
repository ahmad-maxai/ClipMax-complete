export type ClipItem = {
  id: string;
  title: string;
  content: string;
  createdAt: number;
  modifiedAt: number;
  characterCount: number;
  sizeBytes: number;
  pinned: boolean;
  isExpiryLocked?: boolean;
  expiresAt?: number | null;
  contentFile?: string;
};

export type ClipDraft = {
  itemId: string | null;
  title: string;
  content: string;
  timestamp: number;
};

export type SortKey = "modified" | "newest" | "oldest" | "title";

export type Retention = "off" | "1h" | "6h" | "24h" | "72h" | "forever" | "custom" | string;

export const SORT_LABELS: Record<SortKey, string> = {
  modified: "Recently modified",
  newest: "Newest first",
  oldest: "Oldest first",
  title: "Title A–Z",
};

export const RETENTION_PRESET_KEYS = ["off", "1h", "6h", "24h", "72h", "forever"] as const;

export const RETENTION_LABELS: Record<string, string> = {
  off: "Off",
  "1h": "1 hour",
  "6h": "6 hours",
  "24h": "24 hours",
  "72h": "72 hours",
  forever: "Forever",
  custom: "Custom",
};

export function formatRetentionLabel(
  retention: Retention,
  customDuration = 15,
  customUnit: "m" | "h" = "m"
): string {
  if (retention === "off") return "Off";
  if (retention === "1h") return "1 hour";
  if (retention === "6h") return "6 hours";
  if (retention === "24h") return "24 hours";
  if (retention === "72h") return "72 hours";
  if (retention === "forever") return "Forever";
  const customPart = retention.startsWith("custom:") ? retention.slice(7) : retention;
  const match = customPart.match(/^(\d+)\s*([mh])$/i);
  if (match) {
    const num = parseInt(match[1], 10);
    const unit = match[2].toLowerCase();
    const isHours = unit.startsWith("h");
    const label = isHours ? (num === 1 ? "1 hour" : `${num} hours`) : (num === 1 ? "1 minute" : `${num} minutes`);
    return `Custom (${label})`;
  }
  if (retention === "custom") {
    const isHours = customUnit.startsWith("h");
    const label = isHours ? (customDuration === 1 ? "1 hour" : `${customDuration} hours`) : (customDuration === 1 ? "1 minute" : `${customDuration} minutes`);
    return `Custom (${label})`;
  }
  return "Custom";
}

export type Screen =
  | { name: "library" }
  | { name: "details"; id: string }
  | { name: "editor"; id?: string }
  | { name: "settings" };

export type Nav = (s: Screen) => void;

export type ThemeMode = "system" | "light" | "dark" | "classic";
