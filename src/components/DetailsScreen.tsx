import React, { useState } from "react";
import {
  ArrowLeft,
  Copy,
  CopyPlus,
  Download,
  Lock,
  Pencil,
  Pin,
  PinOff,
  Share2,
  Trash2,
  Type,
  Unlock,
  Check,
} from "lucide-react";
import { TopBar, Btn, IconBtn, ConfirmModal } from "./primitives";
import { ClipItem, Nav, Retention } from "../lib/types";
import {
  applyItemExpiryLockState,
  computeExpiry,
  fullDateTime,
  hasFiniteRetention,
} from "../lib/storage";
import { formatBytes } from "../lib/chunker";
import { downloadFile } from "../lib/backup";
import { nativeCopy, nativeShare } from "../lib/nativeBridge";
import { useToast } from "./ui/Toast";
import { cn } from "../lib/utils";
import { getUiText, useAppLocale } from "../lib/i18n";

interface DetailsScreenProps {
  id: string;
  items: ClipItem[];
  retention: Retention;
  onUpdateItems: (items: ClipItem[]) => void;
  nav: Nav;
}

export function DetailsScreen({
  id,
  items,
  retention,
  onUpdateItems,
  nav,
}: DetailsScreenProps) {
  const locale = useAppLocale();
  const ui = getUiText(locale);
  const t = ui.details;
  const { toast, success } = useToast();
  const clip = items.find((c) => c.id === id);

  const [renaming, setRenaming] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [copied, setCopied] = useState(false);

  if (!clip) {
    return (
      <div className="flex h-full flex-col items-center justify-center p-6 text-center text-sm text-muted-foreground">
        <p className="font-medium">{t.missingItem}</p>
        <Btn className="mt-4" onClick={() => nav({ name: "library" })}>
          {t.backToLibrary}
        </Btn>
      </div>
    );
  }

  const lines = clip.content.split("\n").length;
  const words = clip.content.trim() ? clip.content.trim().split(/\s+/).length : 0;

  // Toggle Pinned (completely independent of expiry lock)
  const handleTogglePinned = () => {
    const nextPinned = !clip.pinned;
    const nowMs = Date.now();
    const updated = items.map((c) =>
      c.id === id
        ? {
            ...c,
            pinned: nextPinned,
            expiresAt:
              nextPinned || c.isExpiryLocked
                ? null
                : c.expiresAt ?? computeExpiry(retention, nowMs),
            modifiedAt: nowMs,
          }
        : c
    );
    onUpdateItems(updated);
    toast(clip.pinned ? t.unpinnedToast : t.pinnedToast);
  };

  // Toggle Expiry Protection Lock (completely independent of pinning)
  const handleToggleExpiryLock = () => {
    const nextLocked = !Boolean(clip.isExpiryLocked);
    const nowMs = Date.now();
    const updated = items.map((c) =>
      c.id === id ? applyItemExpiryLockState(c, nextLocked, retention, nowMs) : c
    );
    onUpdateItems(updated);
    if (nextLocked) {
      toast(t.lockedToast, {
        description: t.lockedToastDesc,
      });
    } else {
      toast(t.unlockedToast, {
        description:
          !clip.pinned && hasFiniteRetention(retention)
            ? t.unlockedFreshDesc
            : t.unlockedDoneDesc,
      });
    }
  };

  // Duplicate item
  const handleDuplicate = () => {
    const timeNow = Date.now();
    const copyId = `clip-${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 7)}`;
    const copyItem: ClipItem = {
      ...clip,
      id: copyId,
      title: `${clip.title} ${t.copySuffix}`.slice(0, 200),
      pinned: false,
      isExpiryLocked: false,
      expiresAt: computeExpiry(retention, timeNow),
      createdAt: timeNow,
      modifiedAt: timeNow,
    };
    onUpdateItems([copyItem, ...items]);
    success(t.duplicatedToast, {
      action: {
        label: t.openCopyAction,
        onClick: () => nav({ name: "details", id: copyId }),
      },
    });
  };

  // Export .txt file
  const handleExportTxt = () => {
    const blob = new Blob([clip.content], { type: "text/plain;charset=utf-8" });
    const filename = `${clip.title.replace(/[/\\?%*:|"<>]/g, "_") || "clip"}.txt`;
    downloadFile(blob, filename);
    success(t.exportedToast(filename), {
      description: t.exportedDesc,
    });
  };

  // Copy to clipboard
  const handleCopy = () => {
    nativeCopy(clip.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
    success(t.copiedToast, {
      description: t.copiedToastDesc(clip.characterCount),
    });
  };

  // Share
  const handleShare = () => {
    const shared = nativeShare(clip.content, clip.title);
    if (!shared) {
      handleCopy();
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
    toast(t.renamedToast);
  };

  // Delete commit
  const handleDeleteCommit = () => {
    onUpdateItems(items.filter((c) => c.id !== id));
    nav({ name: "library" });
    toast(t.deletedToast);
  };

  const actionButtons = [
    {
      label: t.actionEdit,
      icon: Pencil,
      onClick: () => nav({ name: "editor", id: clip.id }),
    },
    {
      label: clip.pinned ? t.actionUnpin : t.actionPin,
      icon: clip.pinned ? PinOff : Pin,
      onClick: handleTogglePinned,
    },
    {
      label: t.actionRename,
      icon: Type,
      onClick: () => {
        setNewTitle(clip.title);
        setRenaming(true);
      },
    },
    {
      label: t.actionDuplicate,
      icon: CopyPlus,
      onClick: handleDuplicate,
    },
    {
      label: t.actionExportTxt,
      icon: Download,
      onClick: handleExportTxt,
    },
    {
      label: t.actionShare,
      icon: Share2,
      onClick: handleShare,
    },
  ];

  return (
    <div className="flex h-full max-h-full w-full flex-col bg-background overflow-hidden">
      <TopBar
        left={
          <IconBtn label={ui.common.back} onClick={() => nav({ name: "library" })}>
            <ArrowLeft className="h-5 w-5 rtl:rotate-180" />
          </IconBtn>
        }
        title={t.title}
        right={
          <IconBtn
            label={t.deleteItemAria}
            className="text-destructive hover:bg-destructive/10"
            onClick={() => setConfirmDelete(true)}
          >
            <Trash2 className="h-5 w-5" />
          </IconBtn>
        }
      />

      <div className="flex-1 min-h-0 overflow-y-auto space-y-3 px-4 py-3 text-start">
        {/* Title, Expiry Lock, and Pin status */}
        <div>
          <div className="flex flex-wrap items-start gap-2">
            <h1 dir="auto" className="min-w-0 flex-1 text-xl font-bold leading-snug tracking-tight text-foreground">
              {clip.title}
            </h1>
            <div className="mt-1 flex flex-wrap items-center gap-1.5 shrink-0">
              {clip.isExpiryLocked && (
                <span className="inline-flex items-center gap-1 rounded-full border border-border bg-surface-2 px-2.5 py-0.5 text-xs font-semibold text-foreground/90">
                  <Lock className="h-3 w-3 text-mint" aria-hidden />
                  {t.lockedBadge}
                </span>
              )}
              {clip.pinned && (
                <span className="inline-flex items-center gap-1 rounded-full bg-mint-soft px-2.5 py-0.5 text-xs font-semibold text-mint-foreground">
                  <Pin className="h-3 w-3" />
                  {t.pinnedBadge}
                </span>
              )}
            </div>
          </div>

          {/* 4-Grid Metadata */}
          <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 rounded-xl border border-border bg-surface p-3 text-xs">
            <MetaItem label={t.modified} value={fullDateTime(clip.modifiedAt, locale)} />
            <MetaItem label={t.created} value={fullDateTime(clip.createdAt, locale)} />
            <MetaItem
              label={t.size}
              value={t.sizeValue(clip.characterCount, formatBytes(clip.sizeBytes))}
            />
            <MetaItem
              label={t.contentMeta}
              value={t.contentValue(words, lines)}
            />
          </dl>

          {/* Per-item Expiry Protection Toggle */}
          <div className="mt-2.5 flex items-center justify-between gap-3 rounded-xl border border-border bg-surface px-3 py-2.5">
            <div className="flex min-w-0 items-center gap-2.5">
              <div
                className={cn(
                  "grid h-8 w-8 shrink-0 place-items-center rounded-lg",
                  clip.isExpiryLocked
                    ? "bg-mint-soft text-mint"
                    : "bg-surface-2 text-muted-foreground"
                )}
              >
                {clip.isExpiryLocked ? (
                  <Lock className="h-4 w-4" aria-hidden />
                ) : (
                  <Unlock className="h-4 w-4" aria-hidden />
                )}
              </div>
              <div className="min-w-0">
                <p className="text-xs font-semibold text-foreground">
                  {clip.isExpiryLocked ? t.expiryLockedTitle : t.expiryTitle}
                </p>
                <p className="truncate text-[11px] text-muted-foreground">
                  {clip.isExpiryLocked
                    ? t.expiryLockedDesc
                    : clip.pinned
                      ? t.expiryPinnedDesc
                      : clip.expiresAt
                        ? t.expiresAtDesc(fullDateTime(clip.expiresAt, locale))
                        : hasFiniteRetention(retention)
                          ? t.followsRetentionDesc
                          : t.foreverRetentionDesc}
                </p>
              </div>
            </div>
            <Btn
              size="sm"
              variant={clip.isExpiryLocked ? "outline" : "mint"}
              onClick={handleToggleExpiryLock}
              className="shrink-0 text-xs"
            >
              {clip.isExpiryLocked ? t.unlockExpiry : t.lockFromExpiry}
            </Btn>
          </div>
        </div>

        {/* Content Viewer */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-xs text-muted-foreground px-1">
            <span>{t.storedUtf8Content}</span>
            <span className="font-mono">{formatBytes(clip.sizeBytes)}</span>
          </div>
          <div className="max-h-[44vh] overflow-y-auto rounded-xl border border-border bg-surface p-4 shadow-2xs">
            <p dir="auto" className="whitespace-pre-wrap break-words text-[13.5px] leading-relaxed text-foreground select-text font-sans">
              {clip.content || <span className="text-muted-foreground italic">{t.emptyTextSnippet}</span>}
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
          {ui.common.delete}
        </Btn>
        <Btn
          variant="primary"
          className="flex-1 gap-1.5"
          onClick={handleCopy}
        >
          {copied ? <Check className="h-4 w-4 text-mint" /> : <Copy className="h-4 w-4" />}
          {copied ? t.copiedBtn : t.copyToClipboard}
        </Btn>
      </div>

      {/* Rename Modal */}
      {renaming && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs"
        >
          <div className="w-full max-w-sm rounded-2xl border border-border bg-surface p-5 shadow-2xl text-start">
            <h3 className="text-base font-semibold">{t.renameTitle}</h3>
            <form onSubmit={handleRenameCommit} className="mt-4 space-y-4">
              <div>
                <label htmlFor="rename-input" className="text-xs font-medium text-muted-foreground">
                  {t.titleLabel}
                </label>
                <input
                  id="rename-input"
                  dir="auto"
                  autoFocus
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  className="mt-1 h-11 w-full rounded-lg border border-input bg-surface px-3 text-sm outline-none focus:border-mint focus:ring-2 focus:ring-ring/30"
                />
              </div>
              <div className="flex justify-end gap-2">
                <Btn variant="ghost" size="sm" onClick={() => setRenaming(false)}>
                  {ui.common.cancel}
                </Btn>
                <Btn type="submit" variant="primary" size="sm" disabled={!newTitle.trim()}>
                  {ui.common.save}
                </Btn>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Confirm Delete Modal */}
      <ConfirmModal
        open={confirmDelete}
        title={t.deleteModalTitle}
        description={t.deleteModalDesc(clip.title)}
        confirmLabel={ui.common.delete}
        onConfirm={handleDeleteCommit}
        onClose={() => setConfirmDelete(false)}
      />
    </div>
  );
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 text-start">
      <dt className="text-muted-foreground/80 text-[11px] uppercase tracking-wider">{label}</dt>
      <dd className="truncate font-medium text-foreground text-[12px] mt-0.5">{value}</dd>
    </div>
  );
}
