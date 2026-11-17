import React, { useState, useRef, useEffect, useMemo } from "react";
import {
  ArrowLeft,
  ClipboardPaste,
  FileUp,
  Search,
  ChevronUp,
  ChevronDown,
  X,
  Keyboard,
  RotateCcw,
} from "lucide-react";
import { TopBar, Btn, IconBtn, ConfirmModal } from "./primitives";
import { ClipItem, Nav } from "../lib/types";
import {
  createClipItem,
  getStoredDraft,
  saveStoredDraft,
  clearStoredDraft,
} from "../lib/storage";
import { formatBytes, utf8ByteLength, MAX_ITEM_BYTES } from "../lib/chunker";
import { useToast } from "./ui/Toast";

interface EditorScreenProps {
  id?: string;
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
  nav: Nav;
}

export function EditorScreen({
  id,
  items,
  onUpdateItems,
  nav,
}: EditorScreenProps) {
  const { toast, success, error } = useToast();
  const existing = id ? items.find((c) => c.id === id) : undefined;

  const [title, setTitle] = useState(existing?.title ?? "");
  const [content, setContent] = useState(existing?.content ?? "");
  const [savedInitial, setSavedInitial] = useState({
    title: existing?.title ?? "",
    content: existing?.content ?? "",
  });

  const [currentId, setCurrentId] = useState<string | undefined>(id);
  const [findOpen, setFindOpen] = useState(false);
  const [findQuery, setFindQuery] = useState("");
  const [matchIdx, setMatchIdx] = useState(0);
  const [confirmLeave, setConfirmLeave] = useState(false);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const dirty = title !== savedInitial.title || content !== savedInitial.content;
  const currentBytes = useMemo(() => utf8ByteLength(content), [content]);

  // Check for stored draft on initial mount if creating new item
  useEffect(() => {
    if (!id) {
      const draft = getStoredDraft();
      if (draft && draft.content && (draft.content !== content || draft.title !== title)) {
        toast("Recovered unsaved draft", {
          description: `Loaded previous draft from ${new Date(draft.timestamp).toLocaleTimeString()}`,
        });
        setTitle(draft.title);
        setContent(draft.content);
      }
    }
  }, [id]);

  // Debounced auto-save to draft storage
  useEffect(() => {
    if (!dirty) return;
    const timer = setTimeout(() => {
      saveStoredDraft({
        itemId: currentId || null,
        title,
        content,
        timestamp: Date.now(),
      });
    }, 800);
    return () => clearTimeout(timer);
  }, [dirty, currentId, title, content]);

  // Compute matches for in-page find
  const matches = useMemo(() => {
    if (!findQuery.trim()) return [];
    const term = findQuery.toLowerCase();
    const haystack = content.toLowerCase();
    const result: number[] = [];
    let pos = 0;
    while ((pos = haystack.indexOf(term, pos)) !== -1) {
      result.push(pos);
      pos += term.length;
    }
    return result;
  }, [content, findQuery]);

  const goMatch = (dir: 1 | -1) => {
    if (!matches.length || !textareaRef.current) return;
    const nextIdx = (matchIdx + dir + matches.length) % matches.length;
    setMatchIdx(nextIdx);
    const pos = matches[nextIdx];
    textareaRef.current.focus();
    textareaRef.current.setSelectionRange(pos, pos + findQuery.length);
  };

  const insertTextAtCursor = (inserted: string) => {
    const el = textareaRef.current;
    if (!el) {
      setContent((prev) => prev + inserted);
      return;
    }
    const start = el.selectionStart ?? content.length;
    const end = el.selectionEnd ?? content.length;
    const updated = content.slice(0, start) + inserted + content.slice(end);
    setContent(updated);
    setTimeout(() => {
      el.focus();
      el.setSelectionRange(start + inserted.length, start + inserted.length);
    }, 50);
  };

  // Handle Paste
  const handlePaste = async () => {
    try {
      if (navigator.clipboard?.readText) {
        const text = await navigator.clipboard.readText();
        if (text) {
          insertTextAtCursor(text);
          toast("Pasted clipboard text", {
            description: `${text.length.toLocaleString()} characters`,
          });
          return;
        }
      }
    } catch {
      // Permission denied or not supported
    }
    // Fallback: prompt user
    const manual = window.prompt("Paste text below:");
    if (manual) {
      insertTextAtCursor(manual);
    }
  };

  // Handle Import .txt
  const handleImportFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const text = String(event.target?.result || "");
      insertTextAtCursor(text);
      if (!title.trim()) {
        setTitle(file.name.replace(/\.txt$/i, ""));
      }
      success(`Imported text from "${file.name}"`);
    };
    reader.readAsText(file);
    e.target.value = "";
  };

  // Save changes
  const handleSave = () => {
    if (!content.trim() && !title.trim()) {
      error("Cannot save empty clip", {
        description: "Please provide either a title or some text content.",
      });
      return;
    }

    if (currentBytes > MAX_ITEM_BYTES) {
      error("Storage limit exceeded", {
        description: `This item is ${formatBytes(currentBytes)}, exceeding the 16 MiB maximum.`,
      });
      return;
    }

    const t = Date.now();
    const cleanTitle = (title.trim() || "Untitled").slice(0, 200);

    if (currentId) {
      // Update existing
      const updated = items.map((c) =>
        c.id === currentId
          ? {
              ...c,
              title: cleanTitle,
              content,
              characterCount: content.length,
              sizeBytes: currentBytes,
              modifiedAt: t,
            }
          : c
      );
      onUpdateItems(updated);
    } else {
      // Create new
      const newItem = createClipItem(cleanTitle, content, false);
      setCurrentId(newItem.id);
      onUpdateItems([newItem, ...items]);
    }

    setSavedInitial({ title, content });
    clearStoredDraft();
    success("Changes saved locally");
  };

  const handleBack = () => {
    if (dirty) {
      setConfirmLeave(true);
    } else {
      nav(currentId ? { name: "details", id: currentId } : { name: "library" });
    }
  };

  return (
    <div className="flex h-[100dvh] max-h-[100dvh] w-full flex-col bg-background overflow-hidden">
      <input
        ref={fileInputRef}
        type="file"
        accept=".txt,text/plain"
        className="hidden"
        onChange={handleImportFile}
      />

      <TopBar
        left={
          <IconBtn label="Back" onClick={handleBack}>
            <ArrowLeft className="h-5 w-5" />
          </IconBtn>
        }
        title={
          <div className="flex items-center gap-2">
            <span className="text-[15px] font-semibold text-foreground">
              {existing ? "Edit item" : "New item"}
            </span>
            {dirty && (
              <span className="rounded-full bg-mint-soft px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-mint-foreground">
                Unsaved
              </span>
            )}
          </div>
        }
        right={
          <Btn
            variant="primary"
            size="sm"
            onClick={handleSave}
            disabled={!dirty}
          >
            Save
          </Btn>
        }
      />

      {/* Editor Sub-toolbar */}
      <div className="flex items-center gap-1.5 border-b border-border bg-surface-2 px-3 py-1.5">
        <Btn variant="ghost" size="sm" onClick={handlePaste} className="h-8 text-xs">
          <ClipboardPaste className="h-3.5 w-3.5 text-muted-foreground" />
          Paste
        </Btn>
        <Btn
          variant="ghost"
          size="sm"
          aria-pressed={findOpen}
          onClick={() => setFindOpen((v) => !v)}
          className="h-8 text-xs"
        >
          <Search className="h-3.5 w-3.5 text-muted-foreground" />
          Find
        </Btn>
        <Btn
          variant="ghost"
          size="sm"
          onClick={() => fileInputRef.current?.click()}
          className="h-8 text-xs"
        >
          <FileUp className="h-3.5 w-3.5 text-muted-foreground" />
          Import .txt
        </Btn>
        <Btn
          variant="ghost"
          size="sm"
          onClick={() => nav({ name: "keyboard" })}
          className="ml-auto h-8 text-xs text-muted-foreground hover:text-foreground"
          title="Open ClipMax IME keyboard preview"
        >
          <Keyboard className="h-3.5 w-3.5" />
          IME Picker
        </Btn>
      </div>

      {/* In-Editor Find Bar */}
      {findOpen && (
        <div className="flex items-center gap-1.5 border-b border-border bg-surface px-3 py-2 animate-in slide-in-from-top-1">
          <input
            autoFocus
            aria-label="Find in text content"
            value={findQuery}
            onChange={(e) => {
              setFindQuery(e.target.value);
              setMatchIdx(0);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                goMatch(1);
              }
            }}
            placeholder="Find in content…"
            className="h-8 min-w-0 flex-1 rounded-md border border-input bg-background px-2.5 text-xs outline-none focus:border-mint"
          />
          <span
            className="w-16 text-center font-mono text-[11px] text-muted-foreground"
            aria-live="polite"
          >
            {findQuery
              ? `${matches.length ? matchIdx + 1 : 0}/${matches.length}`
              : ""}
          </span>
          <IconBtn
            label="Previous occurrence"
            size="iconSm"
            onClick={() => goMatch(-1)}
            disabled={!matches.length}
          >
            <ChevronUp className="h-4 w-4" />
          </IconBtn>
          <IconBtn
            label="Next occurrence"
            size="iconSm"
            onClick={() => goMatch(1)}
            disabled={!matches.length}
          >
            <ChevronDown className="h-4 w-4" />
          </IconBtn>
          <IconBtn
            label="Close find bar"
            size="iconSm"
            onClick={() => {
              setFindOpen(false);
              setFindQuery("");
            }}
          >
            <X className="h-4 w-4" />
          </IconBtn>
        </div>
      )}

      {/* Text Area and Title */}
      <div className="flex flex-1 min-h-0 flex-col px-4 pt-2.5 pb-2 overflow-hidden">
        <label className="sr-only" htmlFor="editor-title-input">
          Title
        </label>
        <input
          id="editor-title-input"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title (optional)"
          maxLength={200}
          className="h-10 shrink-0 w-full border-b border-border bg-transparent text-base sm:text-lg font-bold text-foreground outline-none placeholder:text-muted-foreground/60 focus:border-mint"
        />

        <label className="sr-only" htmlFor="editor-content-textarea">
          Content
        </label>
        <textarea
          ref={textareaRef}
          id="editor-content-textarea"
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder="Type or paste text snippet here…"
          className="flex-1 w-full resize-none bg-transparent pt-2.5 text-[14px] leading-relaxed text-foreground outline-none placeholder:text-muted-foreground/50 select-text font-sans overflow-y-auto"
        />
      </div>

      {/* Bottom Character & Byte Counter Bar */}
      <div
        className="shrink-0 flex items-center justify-between border-t border-border bg-background px-4 py-2 font-mono text-xs text-muted-foreground"
        aria-live="polite"
      >
        <span>{content.length.toLocaleString()} characters</span>
        <span
          className={
            currentBytes > MAX_ITEM_BYTES ? "text-destructive font-semibold" : ""
          }
        >
          {formatBytes(currentBytes)} stored
        </span>
      </div>

      {/* Discard Changes Modal */}
      <ConfirmModal
        open={confirmLeave}
        title="Discard unsaved changes?"
        description="You have edits that haven't been saved to local storage. Leaving now will discard them."
        confirmLabel="Discard"
        destructive={true}
        onConfirm={() => {
          clearStoredDraft();
          nav(currentId ? { name: "details", id: currentId } : { name: "library" });
        }}
        onClose={() => setConfirmLeave(false)}
      />
    </div>
  );
}
