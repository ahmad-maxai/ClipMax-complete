import React from "react";
import { Lock, X } from "lucide-react";
import { Btn } from "./primitives";

interface ExpiryReminderBannerProps {
  itemIds: string[];
  onLockClips: () => void;
  onKeepExpiry: () => void;
  onDismiss: () => void;
}

export function ExpiryReminderBanner({
  itemIds,
  onLockClips,
  onKeepExpiry,
  onDismiss,
}: ExpiryReminderBannerProps) {
  if (itemIds.length === 0) return null;

  const count = itemIds.length;
  const isBatch = count > 1;

  return (
    <div
      role="status"
      aria-live="polite"
      className="pointer-events-none fixed bottom-16 left-1/2 z-40 w-[calc(100%-2rem)] max-w-md -translate-x-1/2"
    >
      <div className="pointer-events-auto rounded-2xl border border-border bg-surface p-3.5 shadow-xl dark:bg-surface-2">
        <div className="flex items-start gap-2.5">
          <div className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-mint-soft text-mint">
            <Lock className="h-3.5 w-3.5" aria-hidden />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-[13px] font-semibold leading-snug text-foreground">
              {isBatch
                ? `This clip has an expiry time (${count} items)`
                : "This clip has an expiry time"}
            </p>
            <p className="mt-0.5 text-[11.5px] leading-snug text-muted-foreground">
              This clip will be automatically removed according to your current retention settings.
            </p>
            <div className="mt-2.5 flex flex-wrap items-center gap-2">
              <Btn
                size="sm"
                variant="primary"
                onClick={onLockClips}
                className="h-8 gap-1.5 px-3 text-xs"
              >
                <Lock className="h-3 w-3" aria-hidden />
                {isBatch ? `Lock all (${count})` : "Lock clip"}
              </Btn>
              <Btn
                size="sm"
                variant="outline"
                onClick={onKeepExpiry}
                className="h-8 px-3 text-xs"
              >
                Keep expiry
              </Btn>
            </div>
          </div>
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Dismiss expiry reminder"
            className="grid h-6 w-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
}
