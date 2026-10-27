import React, { useMemo, useState, useRef } from "react";
import {
  FileUp,
  Moon,
  Pin,
  Plus,
  Search,
  Settings,
  Sun,
  X,
  Keyboard,
  ChevronDown,
} from "lucide-react";
import { TopBar, Wordmark, Btn, IconBtn } from "./primitives";
import {
  ClipItem,
  Nav,
  SortKey,
  SORT_LABELS,
} from "../lib/types";
import {
  sortClips,
  filterClips,
  relTime,
  createClipItem,
} from "../lib/storage";
import { formatBytes, utf8ByteLength } from "../lib/chunker";
import { restoreZipBackup } from "../lib/backup";
import { useToast } from "./ui/Toast";
import { cn } from "../lib/utils";

interface LibraryScreenProps {
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
  nav: Nav;
  dark: boolean;
  onToggleTheme: () => void;
}

export function LibraryScreen({
  items,
  onUpdateItems,
  nav,
  dark,
  onToggleTheme,
}: LibraryScreenProps) {
  const { toast, success, error } = useToast();
  const [searchDraft, setSearchDraft] = useState("");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<SortKey>("newest");
  const [pinnedOnly, setPinnedOnly] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const visible = useMemo(
    () => sortClips(filterClips(items, query, pinnedOnly), sort),
    [items, query, pinnedOnly, sort]
  );

  const totalBytes = useMemo(
    () => items.reduce((acc, curr) => acc + curr.sizeBytes, 0),
    [items]
  );

  const pinnedCount = useMemo(() => items.filter((i) => i.pinned).length, [items]);

  // Universal file import supporting .txt, markdown, JSON backups, and ClipMax .zip backups
  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const lowerName = file.name.toLowerCase();

    // 1. Handle ZIP Backup Archive
    if (
      lowerName.endsWith(".zip") ||
      file.type === "application/zip" ||
      file.type === "application/x-zip-compressed"
    ) {
      try {
        const res = await restoreZipBackup(file, items);
        onUpdateItems(res.items);
        success(`Restored ${res.restoredCount} items from backup`, {
          description: res.warnings.length
            ? `Warnings: ${res.warnings.join(", ")}`
            : `Archive restored. Total clips: ${res.items.length}`,
        });
      } catch (err: any) {
        error("Restore failed", {
          description: err?.message || "Invalid or corrupt backup ZIP archive.",
        });
      } finally {
        if (fileInputRef.current) fileInputRef.current.value = "";
      }
      return;
    }

    // 2. Handle Text, Markdown, JSON, Code, and Document files
    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        let text = String(event.target?.result || "");
        // Strip UTF-8 BOM if present
        text = text.replace(/^\uFEFF/, "");

        // Check if this text file is a JSON backup (array of clip objects)
        if (
          lowerName.endsWith(".json") ||
          (text.trim().startsWith("[") && text.trim().endsWith("]"))
        ) {
          try {
            const parsed = JSON.parse(text);
            if (
              Array.isArray(parsed) &&
              parsed.length > 0 &&
              ("content" in parsed[0] || "title" in parsed[0])
            ) {
              const newItems: ClipItem[] = [];
              for (const p of parsed) {
                const itemTitle = String(p.title || "Imported clip");
                const itemContent = String(p.content || "");
                const isPinned = Boolean(p.pinned);
                newItems.push(createClipItem(itemTitle, itemContent, isPinned));
              }
              const merged = [...newItems, ...items];
              onUpdateItems(merged);
              success(`Imported ${newItems.length} clips from JSON`, {
                description: `Successfully restored ${newItems.length} items to library.`,
              });
              return;
            }
          } catch {
            // Not a JSON backup array, treat as regular text content below
          }
        }

        // Regular plain text / markdown / code file import
        const title = file.name.replace(/\.[^/.]+$/, "") || "Imported text";
        const newItem = createClipItem(title, text, false);
        onUpdateItems([newItem, ...items]);
        success(`Imported "${file.name}"`, {
          description: `${text.length.toLocaleString()} characters (${formatBytes(newItem.sizeBytes)})`,
          action: {
            label: "View",
            onClick: () => nav({ name: "details", id: newItem.id }),
          },
        });
      } catch (err: any) {
        error("Import failed", {
          description: err?.message || "Could not process imported file.",
        });
      } finally {
        if (fileInputRef.current) fileInputRef.current.value = "";
      }
    };

    reader.onerror = () => {
      error("Import failed", {
        description: reader.error?.message || "Failed to read file from storage.",
      });
      if (fileInputRef.current) fileInputRef.current.value = "";
    };

    reader.readAsText(file, "UTF-8");
  };

  return (
    <div className="flex h-[100dvh] max-h-[100dvh] w-full flex-col bg-background overflow-hidden">
      {/* Hidden file input for SAF document selection */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".txt,.md,.json,.zip,text/plain,text/*,application/zip,application/json"
        className="hidden"
        onChange={handleImportFile}
      />

      <TopBar
        title={<Wordmark />}
        right={
          <>
            <IconBtn
              label="ClipMax Keyboard (IME)"
              onClick={() => nav({ name: "keyboard" })}
            >
              <Keyboard className="h-4.5 w-4.5" />
            </IconBtn>
            <IconBtn
              label={dark ? "Switch to light theme" : "Switch to dark theme"}
              onClick={onToggleTheme}
            >
              {dark ? <Sun className="h-4.5 w-4.5" /> : <Moon className="h-4.5 w-4.5" />}
            </IconBtn>
            <IconBtn label="Settings" onClick={() => nav({ name: "settings" })}>
              <Settings className="h-4.5 w-4.5" />
            </IconBtn>
          </>
        }
      />

      <div className="shrink-0 space-y-2.5 px-4 pt-2.5 pb-1">
        {/* Full-width Instant Search Bar */}
        <div className="relative w-full" role="search">
          <label className="relative block w-full">
            <span className="sr-only">Search saved clipboard items by text content</span>
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="search"
              value={searchDraft}
              onChange={(e) => {
                const val = e.target.value;
                setSearchDraft(val);
                setQuery(val);
              }}
              placeholder="Search clipboard text & snippets…"
              className="h-10.5 w-full rounded-xl border border-border bg-surface pl-9.5 pr-22 text-xs sm:text-sm text-foreground outline-none placeholder:text-muted-foreground focus:border-mint focus:ring-2 focus:ring-mint/20 shadow-2xs transition-all"
            />
          </label>

          {/* Quick Clear & Matches Counter inside Search Bar */}
          {searchDraft && (
            <div className="absolute right-2 top-1/2 -translate-y-1/2 flex items-center gap-1.5">
              <span className="rounded-full bg-surface-2 border border-border px-1.5 py-0.5 text-[10px] font-bold text-muted-foreground">
                {visible.length} found
              </span>
              <button
                type="button"
                aria-label="Clear search"
                onClick={() => {
                  setSearchDraft("");
                  setQuery("");
                }}
                className="grid h-6.5 w-6.5 place-items-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent tap-target transition-colors"
                title="Clear search"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>

        {/* Section Header with Stats and Sort Selector */}
        <div className="flex items-end justify-between gap-2 pt-0.5">
          <div>
            <h1 className="text-sm sm:text-base font-bold tracking-tight text-foreground">
              {query ? `Search: "${query}"` : "Saved items"}
            </h1>
            <p className="text-[11px] text-muted-foreground">
              {query
                ? `${visible.length} of ${items.length} clips match`
                : `${items.length} ${items.length === 1 ? "item" : "items"} · ${pinnedCount} pinned · ${formatBytes(totalBytes)}`}
            </p>
          </div>

          {/* Sort Selector */}
          <div className="relative">
            <label className="sr-only" htmlFor="sort-select">
              Sort by
            </label>
            <select
              id="sort-select"
              value={sort}
              onChange={(e) => setSort(e.target.value as SortKey)}
              aria-label="Sort items selector"
              className="h-8.5 appearance-none rounded-lg border border-border bg-surface pl-2.5 pr-7 text-[11px] font-medium outline-none focus:border-mint focus:ring-2 focus:ring-mint/20"
            >
              {(Object.keys(SORT_LABELS) as SortKey[]).map((k) => (
                <option key={k} value={k}>
                  {SORT_LABELS[k]}
                </option>
              ))}
            </select>
            <ChevronDown className="pointer-events-none absolute right-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          </div>
        </div>

        {/* Filter Chips */}
        <div className="flex items-center gap-1.5">
          <FilterChip active={!pinnedOnly} onClick={() => setPinnedOnly(false)}>
            All ({items.length})
          </FilterChip>
          <FilterChip active={pinnedOnly} onClick={() => setPinnedOnly(true)}>
            <Pin className="h-3 w-3" />
            Pinned ({pinnedCount})
          </FilterChip>
          {query && (
            <button
              type="button"
              onClick={() => {
                setSearchDraft("");
                setQuery("");
              }}
              className="ml-auto text-[11px] font-semibold text-mint hover:underline tap-target"
            >
              Reset filter
            </button>
          )}
        </div>
      </div>

      {/* Cards List (flex-1 scrollable area) */}
      <ul className="flex-1 min-h-0 overflow-y-auto space-y-2 px-4 py-2" role="list">
        {visible.map((clip) => (
          <li key={clip.id}>
            <ClipCard
              clip={clip}
              query={query}
              onOpen={() => nav({ name: "details", id: clip.id })}
            />
          </li>
        ))}

        {visible.length === 0 && (
          <li className="rounded-xl border border-dashed border-border px-4 py-10 text-center text-sm text-muted-foreground">
            {items.length === 0 ? (
              <div className="space-y-1.5">
                <p className="font-semibold text-foreground">No saved items yet</p>
                <p className="text-xs text-muted-foreground">
                  Tap "New item" or import a .txt file below to save text.
                </p>
              </div>
            ) : (
              <div className="space-y-2 py-2">
                <Search className="h-7 w-7 text-muted-foreground/60 mx-auto" />
                <p className="font-bold text-foreground">No clips match "{query}"</p>
                <p className="text-xs text-muted-foreground max-w-xs mx-auto">
                  Try searching for different keywords, or check if the clip is unpinned.
                </p>
                <Btn
                  size="sm"
                  variant="outline"
                  className="mx-auto text-xs mt-1"
                  onClick={() => {
                    setSearchDraft("");
                    setQuery("");
                    setPinnedOnly(false);
                  }}
                >
                  <X className="h-3.5 w-3.5" />
                  Clear Search Filter
                </Btn>
              </div>
            )}
          </li>
        )}
      </ul>

      {/* Sticky Bottom Actions Bar (Contained at bottom of screen) */}
      <div className="shrink-0 flex gap-2 border-t border-border bg-background p-3">
        <Btn
          className="flex-1"
          variant="outline"
          onClick={() => fileInputRef.current?.click()}
        >
          <FileUp className="h-4 w-4 text-muted-foreground" />
          Import file
        </Btn>
        <Btn
          className="flex-1"
          variant="primary"
          onClick={() => nav({ name: "editor" })}
        >
          <Plus className="h-4 w-4" />
          New item
        </Btn>
      </div>
    </div>
  );
}

function FilterChip({
  active,
  children,
  onClick,
}: {
  active: boolean;
  children: React.ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        "inline-flex h-8 items-center gap-1.5 rounded-full border px-3 text-[12px] font-medium transition-colors tap-target",
        active
          ? "border-mint/50 bg-mint-soft text-mint-foreground font-semibold"
          : "border-border bg-surface text-muted-foreground hover:bg-accent"
      )}
    >
      {children}
    </button>
  );
}

