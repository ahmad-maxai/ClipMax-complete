import React, { useMemo, useState, useRef } from "react";
import {
  FileUp,
  Lock,
  Moon,
  Pin,
  Plus,
  Search,
  Settings,
  Sun,
  X,
  ChevronDown,
} from "lucide-react";
import { TopBar, Wordmark, Btn, IconBtn } from "./primitives";
import {
  ClipItem,
  Nav,
  SortKey,
} from "../lib/types";
import {
  sortClips,
  filterClips,
  relTime,
  createClipItem,
  getStoredRetention,
} from "../lib/storage";
import { formatBytes } from "../lib/chunker";
import { restoreZipBackup } from "../lib/backup";
import { useToast } from "./ui/Toast";
import { cn } from "../lib/utils";
import { formatSortLabel, getUiText, useAppLocale } from "../lib/i18n";

interface LibraryScreenProps {
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
  onClipsAdded?: (ids: string[]) => void;
  nav: Nav;
  dark: boolean;
  onToggleTheme: () => void;
}

export function LibraryScreen({
  items,
  onUpdateItems,
  onClipsAdded,
  nav,
  dark,
  onToggleTheme,
}: LibraryScreenProps) {
  const locale = useAppLocale();
  const t = getUiText(locale).library;
  const { success, error } = useToast();
  const [searchDraft, setSearchDraft] = useState("");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<SortKey>("newest");
  const [pinnedOnly, setPinnedOnly] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLUListElement>(null);
  const scrollPosRef = useRef<number>(0);

  const preserveScroll = (action: () => void) => {
    if (scrollRef.current) scrollPosRef.current = scrollRef.current.scrollTop;
    action();
    requestAnimationFrame(() => {
      if (scrollRef.current) scrollRef.current.scrollTop = scrollPosRef.current;
    });
  };

  const visible = useMemo(
    () => sortClips(filterClips(items, query, pinnedOnly), sort),
    [items, query, pinnedOnly, sort]
  );

  const totalBytes = useMemo(
    () => items.reduce((acc, curr) => acc + curr.sizeBytes, 0),
    [items]
  );

  const pinnedCount = useMemo(() => items.filter((i) => i.pinned).length, [items]);

  // Universal file import supporting .txt, markdown, JSON clip arrays, and ClipMax .zip backups
  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const selectedFiles = e.target.files ? Array.from(e.target.files) : [];
    if (selectedFiles.length === 0) return;

    const isZipFile = (f: File) => {
      const lower = f.name.toLowerCase();
      return (
        lower.endsWith(".zip") ||
        f.type === "application/zip" ||
        f.type === "application/x-zip-compressed"
      );
    };

    // 1. Handle ZIP Backup Archive (preserves backup state without reminder spam)
    if (selectedFiles.length === 1 && isZipFile(selectedFiles[0])) {
      try {
        const res = await restoreZipBackup(selectedFiles[0], items);
        onUpdateItems(res.items);
        success(t.restoredBackup(res.restoredCount), {
          description: res.warnings.length
            ? t.restoreWarnings(res.warnings.join(", "))
            : t.restoreSuccessDesc(res.items.length),
        });
      } catch (err: any) {
        error(t.restoreFailed, {
          description: err?.message || t.restoreFailedDesc,
        });
      } finally {
        if (fileInputRef.current) fileInputRef.current.value = "";
      }
      return;
    }

    // 2. Handle regular single or batch text, markdown, JSON, or mixed file imports
    try {
      const activeRetention = getStoredRetention();
      let workingItems = [...items];
      const importedNonBackupItems: ClipItem[] = [];
      let restoredZipCount = 0;

      for (const file of selectedFiles) {
        if (isZipFile(file)) {
          const res = await restoreZipBackup(file, workingItems);
          workingItems = res.items;
          restoredZipCount += res.restoredCount;
          continue;
        }

        let text = await file.text();
        text = text.replace(/^\uFEFF/, "");
        const lowerName = file.name.toLowerCase();

        // Check if this text file is a JSON clip list (array of clip objects)
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
              for (const p of parsed) {
                const itemTitle = String(p.title || "Imported clip");
                const itemContent = String(p.content || "");
                const isPinned = Boolean(p.pinned);
                const locked = Boolean(p.isExpiryLocked);
                const created = createClipItem(
                  itemTitle,
                  itemContent,
                  isPinned,
                  activeRetention,
                  locked
                );
                importedNonBackupItems.push(created);
                workingItems = [created, ...workingItems];
              }
              continue;
            }
          } catch {
            // Not a JSON clip array, treat as regular text content below
          }
        }

        // Regular plain text / markdown / code file import
        const title = file.name.replace(/\.[^/.]+$/, "") || "Imported text";
        const newItem = createClipItem(title, text, false, activeRetention);
        importedNonBackupItems.push(newItem);
        workingItems = [newItem, ...workingItems];
      }

      onUpdateItems(workingItems);

      if (importedNonBackupItems.length === 1 && restoredZipCount === 0) {
        const single = importedNonBackupItems[0];
        const fileName = selectedFiles[0]?.name || single.title;
        success(t.importedFileTitle(fileName), {
          description: t.importedFileDesc(single.characterCount, formatBytes(single.sizeBytes)),
          action: {
            label: t.viewAction,
            onClick: () => nav({ name: "details", id: single.id }),
          },
        });
      } else if (importedNonBackupItems.length > 0) {
        success(t.importedBatchTitle(importedNonBackupItems.length), {
          description: t.importedBatchDesc(importedNonBackupItems.length),
        });
      } else if (restoredZipCount > 0) {
        success(t.restoredBackup(restoredZipCount), {
          description: t.restoreSuccessDesc(workingItems.length),
        });
      }

      const lockCandidateIds = importedNonBackupItems
        .filter((item) => !item.pinned && !item.isExpiryLocked)
        .map((item) => item.id);
      if (lockCandidateIds.length > 0) {
        onClipsAdded?.(lockCandidateIds);
      }
    } catch (err: any) {
      error(t.importFailed, {
        description: err?.message || t.importFailedDesc,
      });
    } finally {
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  return (
    <div className="flex h-[100dvh] max-h-[100dvh] w-full flex-col bg-background overflow-hidden">
      {/* Hidden file input for SAF document selection */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        accept=".txt,.md,.json,.zip,text/plain,text/*,application/zip,application/json"
        className="hidden"
        onChange={handleImportFile}
      />

      <TopBar
        title={<Wordmark />}
        right={
          <>
            <IconBtn
              label={dark ? t.switchToLight : t.switchToDark}
              onClick={onToggleTheme}
            >
              {dark ? <Sun className="h-4.5 w-4.5" /> : <Moon className="h-4.5 w-4.5" />}
            </IconBtn>
            <IconBtn label={t.settings} onClick={() => nav({ name: "settings" })}>
              <Settings className="h-4.5 w-4.5" />
            </IconBtn>
          </>
        }
      />

      <div className="shrink-0 space-y-2.5 px-4 pt-2.5 pb-1">
        {/* Full-width Instant Search Bar */}
        <div className="relative w-full" role="search">
          <label className="relative block w-full">
            <span className="sr-only">{t.searchSrLabel}</span>
            <Search className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="search"
              value={searchDraft}
              onChange={(e) => {
                const val = e.target.value;
                setSearchDraft(val);
                setQuery(val);
              }}
              placeholder={t.searchPlaceholder}
              className="h-10.5 w-full rounded-xl border border-border bg-surface ps-9.5 pe-22 text-xs sm:text-sm text-foreground outline-none placeholder:text-muted-foreground focus:border-mint focus:ring-2 focus:ring-mint/20 shadow-2xs transition-all"
            />
          </label>

          {/* Quick Clear & Matches Counter inside Search Bar */}
          {searchDraft && (
            <div className="absolute end-2 top-1/2 -translate-y-1/2 flex items-center gap-1.5">
              <span className="rounded-full bg-surface-2 border border-border px-1.5 py-0.5 text-[10px] font-bold text-muted-foreground">
                {t.foundBadge(visible.length)}
              </span>
              <button
                type="button"
                aria-label={t.clearSearch}
                onClick={() => {
                  setSearchDraft("");
                  setQuery("");
                }}
                className="grid h-6.5 w-6.5 place-items-center rounded-md text-muted-foreground hover:text-foreground hover:bg-accent tap-target transition-colors"
                title={t.clearSearch}
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>

        {/* Section Header with Stats and Sort Selector */}
        <div className="flex items-end justify-between gap-2 pt-0.5">
          <div className="min-w-0 text-start">
            <h1 className="text-sm sm:text-base font-bold tracking-tight text-foreground">
              {query ? t.searchTitle(query) : t.savedItemsTitle}
            </h1>
            <p className="text-[11px] text-muted-foreground">
              {query
                ? t.searchCountSubtitle(visible.length, items.length)
                : t.statsSubtitle(items.length, pinnedCount, formatBytes(totalBytes))}
            </p>
          </div>

          {/* Sort Selector */}
          <div className="relative">
            <label className="sr-only" htmlFor="sort-select">
              {t.sortByLabel}
            </label>
            <select
              id="sort-select"
              value={sort}
              onChange={(e) => setSort(e.target.value as SortKey)}
              aria-label={t.sortSelectorAria}
              className="h-8.5 appearance-none rounded-lg border border-border bg-surface ps-2.5 pe-7 text-[11px] font-medium outline-none focus:border-mint focus:ring-2 focus:ring-mint/20"
            >
              {(["modified", "newest", "oldest", "title"] as SortKey[]).map((k) => (
                <option key={k} value={k}>
                  {formatSortLabel(k, locale)}
                </option>
              ))}
            </select>
            <ChevronDown className="pointer-events-none absolute end-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          </div>
        </div>

        {/* Filter Chips */}
        <div className="flex items-center gap-1.5">
          <FilterChip active={!pinnedOnly} onClick={() => setPinnedOnly(false)}>
            {t.allFilter(items.length)}
          </FilterChip>
          <FilterChip active={pinnedOnly} onClick={() => setPinnedOnly(true)}>
            <Pin className="h-3 w-3" />
            {t.pinnedFilter(pinnedCount)}
          </FilterChip>
          {query && (
            <button
              type="button"
              onClick={() => {
                setSearchDraft("");
                setQuery("");
              }}
              className="ms-auto text-[11px] font-semibold text-mint hover:underline tap-target"
            >
              {t.resetFilter}
            </button>
          )}
        </div>

      </div>

      {/* Cards List (flex-1 scrollable area) */}
      <ul ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto space-y-2 px-4 py-2" role="list">
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
                <p className="font-semibold text-foreground">{t.emptyTitle}</p>
                <p className="text-xs text-muted-foreground">
                  {t.emptySubtitle}
                </p>
              </div>
            ) : (
              <div className="space-y-2 py-2">
                <Search className="h-7 w-7 text-muted-foreground/60 mx-auto" />
                <p className="font-bold text-foreground">{t.noMatchTitle(query)}</p>
                <p className="text-xs text-muted-foreground max-w-xs mx-auto">
                  {t.noMatchSubtitle}
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
                  {t.clearSearchFilterBtn}
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
          {t.importFile}
        </Btn>
        <Btn
          className="flex-1"
          variant="primary"
          onClick={() => nav({ name: "editor" })}
        >
          <Plus className="h-4 w-4" />
          {t.newItem}
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
  const locale = useAppLocale();
  const t = getUiText(locale).library;
  const preview = clip.content.replace(/\s+/g, " ").trim();
  const isCodeOrHash =
    /^(git|curl|docker|npm|const|class|function|\d{4}|[a-f0-9]{16,})/.test(
      clip.content.trim()
    );

  return (
    <button
      type="button"
      onClick={onOpen}
      className="group block w-full rounded-xl border border-border bg-surface px-4 py-3.5 text-start transition-colors hover:bg-surface-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <div className="flex items-center gap-2">
        <h3 dir="auto" className="min-w-0 flex-1 truncate text-[14px] font-semibold text-foreground group-hover:text-primary">
          <Highlight text={clip.title} q={query} />
        </h3>
        {clip.isExpiryLocked && (
          <span
            className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-border bg-surface-2 text-mint"
            title={t.protectedFromExpiry}
            aria-label={t.protectedFromExpiry}
          >
            <Lock className="h-2.5 w-2.5" aria-hidden />
          </span>
        )}
        {clip.pinned && (
          <span className="inline-flex items-center gap-1 rounded-full bg-mint-soft px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-mint-foreground">
            <Pin className="h-2.5 w-2.5" aria-hidden />
            {t.pinnedBadge}
          </span>
        )}
      </div>

      <p
        dir="auto"
        className={cn(
          "mt-1.5 line-clamp-2 text-[13px] leading-snug text-muted-foreground",
          isCodeOrHash && "font-mono text-[12px] text-foreground/85"
        )}
      >
        <Highlight text={preview || t.emptyTextFallback} q={query} truncateStart={true} />
      </p>

      <div className="mt-2.5 flex items-center gap-2 text-[11px] text-muted-foreground/80">
        <span>{relTime(clip.modifiedAt, locale)}</span>
        <span aria-hidden>·</span>
        <span className="font-mono">{t.charsCount(clip.characterCount)}</span>
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
