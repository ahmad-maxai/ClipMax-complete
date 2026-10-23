import React, { useState } from "react";
import {
  ArrowLeft,
  Copy,
  CopyPlus,
  Download,
  Pencil,
  Pin,
  PinOff,
  Share2,
  Trash2,
  Type,
  Check,
} from "lucide-react";
import { TopBar, Btn, IconBtn, ConfirmModal } from "./primitives";
import { ClipItem, Nav } from "../lib/types";
import { fullDateTime } from "../lib/storage";
import { formatBytes } from "../lib/chunker";
import { downloadFile } from "../lib/backup";
import { useToast } from "./ui/Toast";

interface DetailsScreenProps {
  id: string;
  items: ClipItem[];
  onUpdateItems: (items: ClipItem[]) => void;
  nav: Nav;
}

export function DetailsScreen({
  id,
  items,
  onUpdateItems,
  nav,
}: DetailsScreenProps) {
  const { toast, success } = useToast();
  const clip = items.find((c) => c.id === id);

  const [renaming, setRenaming] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [copied, setCopied] = useState(false);

  if (!clip) {
    return (
      <div className="flex h-full flex-col items-center justify-center p-6 text-center text-sm text-muted-foreground">
        <p className="font-medium">This item no longer exists.</p>
        <Btn className="mt-4" onClick={() => nav({ name: "library" })}>
          Back to library
        </Btn>
      </div>
    );
  }

  const lines = clip.content.split("\n").length;
  const words = clip.content.trim() ? clip.content.trim().split(/\s+/).length : 0;

  // Toggle Pinned
  const handleTogglePinned = () => {
    const updated = items.map((c) =>
      c.id === id ? { ...c, pinned: !c.pinned, modifiedAt: Date.now() } : c
    );
    onUpdateItems(updated);
    toast(clip.pinned ? "Unpinned from top" : "Pinned to top");
  };

  // Duplicate item
  const handleDuplicate = () => {
    const t = Date.now();
    const copyId = `clip-${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 7)}`;
    const copyItem: ClipItem = {
      ...clip,
      id: copyId,
      title: `${clip.title} (copy)`.slice(0, 200),
      pinned: false,
      createdAt: t,
      modifiedAt: t,
    };
    onUpdateItems([copyItem, ...items]);
    success("Item duplicated", {
      action: {
        label: "Open copy",
        onClick: () => nav({ name: "details", id: copyId }),
      },
    });
  };

  // Export .txt file
  const handleExportTxt = () => {
    const blob = new Blob([clip.content], { type: "text/plain;charset=utf-8" });
    const filename = `${clip.title.replace(/[/\\?%*:|"<>]/g, "_") || "clip"}.txt`;
    downloadFile(blob, filename);
    success(`Exported "${filename}"`, {
      description: "Downloaded using document export.",
    });
  };

  // Copy to clipboard
  const handleCopy = () => {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(clip.content).catch(() => {});
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
    success("Copied to clipboard", {
      description: `${clip.characterCount.toLocaleString()} characters copied.`,
    });
  };

  // Share
  const handleShare = () => {
    if (navigator.share) {
      navigator
        .share({
          title: clip.title,
          text: clip.content,
        })
        .catch(() => {});
    } else {
      handleCopy();
      toast("Share sheet opened or copied to clipboard");
    }
  };

  // Rename commit
  const handleRenameCommit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;
    const updated = items.map((c) =>
      c.id === id
        ? {
            ...c,
            title: newTitle.trim().slice(0, 200),
            modifiedAt: Date.now(),
          }
        : c
    );
    onUpdateItems(updated);
    setRenaming(false);
    toast("Item renamed");
  };

  // Delete commit
  const handleDeleteCommit = () => {
    onUpdateItems(items.filter((c) => c.id !== id));
    nav({ name: "library" });
    toast("Item deleted");
  };

  const actionButtons = [
    {
      label: "Edit",
      icon: Pencil,
      onClick: () => nav({ name: "editor", id: clip.id }),
    },
    {
      label: clip.pinned ? "Unpin" : "Pin",
      icon: clip.pinned ? PinOff : Pin,
      onClick: handleTogglePinned,
    },
    {
      label: "Rename",
      icon: Type,
      onClick: () => {
        setNewTitle(clip.title);
        setRenaming(true);
      },
    },
    {
      label: "Duplicate",
      icon: CopyPlus,
      onClick: handleDuplicate,
    },
    {
      label: "Export .txt",
      icon: Download,
      onClick: handleExportTxt,
    },
    {
      label: "Share",
      icon: Share2,
      onClick: handleShare,
    },
  ];

  return (
    <div className="flex h-[100dvh] max-h-[100dvh] w-full flex-col bg-background overflow-hidden">
      <TopBar
        left={
          <IconBtn label="Back" onClick={() => nav({ name: "library" })}>
            <ArrowLeft className="h-5 w-5" />
          </IconBtn>
        }
        title="Details"
        right={
          <IconBtn
            label="Delete item"
            className="text-destructive hover:bg-destructive/10"
            onClick={() => setConfirmDelete(true)}
          >
            <Trash2 className="h-5 w-5" />
          </IconBtn>
        }
      />

      <div className="flex-1 min-h-0 overflow-y-auto space-y-3 px-4 py-3">
        {/* Title and Pin status */}
        <div>
          <div className="flex items-start gap-2">
            <h1 className="flex-1 text-xl font-bold leading-snug tracking-tight text-foreground">
              {clip.title}
            </h1>
            {clip.pinned && (
              <span className="mt-1 inline-flex items-center gap-1 rounded-full bg-mint-soft px-2.5 py-0.5 text-xs font-semibold text-mint-foreground">
                <Pin className="h-3 w-3" />
                Pinned
              </span>
            )}
          </div>

          {/* 4-Grid Metadata */}
          <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 rounded-xl border border-border bg-surface p-3 text-xs">
            <MetaItem label="Modified" value={fullDateTime(clip.modifiedAt)} />
            <MetaItem label="Created" value={fullDateTime(clip.createdAt)} />
            <MetaItem
              label="Size"
              value={`${clip.characterCount.toLocaleString()} chars · ${formatBytes(clip.sizeBytes)}`}
            />
            <MetaItem
              label="Content"
              value={`${words.toLocaleString()} words · ${lines.toLocaleString()} lines`}
            />
          </dl>
        </div>

        {/* Content Viewer */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-xs text-muted-foreground px-1">
            <span>Stored UTF-8 content</span>
            <span className="font-mono">{formatBytes(clip.sizeBytes)}</span>
          </div>
          <div className="max-h-[44vh] overflow-y-auto rounded-xl border border-border bg-surface p-4 shadow-2xs">
            <p className="whitespace-pre-wrap break-words text-[13.5px] leading-relaxed text-foreground select-text font-sans">
              {clip.content || <span className="text-muted-foreground italic">Empty text snippet</span>}
            </p>
          </div>
        </div>

        {/* 6 Actions Grid */}
        <div className="grid grid-cols-3 gap-2">
          {actionButtons.map((btn) => (
            <button
              key={btn.label}
              type="button"
              onClick={btn.onClick}
              className="flex h-16 flex-col items-center justify-center gap-1.5 rounded-xl border border-border bg-surface text-xs font-medium text-foreground transition-colors hover:bg-surface-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring tap-target active:scale-[0.98]"
            >
              <btn.icon className="h-4.5 w-4.5 text-muted-foreground group-hover:text-primary" />
              <span>{btn.label}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Sticky Bottom Actions */}
      <div className="shrink-0 flex gap-2 border-t border-border bg-background p-3">
        <Btn
          variant="dangerOutline"
          onClick={() => setConfirmDelete(true)}
          className="gap-1.5"
        >
          <Trash2 className="h-4 w-4" />
          Delete
        </Btn>
        <Btn
          variant="primary"
          className="flex-1 gap-1.5"
          onClick={handleCopy}
        >
          {copied ? <Check className="h-4 w-4 text-mint" /> : <Copy className="h-4 w-4" />}
          {copied ? "Copied" : "Copy to clipboard"}
        </Btn>
      </div>

      {/* Rename Modal */}
      {renaming && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs"
        >
          <div className="w-full max-w-sm rounded-2xl border border-border bg-surface p-5 shadow-2xl">
            <h3 className="text-base font-semibold">Rename item</h3>
            <form onSubmit={handleRenameCommit} className="mt-4 space-y-4">
              <div>
                <label htmlFor="rename-input" className="text-xs font-medium text-muted-foreground">
                  Title
                </label>
                <input
                  id="rename-input"
                  autoFocus
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  className="mt-1 h-11 w-full rounded-lg border border-input bg-surface px-3 text-sm outline-none focus:border-mint focus:ring-2 focus:ring-ring/30"
                />
              </div>
              <div className="flex justify-end gap-2">
                <Btn variant="ghost" size="sm" onClick={() => setRenaming(false)}>
                  Cancel
                </Btn>
                <Btn type="submit" variant="primary" size="sm" disabled={!newTitle.trim()}>
                  Save
                </Btn>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Confirm Delete Modal */}
      <ConfirmModal
        open={confirmDelete}
        title="Delete this item?"
        description={`"${clip.title}" will be permanently removed from this device's private storage.`}
        confirmLabel="Delete"
        onConfirm={handleDeleteCommit}
        onClose={() => setConfirmDelete(false)}
      />
    </div>
  );
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-muted-foreground/80 text-[11px] uppercase tracking-wider">{label}</dt>
      <dd className="truncate font-medium text-foreground text-[12px] mt-0.5">{value}</dd>
    </div>
  );
}
