import React, { useState, useMemo, useRef } from "react";
import {
  ArrowLeft,
  Search,
  X,
  Pin,
  PinOff,
  Pencil,
  Trash2,
  Lock,
  Unlock,
  RotateCcw,
  Copy,
  Layers,
  ChevronDown,
  ClipboardList,
} from "lucide-react";
import { TopBar, Btn, IconBtn, ConfirmModal } from "./primitives";
import { ClipItem, Nav } from "../lib/types";
import { sortClips, filterClips, createClipItem } from "../lib/storage";
import { splitIntoChunks, formatBytes, DEFAULT_CHUNK_CHARS } from "../lib/chunker";
import { useToast } from "./ui/Toast";
import { cn } from "../lib/utils";
import { QwertyKeyboard } from "./QwertyKeyboard";

interface KeyboardPanelScreenProps {
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
  captureEnabled: boolean;
  nav: Nav;
}

export function KeyboardPanelScreen({
  items,
  onUpdateItems,
  captureEnabled,
  nav,
}: KeyboardPanelScreenProps) {
  const { toast, success, error } = useToast();

  // Host App State
  const [hostText, setHostText] = useState("Hey! Here are the details you requested: ");
  const [isPasswordField, setIsPasswordField] = useState(false);

  // Keyboard Preview State
  const [typingTarget, setTypingTarget] = useState<"host" | "search">("host");
  const [searchQuery, setSearchQuery] = useState("");
  const [pinnedOnly, setPinnedOnly] = useState(false);
  const [keyboardSize, setKeyboardSize] = useState<"compact" | "normal">("normal");
  const [showAllCardsSheet, setShowAllCardsSheet] = useState(false);
  const [expandedCardId, setExpandedCardId] = useState<string | null>(null);
  const [cardToDelete, setCardToDelete] = useState<ClipItem | null>(null);

  // Chunk Insertion State
  const [isInserting, setIsInserting] = useState(false);
  const [insertProgress, setInsertProgress] = useState<{
    currentChunk: number;
    totalChunks: number;
    itemTitle: string;
  } | null>(null);
  const cancelInsertionRef = useRef(false);

  // Filtered Clips
  const visible = useMemo(
    () => sortClips(filterClips(items, searchQuery, pinnedOnly), "modified"),
    [items, searchQuery, pinnedOnly]
  );

  // Insert text into Host App using 50,000 UTF-16 code units chunking
  const handleInsertClip = async (clip: ClipItem) => {
    if (isInserting) return;

    const chunks = splitIntoChunks(clip.content, DEFAULT_CHUNK_CHARS);
    if (chunks.length === 0) {
      toast("Clip has no content to insert");
      return;
    }

    setIsInserting(true);
    cancelInsertionRef.current = false;

    for (let i = 0; i < chunks.length; i++) {
      if (cancelInsertionRef.current) {
        toast(`Insertion cancelled at chunk ${i + 1} of ${chunks.length}`);
        break;
      }

      setInsertProgress({
        currentChunk: i + 1,
        totalChunks: chunks.length,
        itemTitle: clip.title,
      });

      // Commit chunk to host text
      setHostText((prev) => prev + chunks[i]);

      // If multiple chunks (large payload), pause slightly to visually demonstrate bounded chunks
      if (chunks.length > 1) {
        await new Promise((res) => setTimeout(res, 200));
      }
    }

    setIsInserting(false);
    setInsertProgress(null);
    if (!cancelInsertionRef.current) {
      success(`Inserted "${clip.title}"`, {
        description: `${clip.characterCount.toLocaleString()} code units (${chunks.length} ${chunks.length === 1 ? "chunk" : "chunks"}).`,
      });
    }
  };

  const handleCancelInsertion = () => {
    cancelInsertionRef.current = true;
  };

  // Simulate Copy Event & Automatic Capture into 50k numbered cards
  const handleSimulateCopyCapture = () => {
    if (isPasswordField) {
      error("Capture suppressed", {
        description: "Password fields block automatic capture to protect sensitive credentials.",
      });
      return;
    }

    if (!captureEnabled) {
      toast("Automatic capture is turned off in Settings");
      return;
    }

    const sampleLargeCopied =
      "ClipMax automated clipboard capture test text.\n".repeat(1500); // ~70,000 chars -> 2 chunks

    const chunks = splitIntoChunks(sampleLargeCopied, DEFAULT_CHUNK_CHARS);
    const newItems: ClipItem[] = [];

    const baseTitle = "Captured Selection";
    for (let i = 0; i < chunks.length; i++) {
      const cardTitle =
        chunks.length > 1
          ? `${baseTitle} (Chunk ${String(i + 1).padStart(3, "0")}/${String(chunks.length).padStart(3, "0")})`
          : baseTitle;
      const card = createClipItem(cardTitle, chunks[i], false);
      newItems.push(card);
    }

    onUpdateItems([...newItems, ...items]);
    success(`Captured ${sampleLargeCopied.length.toLocaleString()} characters`, {
      description: `Saved as ${chunks.length} serially numbered cards (50k units/card).`,
    });
  };

  // Toggle Pin on a Card
  const handleTogglePin = (clip: ClipItem) => {
    const updated = items.map((c) =>
      c.id === clip.id ? { ...c, pinned: !c.pinned, modifiedAt: Date.now() } : c
    );
    onUpdateItems(updated);
    toast(clip.pinned ? "Card unpinned" : "Card pinned");
  };

  // Delete Card
  const handleDeleteCard = () => {
    if (!cardToDelete) return;
    onUpdateItems(items.filter((c) => c.id !== cardToDelete.id));
    setCardToDelete(null);
    toast("Card removed");
  };

  // QWERTY Keyboard Action Handlers
  const handleKeyPress = (char: string) => {
    if (typingTarget === "search") {
      setSearchQuery((prev) => prev + char);
    } else {
      setHostText((prev) => prev + char);
    }
  };

  const handleBackspace = () => {
    if (typingTarget === "search") {
      setSearchQuery((prev) => prev.slice(0, -1));
    } else {
      setHostText((prev) => prev.slice(0, -1));
    }
  };

  const handleSpace = () => {
    if (typingTarget === "search") {
      setSearchQuery((prev) => prev + " ");
    } else {
      setHostText((prev) => prev + " ");
    }
  };

  const handleEnter = () => {
    if (typingTarget === "search") {
      toast(searchQuery ? `Filtering: "${searchQuery}"` : "Search active");
    } else {
      setHostText((prev) => prev + "\n");
    }
  };

  return (
    <div className="flex h-full max-h-full w-full flex-col bg-background overflow-hidden select-none font-sans">
      <TopBar
        left={
          <IconBtn label="Back" onClick={() => nav({ name: "library" })}>
            <ArrowLeft className="h-5 w-5" />
          </IconBtn>
        }
        title="ClipMax Keyboard Preview"
        subtitle="Standard Gboard-inspired portrait QWERTY arrangement"
        right={
          <button
            type="button"
            onClick={() =>
              setKeyboardSize((v) => (v === "compact" ? "normal" : "compact"))
            }
            className="inline-flex h-7 items-center rounded-md border border-border bg-surface px-2 text-[11px] font-semibold text-muted-foreground hover:text-foreground hover:bg-accent cursor-pointer"
          >
            {keyboardSize === "compact" ? "Compact" : "Normal"}
          </button>
        }
      />

      {/* Host App Simulation Area (Flexibly bounded, never forces page scroll) */}
      <div className="flex-1 min-h-[100px] max-h-[160px] sm:max-h-[200px] flex flex-col p-2.5 sm:p-3 overflow-hidden bg-background shrink-0">
        <div className="flex items-center justify-between shrink-0 pb-1.5">
          <div className="flex items-center gap-2">
            <span className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
              Host App · Active Text Field
            </span>
            <span
              className={cn(
                "inline-flex items-center rounded px-1.5 py-0.5 text-[9px] font-extrabold uppercase tracking-wide",
                typingTarget === "host"
                  ? "bg-mint/15 text-[#0F766E] dark:text-mint"
                  : "bg-surface-2 text-muted-foreground"
              )}
            >
              {typingTarget === "host" ? "Keyboard Focus" : "Unfocused"}
            </span>
          </div>

          <button
            type="button"
            onClick={() => setIsPasswordField((v) => !v)}
            className={cn(
              "inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-semibold transition-colors cursor-pointer",
              isPasswordField
                ? "bg-destructive/15 text-destructive font-bold border border-destructive/30"
                : "bg-surface text-muted-foreground border border-border hover:bg-accent"
            )}
          >
            {isPasswordField ? <Lock className="h-3 w-3" /> : <Unlock className="h-3 w-3" />}
            {isPasswordField ? "Password Field" : "Normal Field"}
          </button>
        </div>

        {/* Host Input Area */}
        <div
          onClick={() => setTypingTarget("host")}
          className={cn(
            "relative flex-1 min-h-[50px] rounded-xl border p-2 text-sm shadow-2xs overflow-y-auto cursor-text transition-colors",
            typingTarget === "host"
              ? "border-mint/60 bg-surface ring-1 ring-mint/20"
              : "border-border bg-surface hover:border-border/80"
          )}
        >
          <label className="sr-only" htmlFor="host-editor-field">
            Host editable field
          </label>
          {isPasswordField ? (
            <input
              id="host-editor-field"
              type="password"
              value={hostText}
              onChange={(e) => setHostText(e.target.value)}
              placeholder="Type password..."
              className="w-full bg-transparent font-mono font-medium outline-none text-foreground placeholder:text-muted-foreground text-xs sm:text-sm"
            />
          ) : (
            <textarea
              id="host-editor-field"
              value={hostText}
              onChange={(e) => setHostText(e.target.value)}
              placeholder="Type using the keyboard below, or tap a clip to insert..."
              className="w-full h-full resize-none bg-transparent outline-none text-foreground placeholder:text-muted-foreground leading-relaxed text-xs sm:text-sm"
            />
          )}
          <span className="inline-block h-3.5 w-0.5 translate-y-0.5 animate-pulse bg-mint ml-0.5" />
        </div>

        {/* Host Controls */}
        <div className="flex items-center justify-between shrink-0 pt-1">
          <Btn
            size="sm"
            variant="ghost"
            onClick={() => setHostText("")}
            className="h-6.5 px-2 text-[11px] text-muted-foreground font-semibold"
          >
            <RotateCcw className="h-3 w-3" />
            Clear Host Text
          </Btn>

          <div className="flex items-center gap-1.5">
            <span className="text-[10px] text-muted-foreground font-mono">
              {hostText.length.toLocaleString()} chars
            </span>
            <Btn
              size="sm"
              variant="outline"
              onClick={handleSimulateCopyCapture}
              className="h-6.5 px-2 text-[11px] font-semibold"
              title="Simulates clipboard copy to test automatic 50k card chunking"
            >
              <Copy className="h-3 w-3 text-mint" />
              Simulate 70k Copy
            </Btn>
          </div>
        </div>
      </div>

      {/* Floating Insertion Progress Banner */}
      {isInserting && insertProgress && (
        <div className="shrink-0 flex items-center justify-between border-t border-mint/40 bg-mint-soft px-3 py-1 text-[11px] font-bold text-mint-foreground">
          <span className="truncate pr-2">
            Inserting <strong>{insertProgress.itemTitle}</strong> — Chunk {insertProgress.currentChunk}/{insertProgress.totalChunks} (50k units/chunk)
          </span>
          <button
            type="button"
            onClick={handleCancelInsertion}
            className="rounded bg-destructive px-2 py-0.5 text-[10px] font-bold text-destructive-foreground shadow-xs hover:opacity-90 cursor-pointer shrink-0"
          >
            Cancel
          </button>
        </div>
      )}

      {/* ============================================================== */}
      {/* KEYBOARD PREVIEW CONTAINER (Theme-Aware, Strictly Bounded)     */}
      {/* ============================================================== */}
      <div
        className="w-full flex flex-col border-t border-border bg-[#ECEFF1] dark:bg-[#151C18] shadow-2xl overflow-hidden box-border shrink-0 mt-auto"
        role="region"
        aria-label="ClipMax Keyboard Preview"
      >
        {/* 1. CLIPMAX SAVED-CLIP TOOLBAR (Visibly distinct, No mystery 'C' button) */}
        <div className="flex h-8.5 shrink-0 items-center justify-between border-b border-[#D5DED8] dark:border-[#24352D] bg-[#F4F7F5] dark:bg-[#18231E] px-2 w-full max-w-full box-border gap-1.5 overflow-hidden">
          {/* Left: App Identity & Pinned Filter */}
          <div className="flex items-center gap-1.5 shrink-0">
            <div className="flex items-center gap-1 text-[11px] font-bold tracking-tight text-foreground">
              <ClipboardList className="h-3.5 w-3.5 text-[#0D9488] dark:text-mint" />
              <span>ClipMax</span>
            </div>

            <button
              type="button"
              onClick={() => setPinnedOnly((v) => !v)}
              className={cn(
                "inline-flex h-6 items-center gap-1 rounded-md border px-1.5 text-[10px] font-bold cursor-pointer shrink-0 transition-colors",
                pinnedOnly
                  ? "border-[#0D9488] dark:border-[#2DD4BF] bg-mint/15 text-[#0F766E] dark:text-mint"
                  : "border-[#D1D9D4] dark:border-[#2B3D35] bg-white dark:bg-[#202E28] text-muted-foreground hover:text-foreground"
              )}
              title="Toggle Pinned Clips"
            >
              <Pin className="h-2.5 w-2.5 shrink-0" />
              <span>Pinned</span>
            </button>
          </div>

          {/* Center: Search Filter Query Pill */}
          <div
            onClick={() => setTypingTarget("search")}
            className={cn(
              "flex-1 min-w-[90px] max-w-[190px] h-6 rounded-md border px-2 flex items-center gap-1 cursor-pointer transition-colors box-border",
              typingTarget === "search"
                ? "border-[#0D9488] dark:border-[#2DD4BF] bg-white dark:bg-[#202E28] text-foreground ring-1 ring-mint/30"
                : "border-[#D1D9D4] dark:border-[#2B3D35] bg-white/70 dark:bg-[#1A2520] text-muted-foreground hover:border-foreground/30"
            )}
            title="Click to type search query using keyboard"
          >
            <Search className="h-2.5 w-2.5 text-[#0D9488] dark:text-mint shrink-0" />
            <span className="text-[10px] font-medium truncate flex-1">
              {searchQuery || "Search clips..."}
            </span>
            {searchQuery && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setSearchQuery("");
                }}
                className="text-muted-foreground hover:text-foreground p-0.5 cursor-pointer"
                title="Clear Search Query"
              >
                <X className="h-2.5 w-2.5" />
              </button>
            )}
          </div>

          {/* Right: Typing Target Switcher & Cards Sheet Button */}
          <div className="flex items-center gap-1 shrink-0">
            <button
              type="button"
              onClick={() =>
                setTypingTarget((v) => (v === "host" ? "search" : "host"))
              }
              className={cn(
                "inline-flex h-6 items-center gap-1 rounded-md border px-1.5 text-[10px] font-bold cursor-pointer shrink-0 transition-colors",
                typingTarget === "search"
                  ? "border-[#0D9488] dark:border-[#2DD4BF] bg-[#0D9488] dark:bg-mint text-white dark:text-[#0A1F18]"
                  : "border-[#D1D9D4] dark:border-[#2B3D35] bg-white dark:bg-[#202E28] text-foreground hover:bg-accent"
              )}
              title="Switch whether keyboard types into Host App or Filters clips"
            >
              <span>{typingTarget === "host" ? "Target: Host" : "Target: Filter"}</span>
            </button>

            <button
              type="button"
              onClick={() => setShowAllCardsSheet(true)}
              className="inline-flex h-6 items-center gap-1 rounded-md border border-[#D1D9D4] dark:border-[#2B3D35] bg-white dark:bg-[#202E28] px-1.5 text-[10px] font-semibold text-muted-foreground hover:text-foreground cursor-pointer shrink-0"
              title="Browse all saved cards"
            >
              <Layers className="h-3 w-3" />
              <span className="hidden sm:inline">Cards ({items.length})</span>
            </button>
          </div>
        </div>

        {/* 2. COMPACT SAVED-CLIP STRIP (Clean Scannable Cards, Height ~30px) */}
        <div className="h-7.5 shrink-0 flex items-center gap-1.5 overflow-x-auto no-scrollbar py-0.5 px-2 bg-[#E9EDE9] dark:bg-[#131B17] border-b border-[#D5DED8] dark:border-[#202D26] w-full box-border">
          {isPasswordField ? (
            <span className="text-[10px] text-rose-500 font-semibold flex items-center gap-1">
              <Lock className="h-3 w-3" /> Password Field Active — Saved clips withheld for privacy
            </span>
          ) : visible.length === 0 ? (
            <span className="text-[10px] text-muted-foreground italic truncate">
              {searchQuery
                ? `No clips matching "${searchQuery}". Tap '✕' to clear.`
                : "No saved clips. Tap 'Simulate Copy' above to create cards."}
            </span>
          ) : (
            visible.map((clip) => {
              const preview = clip.content.replace(/\s+/g, " ").trim();
              return (
                <button
                  key={clip.id}
                  type="button"
                  onClick={() => handleInsertClip(clip)}
                  className="inline-flex h-6 shrink-0 items-center gap-1 rounded-[5px] border border-[#D1D9D4] dark:border-[#2C3D35] bg-white dark:bg-[#1E2C26] px-2 text-[10px] font-medium text-foreground shadow-2xs hover:border-mint hover:bg-mint-soft dark:hover:bg-[#263A31] active:scale-[0.97] transition-all cursor-pointer"
                  title={`Insert "${clip.title}": ${preview.slice(0, 80)}...`}
                >
                  {clip.pinned && <Pin className="h-2 w-2 text-[#0D9488] dark:text-mint" />}
                  <span className="truncate max-w-[110px] font-semibold">{clip.title}</span>
                  <span className="text-muted-foreground truncate max-w-[90px] font-mono text-[9px] hidden xs:inline">
                    · {preview || "Empty"}
                  </span>
                  <span className="font-mono text-[8px] text-muted-foreground bg-muted/40 dark:bg-[#16211C] px-1 py-0.2 rounded shrink-0">
                    {formatBytes(clip.sizeBytes)}
                  </span>
                </button>
              );
            })
          )}
        </div>

        {/* 3. RESPONSIVE GBOARD-INSPIRED QWERTY KEYBOARD (NO OVERFLOW) */}
        <QwertyKeyboard
          onKeyPress={handleKeyPress}
          onBackspace={handleBackspace}
          onSpace={handleSpace}
          onEnter={handleEnter}
          size={keyboardSize}
        />
      </div>

      {/* ============================================================== */}
      {/* BROWSE ALL CARDS MODAL SHEET                                  */}
      {/* ============================================================== */}
      {showAllCardsSheet && (
        <div className="fixed inset-0 z-50 flex flex-col justify-end bg-black/50 backdrop-blur-xs animate-in fade-in">
          <div className="w-full max-h-[75vh] flex flex-col rounded-t-2xl border-t border-border bg-background shadow-2xl overflow-hidden animate-in slide-in-from-bottom duration-200">
            {/* Sheet Header */}
            <div className="flex h-11 items-center justify-between border-b border-border px-4 shrink-0 bg-surface">
              <div className="flex items-center gap-2">
                <ClipboardList className="h-4 w-4 text-[#0D9488] dark:text-mint" />
                <h3 className="font-bold text-sm text-foreground">
                  Saved ClipMax Cards ({items.length})
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setShowAllCardsSheet(false)}
                className="rounded-full p-1 text-muted-foreground hover:bg-accent cursor-pointer"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* Sheet List */}
            <div className="flex-1 overflow-y-auto p-3 space-y-2">
              {items.length === 0 ? (
                <div className="p-8 text-center text-xs text-muted-foreground">
                  No saved clips available. Copy text to create serially numbered 50,000-character cards.
                </div>
              ) : (
                items.map((clip) => {
                  const isExpanded = expandedCardId === clip.id;
                  const preview = clip.content.replace(/\s+/g, " ").trim();

                  return (
                    <div
                      key={clip.id}
                      className="rounded-xl border border-border bg-surface p-2.5 transition-colors shadow-2xs"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <button
                          type="button"
                          onClick={() => {
                            handleInsertClip(clip);
                            setShowAllCardsSheet(false);
                          }}
                          className="flex-1 min-w-0 text-left select-none cursor-pointer"
                        >
                          <div className="flex items-center gap-1.5">
                            <span className="font-bold text-xs text-foreground truncate">
                              {clip.title}
                            </span>
                            {clip.pinned && (
                              <Pin className="h-2.5 w-2.5 text-[#0D9488] dark:text-mint shrink-0" />
                            )}
                          </div>
                          <p className="mt-0.5 line-clamp-2 text-[11px] text-muted-foreground font-mono">
                            {preview || "Empty"}
                          </p>
                          <div className="mt-1 text-[10px] text-muted-foreground font-mono">
                            {clip.characterCount.toLocaleString()} chars · {formatBytes(clip.sizeBytes)}
                          </div>
                        </button>

                        <button
                          type="button"
                          onClick={() =>
                            setExpandedCardId(isExpanded ? null : clip.id)
                          }
                          className={cn(
                            "rounded-md p-1 text-muted-foreground hover:bg-accent cursor-pointer shrink-0",
                            isExpanded && "bg-accent text-foreground"
                          )}
                        >
                          <ChevronDown
                            className={cn(
                              "h-4 w-4 transition-transform",
                              isExpanded && "rotate-180"
                            )}
                          />
                        </button>
                      </div>

                      {isExpanded && (
                        <div className="mt-2 flex items-center gap-2 border-t border-border pt-2 animate-in fade-in">
                          <Btn
                            size="sm"
                            variant="primary"
                            onClick={() => {
                              handleInsertClip(clip);
                              setShowAllCardsSheet(false);
                            }}
                            className="h-6.5 px-3 text-[11px] font-bold bg-[#0D9488] dark:bg-mint text-white dark:text-[#0A1F18]"
                          >
                            Insert ↵
                          </Btn>
                          <Btn
                            size="sm"
                            variant="ghost"
                            onClick={() => nav({ name: "editor", id: clip.id })}
                            className="h-6.5 px-2 text-[11px] font-semibold"
                          >
                            <Pencil className="h-3 w-3" />
                            Edit
                          </Btn>
                          <Btn
                            size="sm"
                            variant="ghost"
                            onClick={() => handleTogglePin(clip)}
                            className="h-6.5 px-2 text-[11px] font-semibold"
                          >
                            {clip.pinned ? (
                              <PinOff className="h-3 w-3" />
                            ) : (
                              <Pin className="h-3 w-3" />
                            )}
                            {clip.pinned ? "Unpin" : "Pin"}
                          </Btn>
                          <Btn
                            size="sm"
                            variant="dangerOutline"
                            onClick={() => setCardToDelete(clip)}
                            className="h-6.5 px-2 text-[11px] font-semibold ml-auto"
                          >
                            <Trash2 className="h-3 w-3" />
                            Delete
                          </Btn>
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      )}

      {/* Confirm Delete Modal */}
      <ConfirmModal
        open={Boolean(cardToDelete)}
        title="Delete card from keyboard?"
        description={`"${cardToDelete?.title}" will be permanently removed.`}
        confirmLabel="Delete"
        destructive={true}
        onConfirm={handleDeleteCard}
        onClose={() => setCardToDelete(null)}
      />
    </div>
  );
}
