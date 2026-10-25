import React, { useState, useEffect } from "react";
import { ClipItem } from "../lib/types";
import { formatBytes } from "../lib/chunker";
import { cn } from "../lib/utils";
import {
  Search,
  Pin,
  PinOff,
  Keyboard,
  ChevronDown,
  X,
  Lock,
  Pencil,
  Trash2,
  MoreHorizontal,
  Delete,
  CornerDownLeft,
} from "lucide-react";
import { ClipMaxLogo } from "./primitives";
import { QwertyKeyboard } from "./QwertyKeyboard";

interface ImeKeyboardScreenProps {
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
}

export function ImeKeyboardScreen({ items, onUpdateItems }: ImeKeyboardScreenProps) {
  const [searchKeypadOpen, setSearchKeypadOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [pinnedOnly, setPinnedOnly] = useState(false);
  const [expandedCardId, setExpandedCardId] = useState<string | null>(null);
  const [isPassword, setIsPassword] = useState(false);

  // Listen to password event and refresh from Android IME service
  useEffect(() => {
    const handlePassword = (e: Event) => {
      const custom = e as CustomEvent<{ isPassword: boolean }>;
      setIsPassword(Boolean(custom.detail?.isPassword));
    };

    window.addEventListener("clipmax:password", handlePassword);
    return () => {
      window.removeEventListener("clipmax:password", handlePassword);
    };
  }, []);

  // Filter clips
  const visibleClips = items.filter((clip) => {
    if (pinnedOnly && !clip.pinned) return false;
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return (
      clip.title.toLowerCase().includes(q) ||
      clip.content.toLowerCase().includes(q)
    );
  });

  // Native IME bridge helpers
  const getImeBridge = () => (window as any).ClipMaxIme;

  const handleInsert = (clip: ClipItem) => {
    const ime = getImeBridge();
    if (ime?.insertClip) {
      ime.insertClip(clip.id);
    } else {
      // Fallback: clipboard copy
      navigator.clipboard?.writeText(clip.content);
    }
  };

  const handleTogglePin = (clip: ClipItem) => {
    const updatedPinned = !clip.pinned;
    const ime = getImeBridge();
    if (ime?.setPinned) {
      ime.setPinned(clip.id, updatedPinned);
    }
    const updated = items.map((c) =>
      c.id === clip.id ? { ...c, pinned: updatedPinned, modifiedAt: Date.now() } : c
    );
    onUpdateItems(updated);
  };

  const handleDelete = (clip: ClipItem) => {
    const ime = getImeBridge();
    if (ime?.deleteItem) {
      ime.deleteItem(clip.id);
    }
    onUpdateItems(items.filter((c) => c.id !== clip.id));
    setExpandedCardId(null);
  };

  const handleEdit = (clip: ClipItem) => {
    const ime = getImeBridge();
    if (ime?.openClipMaxApp) {
      ime.openClipMaxApp(clip.id);
    }
  };

  const handleSwitchKeyboard = () => {
    const ime = getImeBridge();
    if (ime?.switchKeyboard) {
      ime.switchKeyboard();
    }
  };

  const handleHideKeyboard = () => {
    const ime = getImeBridge();
    if (ime?.hideKeyboard) {
      ime.hideKeyboard();
    }
  };

  return (
    <div className="flex h-full max-h-full w-full flex-col bg-[#111916] text-[#EAF1ED] overflow-hidden select-none box-border font-sans">
      {/* Top Header Bar */}
      <div className="flex h-9 shrink-0 items-center justify-between border-b border-[#24352E] bg-[#16211C] px-2.5 w-full max-w-full box-border gap-1 overflow-hidden">
        {/* Left: Branding & Search / Pinned Filter */}
        <div className="flex items-center gap-1.5 min-w-0">
          <div className="flex items-center gap-1.5 pr-1">
            <ClipMaxLogo size={20} />
            <span className="text-xs font-bold tracking-tight text-[#EAF1ED] hidden xs:inline">
              Clip<span className="text-[#2DD4BF]">Max</span>
            </span>
          </div>

          <button
            type="button"
            onClick={() => setSearchKeypadOpen((v) => !v)}
            className={cn(
              "inline-flex h-6.5 items-center gap-1 rounded-md border px-2 text-[10px] font-bold transition-colors tap-target shrink-0",
              searchKeypadOpen || searchQuery
                ? "border-[#2DD4BF] bg-[#2DD4BF]/15 text-[#2DD4BF]"
                : "border-[#263830] bg-[#1B2923] text-[#EAF1ED] hover:bg-[#24352E]"
            )}
          >
            <Search className="h-3 w-3 shrink-0" />
            <span>{searchKeypadOpen ? "Hide" : searchQuery ? `"${searchQuery.slice(0, 6)}"` : "Keypad"}</span>
          </button>

          {searchQuery && (
            <button
              type="button"
              onClick={() => setSearchQuery("")}
              className="grid h-6 w-6 place-items-center rounded text-[#8A9E96] hover:bg-[#24352E] tap-target shrink-0"
              title="Clear search"
            >
              <X className="h-3 w-3" />
            </button>
          )}

          <button
            type="button"
            onClick={() => setPinnedOnly((v) => !v)}
            className={cn(
              "inline-flex h-6.5 items-center gap-1 rounded-md border px-2 text-[10px] font-bold tap-target shrink-0",
              pinnedOnly
                ? "border-[#2DD4BF] bg-[#2DD4BF]/15 text-[#2DD4BF]"
                : "border-[#263830] bg-[#1B2923] text-[#8A9E96] hover:bg-[#24352E]"
            )}
          >
            <Pin className="h-2.5 w-2.5 shrink-0" />
            <span>Pinned</span>
          </button>
        </div>

        {/* Right: Switch Keyboard & Close Buttons */}
        <div className="flex items-center gap-1 shrink-0">
          <button
            type="button"
            onClick={handleSwitchKeyboard}
            className="inline-flex h-6.5 items-center gap-1 rounded-md border border-[#263830] bg-[#1B2923] px-2 text-[10px] font-semibold text-[#8A9E96] hover:text-[#EAF1ED] hover:bg-[#24352E] tap-target"
            title="Switch to another keyboard"
          >
            <Keyboard className="h-3 w-3 text-[#2DD4BF]" />
            <span className="hidden sm:inline">Switch</span>
          </button>

          <button
            type="button"
            onClick={handleHideKeyboard}
            className="grid h-6.5 w-6.5 place-items-center rounded-md border border-[#263830] bg-[#1B2923] text-[#8A9E96] hover:text-[#EAF1ED] hover:bg-[#24352E] tap-target"
            title="Dismiss keyboard"
          >
            <ChevronDown className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {/* Main Content Area */}
      {isPassword ? (
        <div className="flex-1 flex flex-col items-center justify-center p-4 text-center">
          <Lock className="h-8 w-8 text-rose-400 mb-2" />
          <p className="font-bold text-sm text-[#EAF1ED]">Password Field Focused</p>
          <p className="text-xs text-[#8A9E96] max-w-xs mt-1">
            ClipMax suppresses saved cards and clipboard capture in password fields to safeguard credentials.
          </p>
        </div>
      ) : searchKeypadOpen ? (
        /* Keypad Mode */
        <div className="flex-1 flex flex-col bg-[#111916] p-1.5 space-y-1 overflow-hidden box-border max-w-full w-full">
          {/* Query Bar + Quick Matching Snippets Strip */}
          <div className="flex items-center justify-between px-1 text-[11px] shrink-0">
            <span className="font-mono text-[#8A9E96] truncate">
              Query: <strong className="text-[#2DD4BF]">{searchQuery || "(type to filter clips)"}</strong>
            </span>
            <button
              type="button"
              onClick={() => setSearchKeypadOpen(false)}
              className="text-[11px] font-bold text-[#2DD4BF] hover:underline pl-2 shrink-0 flex items-center gap-0.5"
            >
              Done <ChevronDown className="h-3 w-3" />
            </button>
          </div>

          {/* Quick Matched Chips Strip (One-tap insert while typing) */}
          <div className="h-7.5 shrink-0 flex items-center gap-1.5 overflow-x-auto no-scrollbar py-0.5 px-0.5 w-full box-border">
            {visibleClips.length === 0 ? (
              <span className="text-[11px] text-[#8A9E96] italic">
                {searchQuery ? "No matching clips" : "Type keys below to search saved snippets"}
              </span>
            ) : (
              visibleClips.map((clip) => (
                <button
                  key={clip.id}
                  type="button"
                  onClick={() => handleInsert(clip)}
                  className="inline-flex h-6.5 shrink-0 items-center gap-1.5 rounded border border-[#2D3F37] bg-[#1E2B25] px-2 text-[11px] font-bold text-[#EAF1ED] shadow-2xs hover:bg-[#2DD4BF]/20 hover:text-[#2DD4BF] transition-colors tap-target"
                  title={`Insert "${clip.title}"`}
                >
                  {clip.pinned && <Pin className="h-2.5 w-2.5 text-[#2DD4BF]" />}
                  <span className="truncate max-w-[120px]">{clip.title}</span>
                  <span className="font-mono text-[9px] text-[#8A9E96]">
                    {formatBytes(clip.sizeBytes)}
                  </span>
                </button>
              ))
            )}
          </div>

          {/* Proportional Gboard QWERTY Keyboard */}
          <QwertyKeyboard
            onKeyPress={(char) => setSearchQuery((prev) => prev + char)}
            onBackspace={() => setSearchQuery((prev) => prev.slice(0, -1))}
            onSpace={() => setSearchQuery((prev) => prev + " ")}
            onEnter={() => setSearchKeypadOpen(false)}
            size="compact"
          />
        </div>
      ) : (
        /* Saved Clips List Mode */
        <div className="flex-1 overflow-y-auto p-2 space-y-2 w-full max-w-full box-border">
          {visibleClips.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center p-4 text-center text-xs text-[#8A9E96] space-y-1">
              <p className="font-bold text-[#EAF1ED]">
                {searchQuery ? `No clips match "${searchQuery}"` : "No saved items available"}
              </p>
              <p className="text-[11px]">
                {searchQuery
                  ? "Tap Clear or type another search term."
                  : "Copy text in any app to automatically save clips here."}
              </p>
            </div>
          ) : (
            visibleClips.map((clip) => {
              const isExpanded = expandedCardId === clip.id;
              const preview = clip.content.replace(/\s+/g, " ").trim();

              return (
                <div
                  key={clip.id}
                  className="rounded-xl border border-[#24352E] bg-[#16211C] p-2.5 shadow-2xs transition-colors hover:border-[#2DD4BF]/40"
                >
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-1.5">
                        <span className="font-bold text-xs text-[#EAF1ED] truncate">
                          {clip.title}
                        </span>
                        {clip.pinned && (
                          <Pin className="h-2.5 w-2.5 text-[#2DD4BF] shrink-0" />
                        )}
                      </div>
                      <p className="mt-0.5 line-clamp-1 text-[11px] text-[#8A9E96]">
                        {preview || "Empty"}
                      </p>
                      <div className="mt-1 text-[10px] text-[#8A9E96]/80 font-mono">
                        {clip.characterCount.toLocaleString()} chars · {formatBytes(clip.sizeBytes)}
                      </div>
                    </div>

                    {/* Insert Button */}
                    <button
                      type="button"
                      onClick={() => handleInsert(clip)}
                      className="inline-flex h-7.5 items-center gap-1 rounded-lg bg-[#2DD4BF] px-3 text-xs font-bold text-[#111916] shadow-sm hover:opacity-90 active:scale-95 transition-all tap-target shrink-0"
                    >
                      <CornerDownLeft className="h-3.5 w-3.5 stroke-[2.5]" />
                      <span>Insert</span>
                    </button>

                    {/* Expand More Options Button */}
                    <button
                      type="button"
                      onClick={() => setExpandedCardId(isExpanded ? null : clip.id)}
                      className={cn(
                        "grid h-7.5 w-7.5 place-items-center rounded-lg border border-[#263830] text-[#8A9E96] hover:text-[#EAF1ED] hover:bg-[#24352E] tap-target shrink-0 transition-colors",
                        isExpanded && "bg-[#24352E] text-[#EAF1ED] border-[#2DD4BF]/50"
                      )}
                      title="More actions"
                    >
                      <MoreHorizontal className="h-4 w-4" />
                    </button>
                  </div>

                  {/* Expanded Actions: Edit, Pin, Delete */}
                  {isExpanded && (
                    <div className="mt-2 flex items-center gap-2 border-t border-[#24352E] pt-2 animate-in fade-in">
                      <button
                        type="button"
                        onClick={() => handleEdit(clip)}
                        className="inline-flex h-7 items-center gap-1 rounded-md px-2 text-[11px] font-semibold text-[#8A9E96] hover:text-[#EAF1ED] hover:bg-[#24352E] tap-target"
                      >
                        <Pencil className="h-3 w-3" />
                        Edit in App
                      </button>

                      <button
                        type="button"
                        onClick={() => handleTogglePin(clip)}
                        className="inline-flex h-7 items-center gap-1 rounded-md px-2 text-[11px] font-semibold text-[#8A9E96] hover:text-[#EAF1ED] hover:bg-[#24352E] tap-target"
                      >
                        {clip.pinned ? <PinOff className="h-3 w-3" /> : <Pin className="h-3 w-3" />}
                        {clip.pinned ? "Unpin" : "Pin"}
                      </button>

                      <button
                        type="button"
                        onClick={() => handleDelete(clip)}
                        className="inline-flex h-7 items-center gap-1 rounded-md px-2 text-[11px] font-semibold text-rose-400 hover:bg-rose-500/15 tap-target ml-auto"
                      >
                        <Trash2 className="h-3 w-3" />
                        Delete
                      </button>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      )}
    </div>
  );
}
