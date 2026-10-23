import React, { useState, useMemo, useRef } from "react";
import {
  ArrowLeft,
  Search,
  X,
  Pin,
  PinOff,
  Pencil,
  Trash2,
  MoreHorizontal,
  Lock,
  Unlock,
  Delete,
  RotateCcw,
  Copy,
  ChevronDown,
} from "lucide-react";
import { TopBar, Btn, IconBtn, ConfirmModal } from "./primitives";
import { ClipItem, Nav } from "../lib/types";
import { sortClips, filterClips, createClipItem } from "../lib/storage";
import { splitIntoChunks, formatBytes, DEFAULT_CHUNK_CHARS } from "../lib/chunker";
import { useToast } from "./ui/Toast";
import { cn } from "../lib/utils";

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

  // Keyboard Panel State
  const [searchKeypadOpen, setSearchKeypadOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [numericMode, setNumericMode] = useState(false);
  const [capsLock, setCapsLock] = useState(true);
  const [pinnedOnly, setPinnedOnly] = useState(false);
  const [keyboardSize, setKeyboardSize] = useState<"compact" | "normal">("compact");
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

  // Filtered Items
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

      // If multiple chunks (large payload), pause slightly to visually demonstrate bounded chunks and allow cancellation
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

  // Simulate Copy Event & Automatic Capture into numbered cards
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

  // Keypad definitions with bold, thicker characters
  const alphaRow1 = ["Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"];
  const alphaRow2 = ["A", "S", "D", "F", "G", "H", "J", "K", "L"];
  const alphaRow3 = ["Z", "X", "C", "V", "B", "N", "M"];

  const numRow1 = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"];
  const numRow2 = ["-", "/", ":", ";", "(", ")", "$", "&", "@", "\""];
  const numRow3 = [".", ",", "?", "!", "'", "_", "#"];

  const handleKeyPress = (char: string) => {
    const letter = capsLock ? char.toUpperCase() : char.toLowerCase();
    setSearchQuery((prev) => prev + letter);
  };

  return (
    <div className="flex h-full max-h-full w-full flex-col bg-background overflow-hidden select-none">
      <TopBar
        left={
          <IconBtn label="Back" onClick={() => nav({ name: "library" })}>
            <ArrowLeft className="h-5 w-5" />
          </IconBtn>
        }
        title="Android Keyboard (IME)"
        subtitle="Simulates native ClipMaxImeService in active host application"
      />

      {/* Host App Simulation Area (Flexibly shrinks/grows, never forces page scroll) */}
      <div className="flex-1 min-h-0 flex flex-col p-2.5 sm:p-3 overflow-hidden">
        <div className="flex items-center justify-between shrink-0 pb-1">
          <span className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">
            Host App · Messages / Notes
          </span>
          <button
            type="button"
            onClick={() => setIsPasswordField((v) => !v)}
            className={cn(
              "inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-semibold transition-colors tap-target",
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
        <div className="relative flex-1 min-h-[50px] rounded-xl border border-border bg-surface p-2.5 text-sm shadow-2xs overflow-y-auto">
          <label className="sr-only" htmlFor="host-editor-field">
            Host editable field
          </label>
          {isPasswordField ? (
            <input
              id="host-editor-field"
              type="password"
              value={hostText}
              onChange={(e) => setHostText(e.target.value)}
              placeholder="Password input..."
              className="w-full bg-transparent font-mono font-medium outline-none text-foreground placeholder:text-muted-foreground text-xs sm:text-sm"
            />
          ) : (
            <textarea
              id="host-editor-field"
              value={hostText}
              onChange={(e) => setHostText(e.target.value)}
              placeholder="Host application text field..."
              className="w-full h-full resize-none bg-transparent outline-none text-foreground placeholder:text-muted-foreground leading-relaxed text-xs sm:text-sm"
            />
          )}
          <span className="inline-block h-3.5 w-0.5 translate-y-0.5 animate-pulse bg-mint ml-0.5" />
        </div>

        {/* Host Controls */}
        <div className="flex items-center justify-between shrink-0 pt-1.5">
          <Btn
            size="sm"
            variant="ghost"
            onClick={() => setHostText("")}
            className="h-7 px-2 text-[11px] text-muted-foreground font-semibold"
          >
            <RotateCcw className="h-3 w-3" />
            Clear
          </Btn>
          <Btn
            size="sm"
            variant="outline"
            onClick={handleSimulateCopyCapture}
            className="h-7 px-2.5 text-[11px] font-semibold"
            title="Simulates clipboard copy to test automatic card chunking"
          >
            <Copy className="h-3 w-3 text-mint" />
            Simulate Copy
          </Btn>
        </div>
      </div>

      {/* Floating Insertion Progress Banner */}
      {isInserting && insertProgress && (
        <div className="shrink-0 flex items-center justify-between border-t border-mint/40 bg-mint-soft px-3 py-1.5 text-[11px] font-bold text-mint-foreground">
          <span className="truncate pr-2">
            Inserting <strong>{insertProgress.itemTitle}</strong> — Chunk {insertProgress.currentChunk}/{insertProgress.totalChunks} (50k units/chunk)
          </span>
          <button
            type="button"
            onClick={handleCancelInsertion}
            className="rounded bg-destructive px-2 py-0.5 text-[10px] font-bold text-destructive-foreground shadow-xs hover:opacity-90 tap-target shrink-0"
          >
            Cancel
          </button>
        </div>
      )}

      {/* Keyboard Panel (Docked at bottom, strictly contained within display) */}
      <div
        className="shrink-0 w-full flex flex-col border-t border-border bg-surface-2 shadow-2xl overflow-hidden"
        role="region"
        aria-label="ClipMax Input Method View"
      >
        {/* IME Header Bar */}
        <div className="flex h-8 shrink-0 items-center justify-between border-b border-border bg-surface px-2 sm:px-3">
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => setSearchKeypadOpen((v) => !v)}
              className={cn(
                "inline-flex h-6.5 items-center gap-1.5 rounded-md border px-2 text-[10px] sm:text-[11px] font-bold transition-colors tap-target",
                searchKeypadOpen || searchQuery
                  ? "border-mint bg-mint-soft text-mint-foreground"
                  : "border-border bg-surface text-foreground hover:bg-accent"
              )}
            >
              <Search className="h-3 w-3" />
              {searchKeypadOpen ? "Hide Keypad" : searchQuery ? `"${searchQuery}"` : "Search Keypad"}
            </button>

            {searchQuery && (
              <button
                type="button"
                onClick={() => setSearchQuery("")}
                className="grid h-6 w-6 place-items-center rounded text-muted-foreground hover:bg-accent tap-target"
                title="Clear query"
              >
                <X className="h-3 w-3" />
              </button>
            )}

            <button
              type="button"
              onClick={() => setPinnedOnly((v) => !v)}
              className={cn(
                "inline-flex h-6.5 items-center gap-1 rounded-md border px-1.5 text-[10px] sm:text-[11px] font-bold tap-target",
                pinnedOnly
                  ? "border-mint bg-mint-soft text-mint-foreground"
                  : "border-border bg-surface text-muted-foreground hover:bg-accent"
              )}
            >
              <Pin className="h-2.5 w-2.5" />
              Pinned
            </button>
          </div>

          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() =>
                setKeyboardSize((v) => (v === "compact" ? "normal" : "compact"))
              }
              className="inline-flex h-6.5 items-center rounded-md border border-border bg-surface px-1.5 text-[10px] font-semibold text-muted-foreground hover:text-foreground hover:bg-accent tap-target"
              title="Toggle Keyboard Size"
            >
              {keyboardSize === "compact" ? "Compact Size" : "Standard Size"}
            </button>
            <span className="text-[9px] font-extrabold tracking-wider text-muted-foreground uppercase hidden xs:inline">
              ClipMax IME
            </span>
          </div>
        </div>

        {/* View Mode 1: Search Keypad Active */}
        {searchKeypadOpen ? (
          <div className="shrink-0 flex flex-col bg-background px-2 sm:px-3 py-1.5 space-y-1 overflow-hidden box-border max-w-md mx-auto w-full">
            {/* Query bar + Live suggestions strip */}
            <div className="flex items-center justify-between px-1 text-[10px] shrink-0">
              <span className="font-mono text-muted-foreground truncate">
                Query: <strong className="text-foreground font-bold">{searchQuery || "(type to search)"}</strong>
              </span>
              <button
                type="button"
                onClick={() => setSearchKeypadOpen(false)}
                className="text-[10px] font-bold text-mint hover:underline pl-2 shrink-0 flex items-center gap-0.5"
              >
                Done <ChevronDown className="h-3 w-3" />
              </button>
            </div>

            {/* Quick Matched Chips Strip (Above Keypad, allowing one-tap insert while typing) */}
            <div className="h-7 shrink-0 flex items-center gap-1 overflow-x-auto no-scrollbar py-0.5 px-0.5">
              {isPasswordField ? (
                <span className="text-[10px] text-destructive font-semibold flex items-center gap-1">
                  <Lock className="h-3 w-3" /> Password Field Active — Previews Withheld
                </span>
              ) : visible.length === 0 ? (
                <span className="text-[10px] text-muted-foreground italic">
                  {searchQuery ? "No matching clips" : "Type keys below to search saved snippets"}
                </span>
              ) : (
                visible.map((clip) => (
                  <button
                    key={clip.id}
                    type="button"
                    onClick={() => handleInsertClip(clip)}
                    className="inline-flex h-6 shrink-0 items-center gap-1 rounded border border-border bg-surface px-2 text-[10px] font-bold text-foreground shadow-2xs hover:bg-mint-soft hover:text-mint-foreground transition-colors tap-target"
                    title={`Insert "${clip.title}" (${clip.characterCount} chars)`}
                  >
                    {clip.pinned && <Pin className="h-2 w-2 text-mint" />}
                    <span className="truncate max-w-[120px]">{clip.title}</span>
                    <span className="font-mono text-[8px] text-muted-foreground">
                      {formatBytes(clip.sizeBytes)}
                    </span>
                  </button>
                ))
              )}
            </div>

            {/* Virtual Keypad Rows: Sleek, Compact, Contained Flex Width */}
            <div className="w-full space-y-1 select-none box-border" role="group" aria-label="Search Keypad">
              {/* Row 1: 10 Keys (Q W E R T Y U I O P) */}
              <div className="flex w-full gap-0.5 sm:gap-1">
                {(numericMode ? numRow1 : alphaRow1).map((k) => (
                  <button
                    key={k}
                    type="button"
                    onClick={() => handleKeyPress(k)}
                    className={cn(
                      "flex-1 min-w-0 rounded border border-border/80 bg-surface text-foreground font-bold shadow-2xs hover:bg-accent active:bg-mint-soft active:text-mint-foreground flex items-center justify-center tap-target transition-transform active:scale-95",
                      keyboardSize === "compact"
                        ? "h-8 sm:h-8.5 text-[13px] sm:text-[14px]"
                        : "h-9 sm:h-9.5 text-sm sm:text-base"
                    )}
                  >
                    {capsLock ? k.toUpperCase() : k.toLowerCase()}
                  </button>
                ))}
              </div>

              {/* Row 2: 9 Keys with proportional centering (A S D F G H J K L) */}
              <div className="flex w-full gap-0.5 sm:gap-1 px-[4%]">
                {(numericMode ? numRow2 : alphaRow2).map((k) => (
                  <button
                    key={k}
                    type="button"
                    onClick={() => handleKeyPress(k)}
                    className={cn(
                      "flex-1 min-w-0 rounded border border-border/80 bg-surface text-foreground font-bold shadow-2xs hover:bg-accent active:bg-mint-soft active:text-mint-foreground flex items-center justify-center tap-target transition-transform active:scale-95",
                      keyboardSize === "compact"
                        ? "h-8 sm:h-8.5 text-[13px] sm:text-[14px]"
                        : "h-9 sm:h-9.5 text-sm sm:text-base"
                    )}
                  >
                    {capsLock ? k.toUpperCase() : k.toLowerCase()}
                  </button>
                ))}
              </div>

              {/* Row 3: Shift / 7 Keys / Backspace */}
              <div className="flex w-full gap-0.5 sm:gap-1">
                <button
                  type="button"
                  onClick={() => setCapsLock((v) => !v)}
                  className={cn(
                    "flex-[1.25] min-w-0 max-w-[40px] rounded border border-border/80 text-xs font-bold flex items-center justify-center tap-target shadow-2xs transition-colors",
                    keyboardSize === "compact" ? "h-8 sm:h-8.5" : "h-9 sm:h-9.5",
                    capsLock ? "bg-mint text-primary-foreground dark:text-background" : "bg-surface-2 text-foreground hover:bg-accent"
                  )}
                  title="Caps Lock"
                >
                  ⇧
                </button>

                {(numericMode ? numRow3 : alphaRow3).map((k) => (
                  <button
                    key={k}
                    type="button"
                    onClick={() => handleKeyPress(k)}
                    className={cn(
                      "flex-1 min-w-0 rounded border border-border/80 bg-surface text-foreground font-bold shadow-2xs hover:bg-accent active:bg-mint-soft active:text-mint-foreground flex items-center justify-center tap-target transition-transform active:scale-95",
                      keyboardSize === "compact"
                        ? "h-8 sm:h-8.5 text-[13px] sm:text-[14px]"
                        : "h-9 sm:h-9.5 text-sm sm:text-base"
                    )}
                  >
                    {capsLock ? k.toUpperCase() : k.toLowerCase()}
                  </button>
                ))}

                <button
                  type="button"
                  onClick={() => setSearchQuery((prev) => prev.slice(0, -1))}
                  className={cn(
                    "flex-[1.25] min-w-0 max-w-[40px] rounded border border-border/80 bg-surface-2 text-foreground font-bold flex items-center justify-center tap-target shadow-2xs hover:bg-accent active:bg-destructive/15 active:text-destructive",
                    keyboardSize === "compact" ? "h-8 sm:h-8.5" : "h-9 sm:h-9.5"
                  )}
                  title="Backspace"
                >
                  <Delete className="h-4 w-4" />
                </button>
              </div>

              {/* Row 4: Mode Switch / Spacebar / Clear Query */}
              <div className="flex w-full gap-0.5 sm:gap-1 pt-0.5">
                <button
                  type="button"
                  onClick={() => setNumericMode((v) => !v)}
                  className={cn(
                    "flex-[1.25] min-w-0 max-w-[48px] rounded border border-border/80 bg-surface-2 text-foreground font-bold text-[11px] tap-target shadow-2xs hover:bg-accent",
                    keyboardSize === "compact" ? "h-7.5 sm:h-8" : "h-8.5 sm:h-9"
                  )}
                >
                  {numericMode ? "ABC" : "?123"}
                </button>
                <button
                  type="button"
                  onClick={() => setSearchQuery((prev) => prev + " ")}
                  className={cn(
                    "flex-[4] min-w-0 rounded border border-border/80 bg-surface text-foreground font-semibold text-xs tap-target shadow-2xs hover:bg-accent active:bg-mint-soft",
                    keyboardSize === "compact" ? "h-7.5 sm:h-8" : "h-8.5 sm:h-9"
                  )}
                >
                  Space
                </button>
                <button
                  type="button"
                  onClick={() => setSearchQuery("")}
                  disabled={!searchQuery}
                  className={cn(
                    "flex-[1.25] min-w-0 max-w-[48px] rounded border border-border/80 bg-surface-2 text-foreground font-bold text-[11px] tap-target shadow-2xs disabled:opacity-30 hover:bg-accent",
                    keyboardSize === "compact" ? "h-7.5 sm:h-8" : "h-8.5 sm:h-9"
                  )}
                >
                  Clear
                </button>
              </div>
            </div>
          </div>
        ) : (
          /* View Mode 2: Full Scrollable Cards List */
          <div className="h-44 sm:h-52 overflow-y-auto p-2 space-y-1.5 max-w-md mx-auto w-full">
            {isPasswordField ? (
              <div className="flex h-full flex-col items-center justify-center p-4 text-center text-xs text-muted-foreground">
                <Lock className="h-7 w-7 text-destructive/70 mb-1.5" />
                <p className="font-bold text-foreground">Password Field Focused</p>
                <p className="mt-1 max-w-xs text-[11px] text-muted-foreground leading-relaxed">
                  ClipMax suppresses card previews and blocks clipboard capture in password inputs to safeguard credentials.
                </p>
              </div>
            ) : visible.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center p-4 text-center text-xs text-muted-foreground space-y-1">
                <p className="font-bold text-foreground">
                  {searchQuery ? `No cards match "${searchQuery}"` : "No saved items available"}
                </p>
                <p className="text-[11px]">
                  {searchQuery ? "Tap 'Clear' or search another term." : "Create items in the library to insert them here."}
                </p>
              </div>
            ) : (
              visible.map((clip) => {
                const isExpanded = expandedCardId === clip.id;
                const preview = clip.content.replace(/\s+/g, " ").trim();

                return (
                  <div
                    key={clip.id}
                    className="rounded-xl border border-border bg-surface p-2.5 transition-colors shadow-2xs"
                  >
                    <div className="flex items-start justify-between gap-1.5">
                      <button
                        type="button"
                        onClick={() => handleInsertClip(clip)}
                        className="flex-1 min-w-0 text-left select-none tap-target"
                      >
                        <div className="flex items-center gap-1.5">
                          <span className="font-bold text-xs text-foreground truncate">
                            {clip.title}
                          </span>
                          {clip.pinned && (
                            <Pin className="h-2.5 w-2.5 text-mint shrink-0" />
                          )}
                        </div>
                        <p className="mt-0.5 line-clamp-1 text-[11px] text-muted-foreground">
                          {preview || "Empty"}
                        </p>
                        <div className="mt-1 text-[10px] text-muted-foreground/80 font-mono">
                          {clip.characterCount.toLocaleString()} chars · {formatBytes(clip.sizeBytes)}
                        </div>
                      </button>

                      {/* Inline Actions Toggle ("More" button) */}
                      <button
                        type="button"
                        aria-label="More card actions"
                        onClick={() =>
                          setExpandedCardId(isExpanded ? null : clip.id)
                        }
                        className={cn(
                          "rounded-md p-1.5 text-muted-foreground hover:bg-accent tap-target shrink-0",
                          isExpanded && "bg-accent text-foreground"
                        )}
                      >
                        <MoreHorizontal className="h-4 w-4" />
                      </button>
                    </div>

                    {/* Inline Action Buttons (Edit, Pin/Unpin, Delete) */}
                    {isExpanded && (
                      <div className="mt-2 flex items-center gap-1.5 border-t border-border pt-2 animate-in fade-in">
                        <Btn
                          size="sm"
                          variant="ghost"
                          onClick={() => nav({ name: "editor", id: clip.id })}
                          className="h-7 px-2 text-[11px] font-semibold"
                        >
                          <Pencil className="h-3 w-3" />
                          Edit
                        </Btn>
                        <Btn
                          size="sm"
                          variant="ghost"
                          onClick={() => handleTogglePin(clip)}
                          className="h-7 px-2 text-[11px] font-semibold"
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
                          className="h-7 px-2 text-[11px] font-semibold ml-auto"
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
        )}
      </div>

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
