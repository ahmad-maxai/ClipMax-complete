import React, { useState, useRef, useEffect, useMemo } from "react";
import {
  ArrowLeft,
  ClipboardPaste,
  FileUp,
  Search,
  ChevronUp,
  ChevronDown,
  X,
} from "lucide-react";
import { TopBar, Btn, IconBtn, ConfirmModal } from "./primitives";
import { ClipItem, Nav } from "../lib/types";
import {
  createClipItem,
  getStoredDraft,
  getStoredRetention,
  saveStoredDraft,
  clearStoredDraft,
} from "../lib/storage";
import { formatBytes, utf8ByteLength, MAX_ITEM_BYTES } from "../lib/chunker";
import { useToast } from "./ui/Toast";
import { getUiText, useAppLocale } from "../lib/i18n";

interface EditorScreenProps {
  id?: string;
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
  onClipsAdded?: (ids: string[]) => void;
  nav: Nav;
}

export function EditorScreen({
  id,
  items,
  onUpdateItems,
  onClipsAdded,
  nav,
}: EditorScreenProps) {
  const locale = useAppLocale();
  const ui = getUiText(locale);
  const t = ui.editor;
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
        toast(t.recoveredDraft, {
          description: t.recoveredDraftDesc(
            new Date(draft.timestamp).toLocaleTimeString(locale === "ar" ? "ar" : undefined)
          ),
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
          toast(t.pastedToast, {
            description: t.pastedToastDesc(text.length),
          });
          return;
        }
      }
    } catch {
      // Permission denied or not supported
    }
    // Fallback: prompt user
    const manual = window.prompt(t.manualPastePrompt);
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
      success(t.importedFromTxt(file.name));
    };
    reader.readAsText(file);
    e.target.value = "";
  };

  // Save changes
  const handleSave = () => {
    if (!content.trim() && !title.trim()) {
      error(t.cannotSaveEmpty, {
        description: t.cannotSaveEmptyDesc,
      });
      return;
    }

    if (currentBytes > MAX_ITEM_BYTES) {
      error(t.storageLimitTitle, {
        description: t.storageLimitDesc(formatBytes(currentBytes)),
      });
      return;
    }

    const timeNow = Date.now();
    const cleanTitle = (title.trim() || ui.common.untitled).slice(0, 200);

    if (currentId) {
      // Update existing (preserves pinned, isExpiryLocked, expiresAt)
      const updated = items.map((c) =>
        c.id === currentId
          ? {
              ...c,
              title: cleanTitle,
              content,
              characterCount: content.length,
              sizeBytes: currentBytes,
              modifiedAt: timeNow,
            }
          : c
      );
      onUpdateItems(updated);
    } else {
      // Create new using active retention policy
      const retention = getStoredRetention();
      const newItem = createClipItem(cleanTitle, content, false, retention);
      setCurrentId(newItem.id);
      onUpdateItems([newItem, ...items]);
      onClipsAdded?.([newItem.id]);
    }

    setSavedInitial({ title, content });
    clearStoredDraft();
    success(t.savedLocally);
  };

  const handleBack = () => {
    if (dirty) {
      setConfirmLeave(true);
    } else {
      nav(currentId ? { name: "details", id: currentId } : { name: "library" });
    }
  };

  return (
    <div className="flex h-full max-h-full w-full flex-col bg-background overflow-hidden">
      <input
        ref={fileInputRef}
        type="file"
        accept=".txt,text/plain"
        className="hidden"
        onChange={handleImportFile}
      />

      <TopBar
        left={
          <IconBtn label={ui.common.back} onClick={handleBack}>
            <ArrowLeft className="h-5 w-5 rtl:rotate-180" />
          </IconBtn>
        }
        title={
          <div className="flex items-center gap-2">
            <span className="text-[15px] font-semibold text-foreground">
              {existing ? t.editItem : t.newItem}
            </span>
            {dirty && (
              <span className="rounded-full bg-mint-soft px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-mint-foreground">
                {t.unsaved}
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
            {ui.common.save}
          </Btn>
        }
      />

      {/* Editor Sub-toolbar */}
      <div className="flex items-center gap-1.5 border-b border-border bg-surface-2 px-3 py-1.5">
        <Btn variant="ghost" size="sm" onClick={handlePaste} className="h-8 text-xs">
          <ClipboardPaste className="h-3.5 w-3.5 text-muted-foreground" />
          {t.paste}
        </Btn>
        <Btn
          variant="ghost"
          size="sm"
          aria-pressed={findOpen}
          onClick={() => setFindOpen((v) => !v)}
          className="h-8 text-xs"
        >
          <Search className="h-3.5 w-3.5 text-muted-foreground" />
          {t.find}
        </Btn>
        <Btn
          variant="ghost"
          size="sm"
          onClick={() => fileInputRef.current?.click()}
          className="h-8 text-xs"
        >
          <FileUp className="h-3.5 w-3.5 text-muted-foreground" />
          {t.importTxt}
        </Btn>
      </div>

      {/* In-Editor Find Bar */}
      {findOpen && (
        <div className="flex items-center gap-1.5 border-b border-border bg-surface px-3 py-2 animate-in slide-in-from-top-1">
          <input
            autoFocus
            dir="auto"
            aria-label={t.findInputAria}
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
            placeholder={t.findPlaceholder}
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
            label={t.prevOccurrence}
            size="iconSm"
            onClick={() => goMatch(-1)}
            disabled={!matches.length}
          >
            <ChevronUp className="h-4 w-4" />
          </IconBtn>
          <IconBtn
            label={t.nextOccurrence}
            size="iconSm"
            onClick={() => goMatch(1)}
            disabled={!matches.length}
          >
            <ChevronDown className="h-4 w-4" />
          </IconBtn>
          <IconBtn
            label={t.closeFindBar}
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
          {ui.details.titleLabel}
        </label>
        <input
          id="editor-title-input"
          dir="auto"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder={t.titlePlaceholder}
          maxLength={200}
          className="h-10 shrink-0 w-full border-b border-border bg-transparent text-base sm:text-lg font-bold text-foreground outline-none placeholder:text-muted-foreground/60 focus:border-mint"
        />

        <label className="sr-only" htmlFor="editor-content-textarea">
          {ui.details.contentMeta}
        </label>
        <textarea
          ref={textareaRef}
          id="editor-content-textarea"
          dir="auto"
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder={t.contentPlaceholder}
          className="flex-1 w-full resize-none bg-transparent pt-2.5 text-[14px] leading-relaxed text-foreground outline-none placeholder:text-muted-foreground/50 select-text font-sans overflow-y-auto"
        />
      </div>

      {/* Bottom Character & Byte Counter Bar */}
      <div
        className="shrink-0 flex items-center justify-between border-t border-border bg-background px-4 py-2 font-mono text-xs text-muted-foreground"
        aria-live="polite"
      >
        <span>{t.charsCount(content.length)}</span>
        <span
          className={
            currentBytes > MAX_ITEM_BYTES ? "text-destructive font-semibold" : ""
          }
        >
          {t.storedBytes(formatBytes(currentBytes))}
        </span>
      </div>

      {/* Discard Changes Modal */}
      <ConfirmModal
        open={confirmLeave}
        title={t.discardTitle}
        description={t.discardDesc}
        confirmLabel={t.discardBtn}
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
