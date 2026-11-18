import React, { useEffect } from "react";
import { Code2, ExternalLink, Github, ShieldCheck, X } from "lucide-react";
import { Btn, ClipMaxLogo, IconBtn } from "./primitives";
import { isNativeAndroid, nativeOpenExternalUrl } from "../lib/nativeBridge";

export const CLIPMAX_GITHUB_URL = "https://github.com/ahmad-maxai/ClipMax-complete";

interface AboutClipMaxModalProps {
  open: boolean;
  onClose: () => void;
}

export function AboutClipMaxModal({ open, onClose }: AboutClipMaxModalProps) {
  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  const handleGitHubClick = (e: React.MouseEvent<HTMLAnchorElement>) => {
    if (isNativeAndroid()) {
      e.preventDefault();
      nativeOpenExternalUrl(CLIPMAX_GITHUB_URL);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="about-clipmax-title"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs animate-in fade-in duration-150"
    >
      <div className="w-full max-w-sm max-h-[86dvh] flex flex-col rounded-3xl border border-border bg-surface text-foreground shadow-2xl overflow-hidden animate-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="flex items-start justify-between gap-3 px-5 pt-5 pb-3 border-b border-border/70">
          <div className="flex items-center gap-3 min-w-0">
            <ClipMaxLogo size={46} className="shadow-xs" />
            <div className="min-w-0">
              <h2
                id="about-clipmax-title"
                className="text-base font-bold tracking-tight text-foreground leading-tight"
              >
                About ClipMax
              </h2>
              <p className="text-[11px] font-medium text-muted-foreground mt-0.5">
                Open-source · GPL-3.0-or-later
              </p>
            </div>
          </div>
          <IconBtn
            label="Close About ClipMax"
            onClick={onClose}
            className="-mr-1.5 -mt-1.5 h-9 w-9 min-h-[44px] min-w-[44px] text-muted-foreground hover:text-foreground"
          >
            <X className="h-4.5 w-4.5" />
          </IconBtn>
        </div>

        {/* Scrollable Content Body */}
        <div className="flex-1 min-h-0 overflow-y-auto px-5 py-4 space-y-3.5 text-left">
          <div className="space-y-2 text-[13px] leading-relaxed text-muted-foreground">
            <p className="text-foreground/95">
              ClipMax is an independent, open-source clipboard and keyboard project built to make everyday copying and pasting simpler.
            </p>
            <p>
              It gives your frequently used text, links, and snippets a reliable home, while bringing your saved clips directly into your Android keyboard.
            </p>
          </div>

          <div className="rounded-2xl border border-border bg-surface-2 p-3.5 space-y-1.5">
            <div className="flex items-center gap-2">
              <span className="grid h-6 w-6 shrink-0 place-items-center rounded-lg bg-mint-soft text-mint-foreground">
                <ShieldCheck className="h-3.5 w-3.5 text-mint" />
              </span>
              <h3 className="text-xs font-bold text-foreground">
                Built with a local-first philosophy
              </h3>
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground">
              Your clips stay on your device. ClipMax is designed without accounts, unnecessary cloud uploads, or telemetry.
            </p>
          </div>

          <div className="rounded-2xl border border-border bg-surface-2 p-3.5 space-y-1.5">
            <div className="flex items-center gap-2">
              <span className="grid h-6 w-6 shrink-0 place-items-center rounded-lg bg-mint-soft text-mint-foreground">
                <Code2 className="h-3.5 w-3.5 text-mint" />
              </span>
              <h3 className="text-xs font-bold text-foreground">
                Open source. Built to improve.
              </h3>
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground">
              ClipMax is developed as an open-source project. Its source code is available for people to inspect, learn from, contribute to, and help improve.
            </p>
            <p className="text-[11px] font-medium text-muted-foreground/90 pt-0.5">
              Licensed under GNU General Public License v3.0 or later (GPL-3.0-or-later).
            </p>
          </div>

          {/* Secondary Repository Action */}
          <div className="pt-0.5 space-y-1.5">
            <a
              href={CLIPMAX_GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              onClick={handleGitHubClick}
              className="flex min-h-[44px] w-full items-center justify-between gap-2 rounded-xl border border-border bg-surface-2 px-3.5 py-2.5 text-xs font-semibold text-foreground hover:bg-accent active:scale-[0.98] transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <span className="flex items-center gap-2 min-w-0">
                <Github className="h-4 w-4 shrink-0 text-mint" />
                <span className="truncate">View source on GitHub</span>
              </span>
              <ExternalLink className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
            </a>
            <p className="text-center text-[11px] text-muted-foreground">
              Explore the code, report issues, or contribute.
            </p>
          </div>
        </div>

        {/* Footer Close Action */}
        <div className="shrink-0 border-t border-border/70 px-5 py-3 bg-surface">
          <Btn
            variant="subtle"
            size="sm"
            onClick={onClose}
            className="h-10 w-full rounded-xl font-semibold"
          >
            Close
          </Btn>
        </div>
      </div>
    </div>
  );
}
