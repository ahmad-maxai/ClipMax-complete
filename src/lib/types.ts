export type ClipItem = {
  id: string;
  title: string;
  content: string;
  createdAt: number;
  modifiedAt: number;
  characterCount: number;
  sizeBytes: number;
  pinned: boolean;
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

export type Retention = "off" | "1h" | "6h" | "24h" | "72h" | "forever";

export const SORT_LABELS: Record<SortKey, string> = {
  modified: "Recently modified",
  newest: "Newest first",
  oldest: "Oldest first",
  title: "Title A–Z",
};

export const RETENTION_LABELS: Record<Retention, string> = {
  off: "Off",
  "1h": "1 hour",
  "6h": "6 hours",
  "24h": "24 hours",
  "72h": "72 hours",
  forever: "Forever",
};

export type Screen =
  | { name: "library" }
  | { name: "details"; id: string }
  | { name: "editor"; id?: string }
  | { name: "settings" }
  | { name: "keyboard" };

export type Nav = (s: Screen) => void;
