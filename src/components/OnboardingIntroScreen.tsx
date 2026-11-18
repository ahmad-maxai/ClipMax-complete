import React, { useEffect, useState } from "react";
import { ArrowRight, Keyboard, Lock, Sparkles } from "lucide-react";
import { motion } from "motion/react";
import { Btn, ClipMaxLogo } from "./primitives";

interface OnboardingIntroScreenProps {
  reducedMotion: boolean;
  onNext: () => void;
  onSkip: () => void;
}

const INTRO_HIGHLIGHTS = [
  {
    icon: Sparkles,
    title: "Your clips, always ready",
    description: "Save snippets, links, and templates once and find them instantly.",
  },
  {
    icon: Keyboard,
    title: "Built right into your keyboard",
    description: "Paste saved clips directly inside Chrome, YouTube, messages, and more.",
  },
  {
    icon: Lock,
    title: "Private on your device",
    description: "No accounts, no cloud uploads — everything stays safely on your phone.",
  },
] as const;

const CYCLE_DURATION_MS = 5000;

export function OnboardingIntroScreen({
  reducedMotion,
  onNext,
  onSkip,
}: OnboardingIntroScreenProps) {
  const [activeIndex, setActiveIndex] = useState(0);
  const [cycleProgress, setCycleProgress] = useState(0);

  // Subtle non-blocking 5-second visual cycle across highlights
  useEffect(() => {
    if (reducedMotion) {
      setCycleProgress(100);
      return;
    }

    let start = performance.now();
    let rafId = 0;

    const tick = (now: number) => {
      const elapsed = now - start;
      const pct = Math.min(100, (elapsed / CYCLE_DURATION_MS) * 100);
      setCycleProgress(pct);

      if (elapsed >= CYCLE_DURATION_MS) {
        start = now;
        setCycleProgress(0);
        setActiveIndex((prev) => (prev + 1) % INTRO_HIGHLIGHTS.length);
      }
      rafId = requestAnimationFrame(tick);
    };

    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [reducedMotion]);

  return (
    <div className="relative flex h-[100dvh] max-h-[100dvh] w-full flex-col bg-background text-foreground overflow-hidden">
      {/* Subtle atmospheric mint glow */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 top-0 h-72 bg-gradient-to-b from-mint/10 via-mint/5 to-transparent"
      />

      {/* Top Bar with unobtrusive Skip action */}
      <div className="relative z-10 flex items-center justify-between px-4 pt-3 pb-1 shrink-0">
        <span className="rounded-md bg-mint-soft px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-mint-foreground">
          v0.5.1
        </span>
        <button
          type="button"
          onClick={onSkip}
          className="min-h-[44px] min-w-[44px] px-3 rounded-lg text-sm font-semibold text-muted-foreground hover:text-foreground hover:bg-accent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          Skip
        </button>
      </div>

      {/* Main Centered Welcome Content */}
      <div className="relative z-10 flex-1 min-h-0 overflow-y-auto px-6 flex flex-col items-center justify-center py-4">
        <motion.div
          initial={reducedMotion ? false : { opacity: 0, scale: 0.9, y: 12 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          transition={
            reducedMotion
              ? { duration: 0 }
              : { duration: 0.45, ease: [0.22, 1, 0.36, 1] }
          }
          className="relative flex items-center justify-center"
        >
          {!reducedMotion && (
            <span
              aria-hidden
              className="absolute -inset-3 rounded-[28%] bg-mint/15 blur-xl animate-pulse"
            />
          )}
          <ClipMaxLogo size={88} className="relative shadow-lg border-border" />
        </motion.div>

        <motion.div
          initial={reducedMotion ? false : { opacity: 0, y: 14 }}
          animate={{ opacity: 1, y: 0 }}
          transition={
            reducedMotion
              ? { duration: 0 }
              : { duration: 0.45, delay: 0.08, ease: [0.22, 1, 0.36, 1] }
          }
          className="mt-5 text-center max-w-sm"
        >
          <h1 className="text-2xl font-bold tracking-tight text-foreground">
            Welcome to ClipMax
          </h1>
          <p className="mt-2.5 text-sm leading-relaxed text-muted-foreground">
            Save text you reuse every day and paste it in a single tap right from your keyboard.
          </p>
        </motion.div>

        {/* Highlights list */}
        <motion.div
          initial={reducedMotion ? false : { opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={
            reducedMotion
              ? { duration: 0 }
              : { duration: 0.45, delay: 0.16, ease: [0.22, 1, 0.36, 1] }
          }
          className="mt-6 w-full max-w-sm space-y-2.5"
        >
          {INTRO_HIGHLIGHTS.map((item, idx) => {
            const Icon = item.icon;
            const isHighlighted = !reducedMotion && activeIndex === idx;
            return (
              <div
                key={item.title}
                onClick={() => {
                  setActiveIndex(idx);
                  setCycleProgress(0);
                }}
                className={`flex items-start gap-3 rounded-2xl border px-3.5 py-3 transition-colors duration-300 ${
                  isHighlighted
                    ? "border-mint/50 bg-mint-soft/40 shadow-2xs"
                    : "border-border bg-surface/90"
                }`}
              >
                <span
                  className={`mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-xl transition-colors duration-300 ${
                    isHighlighted
                      ? "bg-mint text-primary-foreground dark:text-background"
                      : "bg-secondary text-mint"
                  }`}
                >
                  <Icon className="h-4 w-4" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-[13px] font-semibold text-foreground leading-snug">
                    {item.title}
                  </p>
                  <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
                    {item.description}
                  </p>
                </div>
              </div>
            );
          })}
        </motion.div>
      </div>

      {/* Bottom Sticky CTA + Non-blocking 5s Progress Indicator */}
      <div className="relative z-10 shrink-0 px-5 pt-2 pb-5 bg-gradient-to-t from-background via-background to-transparent">
        <div className="mx-auto w-full max-w-sm space-y-3">
          {/* Subtle non-blocking 5s highlight cycle progress */}
          <div className="flex items-center gap-2 px-1" aria-hidden>
            <div className="flex items-center gap-1.5">
              {INTRO_HIGHLIGHTS.map((_, idx) => (
                <span
                  key={idx}
                  className={`h-1.5 rounded-full transition-all duration-300 ${
                    activeIndex === idx ? "w-5 bg-mint" : "w-1.5 bg-border"
                  }`}
                />
              ))}
            </div>
            <div className="h-1 flex-1 overflow-hidden rounded-full bg-secondary">
              <div
                className="h-full rounded-full bg-mint/80 transition-[width] duration-75"
                style={{ width: `${cycleProgress}%` }}
              />
            </div>
          </div>

          <Btn
            variant="mint"
            className="h-12 w-full rounded-xl text-sm font-bold shadow-md"
            onClick={onNext}
          >
            <span>Next</span>
            <ArrowRight className="h-4 w-4" />
          </Btn>
        </div>
      </div>
    </div>
  );
}