function ClipCard({
  clip,
  onOpen,
  query,
}: {
  clip: ClipItem;
  onOpen: () => void;
  query: string;
}) {
  const preview = clip.content.replace(/\s+/g, " ").trim();
  const isCodeOrHash =
    /^(git|curl|docker|npm|const|class|function|\d{4}|[a-f0-9]{16,})/.test(
      clip.content.trim()
    );

  return (
    <button
      type="button"
      onClick={onOpen}
      className="group block w-full rounded-xl border border-border bg-surface px-4 py-3.5 text-left transition-colors hover:bg-surface-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <div className="flex items-center gap-2">
        <h3 className="min-w-0 flex-1 truncate text-[14px] font-semibold text-foreground group-hover:text-primary">
          <Highlight text={clip.title} q={query} />
        </h3>
        {clip.pinned && (
          <span className="inline-flex items-center gap-1 rounded-full bg-mint-soft px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-mint-foreground">
            <Pin className="h-2.5 w-2.5" aria-hidden />
            Pinned
          </span>
        )}
      </div>

      <p
        className={cn(
          "mt-1.5 line-clamp-2 text-[13px] leading-snug text-muted-foreground",
          isCodeOrHash && "font-mono text-[12px] text-foreground/85"
        )}
      >
        <Highlight text={preview || "Empty text"} q={query} truncateStart={true} />
      </p>

      <div className="mt-2.5 flex items-center gap-2 text-[11px] text-muted-foreground/80">
        <span>{relTime(clip.modifiedAt)}</span>
        <span aria-hidden>·</span>
        <span className="font-mono">{clip.characterCount.toLocaleString()} chars</span>
        <span aria-hidden>·</span>
        <span className="font-mono">{formatBytes(clip.sizeBytes)}</span>
      </div>
    </button>
  );
}

function Highlight({
  text,
  q,
  truncateStart = false,
}: {
  text: string;
  q: string;
  truncateStart?: boolean;
}) {
  const term = q.trim();
  if (!term) return <>{text}</>;

  const idx = text.toLowerCase().indexOf(term.toLowerCase());
  if (idx < 0) return <>{text}</>;

  const start = truncateStart ? Math.max(0, idx - 25) : 0;
  return (
    <>
      {start > 0 && "…"}
      {text.slice(start, idx)}
      <mark className="rounded-xs bg-mint-soft px-0.5 text-foreground font-semibold">
        {text.slice(idx, idx + term.length)}
      </mark>
      {text.slice(idx + term.length)}
    </>
  );
}
