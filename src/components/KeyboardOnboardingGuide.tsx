import React, { useCallback, useEffect, useId, useRef, useState } from "react";
import { AlertCircle, ArrowUp, CheckCircle2, Keyboard } from "lucide-react";
import { motion } from "motion/react";
import { Btn } from "./primitives";
import { OnboardingState } from "../lib/onboarding";
import { getUiText, useAppLocale } from "../lib/i18n";

interface KeyboardOnboardingGuideProps {
  state: OnboardingState;
  scrollContainerRef: React.RefObject<HTMLDivElement | null>;
  sectionRef: React.RefObject<HTMLElement | null>;
  enableBtnRef: React.RefObject<HTMLElement | null>;
  selectBtnRef: React.RefObject<HTMLElement | null>;
  expirySectionRef: React.RefObject<HTMLElement | null>;
  reducedMotion: boolean;
  onSkip: () => void;
  onComplete: () => void;
}

interface TargetRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export function KeyboardOnboardingGuide({
  state,
  scrollContainerRef,
  sectionRef,
  enableBtnRef,
  selectBtnRef,
  expirySectionRef,
  reducedMotion,
  onSkip,
  onComplete,
}: KeyboardOnboardingGuideProps) {
  const locale = useAppLocale();
  const ui = getUiText(locale);
  const t = ui.onboardingGuide;
  const maskId = useId();
  const [targetRect, setTargetRect] = useState<TargetRect | null>(null);
  const [viewport, setViewport] = useState(() => ({
    width: typeof window !== "undefined" ? window.innerWidth : 360,
    height: typeof window !== "undefined" ? window.innerHeight : 720,
  }));
  const [isAutoScrolling, setIsAutoScrolling] = useState(false);
  const [scrolledAway, setScrolledAway] = useState(false);
  const [returnRequestCount, setReturnRequestCount] = useState(0);
  const [justEnabledAck, setJustEnabledAck] = useState(false);
  const [justSelectedAck, setJustSelectedAck] = useState(false);

  const prevImeEnabledRef = useRef<boolean>(state.imeEnabled);
  const prevImeSelectedRef = useRef<boolean>(state.imeSelected);
  const autoScrollingRef = useRef<boolean>(false);
  const userCanceledScrollRef = useRef<boolean>(false);

  const isCompletion = state.step === "COMPLETION";
  const isConfigureExpiry = state.step === "CONFIGURE_EXPIRY";
  const activeBtnRef =
    state.step === "SELECT_KEYBOARD"
      ? selectBtnRef
      : isConfigureExpiry
      ? expirySectionRef
      : enableBtnRef;

  // Detect Stage 1 -> Stage 2 and Stage 2 -> Stage 3 transitions for subtle acknowledgements
  useEffect(() => {
    const prevEnabled = prevImeEnabledRef.current;
    prevImeEnabledRef.current = state.imeEnabled;
    if (!prevEnabled && state.imeEnabled && state.step === "SELECT_KEYBOARD") {
      setJustEnabledAck(true);
    } else if (state.step !== "SELECT_KEYBOARD") {
      setJustEnabledAck(false);
    }

    const prevSelected = prevImeSelectedRef.current;
    prevImeSelectedRef.current = state.imeSelected;
    if (!prevSelected && state.imeSelected && state.step === "CONFIGURE_EXPIRY") {
      setJustSelectedAck(true);
    } else if (state.step !== "CONFIGURE_EXPIRY") {
      setJustSelectedAck(false);
    }
  }, [state.imeEnabled, state.imeSelected, state.step]);

  const measureTarget = useCallback(() => {
    if (typeof window === "undefined") return;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    setViewport({ width: vw, height: vh });

    if (isCompletion) {
      setTargetRect(null);
      setScrolledAway(false);
      return;
    }

    const btnEl = activeBtnRef.current;
    const scrollEl = scrollContainerRef.current;
    if (!btnEl || !scrollEl) {
      setTargetRect(null);
      return;
    }

    const rect = btnEl.getBoundingClientRect();
    const containerRect = scrollEl.getBoundingClientRect();

    // Verify target button is comfortably inside the scrollable viewport bounds
    const visibleTop = Math.max(containerRect.top + 4, 48);
    const visibleBottom = Math.min(containerRect.bottom - 4, vh - 4);
    const isComfortablyVisible =
      rect.width > 0 &&
      rect.height > 0 &&
      rect.top >= visibleTop &&
      rect.bottom <= visibleBottom;

    if (!isComfortablyVisible) {
      setScrolledAway(true);
      setTargetRect(null);
      return;
    }

    setScrolledAway(false);
    setTargetRect({
      top: rect.top,
      left: rect.left,
      width: rect.width,
      height: rect.height,
    });
  }, [activeBtnRef, isCompletion, scrollContainerRef]);

  // Auto-scroll smoothly to bring the Android System Keyboard section into comfortable view
  useEffect(() => {
    if (isCompletion) {
      autoScrollingRef.current = false;
      setIsAutoScrolling(false);
      setTargetRect(null);
      setScrolledAway(false);
      return;
    }

    const scrollEl = scrollContainerRef.current;
    if (!scrollEl) return;

    let settleTimer: number | undefined;
    let safetyTimer: number | undefined;
    let rafId = 0;
    userCanceledScrollRef.current = false;

    const finishAutoScroll = () => {
      if (settleTimer !== undefined) window.clearTimeout(settleTimer);
      if (safetyTimer !== undefined) window.clearTimeout(safetyTimer);
      autoScrollingRef.current = false;
      setIsAutoScrolling(false);
      measureTarget();
    };

    const handleUserScrollIntent = () => {
      if (autoScrollingRef.current) {
        userCanceledScrollRef.current = true;
        finishAutoScroll();
      }
    };

    const handleScrollDuringAuto = () => {
      if (!autoScrollingRef.current) return;
      if (settleTimer !== undefined) window.clearTimeout(settleTimer);
      settleTimer = window.setTimeout(finishAutoScroll, 120);
    };

    scrollEl.addEventListener("touchstart", handleUserScrollIntent, { passive: true });
    scrollEl.addEventListener("pointerdown", handleUserScrollIntent, { passive: true });
    scrollEl.addEventListener("wheel", handleUserScrollIntent, { passive: true });
    scrollEl.addEventListener("scroll", handleScrollDuringAuto, { passive: true });
    scrollEl.addEventListener("scrollend", finishAutoScroll, { passive: true });

    rafId = requestAnimationFrame(() => {
      if (userCanceledScrollRef.current) return;
      const anchorEl = isConfigureExpiry
        ? expirySectionRef.current ?? activeBtnRef.current
        : sectionRef.current ?? activeBtnRef.current;
      const btnEl = activeBtnRef.current;
      if (!anchorEl || !btnEl) {
        measureTarget();
        return;
      }

      const containerRect = scrollEl.getBoundingClientRect();
      const anchorRect = anchorEl.getBoundingClientRect();
      const btnRect = btnEl.getBoundingClientRect();

      // Leave comfortable clearance above target for TopBar + guidance card
      const desiredTop = isConfigureExpiry
        ? containerRect.top + Math.max(248, Math.min(340, containerRect.height * 0.46))
        : containerRect.top + Math.max(196, Math.min(270, containerRect.height * 0.42));
      let delta = anchorRect.top - desiredTop;

      // Also ensure active target sits cleanly inside visible bottom padding
      const projectedBtnBottom = btnRect.bottom - delta;
      const maxBtnBottom = containerRect.bottom - (isConfigureExpiry ? 18 : 44);
      if (projectedBtnBottom > maxBtnBottom) {
        delta += projectedBtnBottom - maxBtnBottom;
      }

      const maxScroll = Math.max(0, scrollEl.scrollHeight - scrollEl.clientHeight);
      const targetScrollTop = Math.max(
        0,
        Math.min(maxScroll, scrollEl.scrollTop + delta)
      );

      if (Math.abs(targetScrollTop - scrollEl.scrollTop) < 4 || reducedMotion) {
        if (Math.abs(targetScrollTop - scrollEl.scrollTop) >= 4) {
          scrollEl.scrollTo({ top: targetScrollTop, behavior: "auto" });
        }
        measureTarget();
        return;
      }

      autoScrollingRef.current = true;
      setIsAutoScrolling(true);
      setScrolledAway(false);
      scrollEl.scrollTo({ top: targetScrollTop, behavior: "smooth" });
      settleTimer = window.setTimeout(finishAutoScroll, 140);
      safetyTimer = window.setTimeout(finishAutoScroll, 620);
    });

    return () => {
      cancelAnimationFrame(rafId);
      if (settleTimer !== undefined) window.clearTimeout(settleTimer);
      if (safetyTimer !== undefined) window.clearTimeout(safetyTimer);
      scrollEl.removeEventListener("touchstart", handleUserScrollIntent);
      scrollEl.removeEventListener("pointerdown", handleUserScrollIntent);
      scrollEl.removeEventListener("wheel", handleUserScrollIntent);
      scrollEl.removeEventListener("scroll", handleScrollDuringAuto);
      scrollEl.removeEventListener("scrollend", finishAutoScroll);
    };
  }, [
    state.step,
    returnRequestCount,
    isCompletion,
    isConfigureExpiry,
    reducedMotion,
    scrollContainerRef,
    sectionRef,
    expirySectionRef,
    activeBtnRef,
    measureTarget,
  ]);

  // Continuously track target button position across resize, rotation, and manual scroll
  useEffect(() => {
    const scrollEl = scrollContainerRef.current;
    const btnEl = activeBtnRef.current;
    let frameId = 0;

    const scheduleMeasure = () => {
      if (autoScrollingRef.current) return;
      cancelAnimationFrame(frameId);
      frameId = requestAnimationFrame(measureTarget);
    };

    const handleScroll = () => scheduleMeasure();
    const handleResize = () => scheduleMeasure();

    scrollEl?.addEventListener("scroll", handleScroll, { passive: true });
    window.addEventListener("resize", handleResize);
    window.addEventListener("orientationchange", handleResize);

    const observer =
      typeof ResizeObserver !== "undefined"
        ? new ResizeObserver(scheduleMeasure)
        : null;
    if (observer) {
      if (scrollEl) observer.observe(scrollEl);
      if (btnEl) observer.observe(btnEl);
    }

    scheduleMeasure();

    return () => {
      cancelAnimationFrame(frameId);
      scrollEl?.removeEventListener("scroll", handleScroll);
      window.removeEventListener("resize", handleResize);
      window.removeEventListener("orientationchange", handleResize);
      observer?.disconnect();
    };
  }, [state.step, activeBtnRef, scrollContainerRef, measureTarget]);

  // Phase 6 Completion Card
  if (isCompletion) {
    return (
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t.setupCompleteAria}
        className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-5"
      >
        <motion.div
          initial={reducedMotion ? false : { opacity: 0, scale: 0.94, y: 12 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          transition={
            reducedMotion
              ? { duration: 0 }
              : { duration: 0.28, ease: [0.22, 1, 0.36, 1] }
          }
          className="w-full max-w-sm max-h-full overflow-y-auto rounded-3xl border border-border bg-surface p-6 text-center shadow-2xl"
        >
          <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-mint-soft text-mint">
            <CheckCircle2 className="h-8 w-8" />
          </div>

          <h2 className="mt-4 text-xl font-bold tracking-tight text-foreground">
            {t.allSetTitle}
          </h2>
          <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
            {t.allSetDesc}
          </p>

          <Btn
            variant="mint"
            className="mt-6 h-12 w-full rounded-xl text-sm font-bold shadow-md"
            onClick={onComplete}
          >
            <Keyboard className="h-4 w-4" />
            <span>{t.startUsingBtn}</span>
          </Btn>
        </motion.div>
      </div>
    );
  }

  // User scrolled manually away from the keyboard activation controls: offer a clean return pill
  if (scrolledAway && !isAutoScrolling) {
    return (
      <div className="pointer-events-none fixed inset-x-0 bottom-4 z-40 flex justify-center px-4">
        <div className="pointer-events-auto flex w-full max-w-sm items-center justify-between gap-2 rounded-2xl border border-border bg-surface/95 px-3.5 py-2.5 shadow-xl backdrop-blur-md">
          <div className="min-w-0 flex-1 text-start">
            <p className="text-xs font-bold text-foreground truncate">
              {state.step === "SELECT_KEYBOARD"
                ? t.step2ReturnTitle
                : isConfigureExpiry
                ? t.step3ReturnTitle
                : t.step1ReturnTitle}
            </p>
            <p className="text-[11px] text-muted-foreground truncate">
              {t.scrollBackSubtitle}
            </p>
          </div>
          <Btn
            size="sm"
            variant="mint"
            className="shrink-0 rounded-xl px-3 text-xs font-bold"
            onClick={() => setReturnRequestCount((c) => c + 1)}
          >
            <ArrowUp className="h-3.5 w-3.5" />
            <span>{t.returnBtn}</span>
          </Btn>
          <button
            type="button"
            onClick={onSkip}
            className="min-h-[36px] px-2 text-xs font-semibold text-muted-foreground hover:text-foreground"
          >
            {ui.common.skip}
          </button>
        </div>
      </div>
    );
  }

  const showSpotlight = !isAutoScrolling && targetRect !== null;

  const pad = 6;
  const cutX = targetRect ? Math.max(4, targetRect.left - pad) : 0;
  const cutY = targetRect ? Math.max(4, targetRect.top - pad) : 0;
  const cutW = targetRect
    ? Math.min(viewport.width - cutX - 4, targetRect.width + pad * 2)
    : 0;
  const cutH = targetRect ? targetRect.height + pad * 2 : 0;

  // Decide whether guidance card sits above the target section or below the cutout
  const sectionTop = isConfigureExpiry
    ? cutY
    : sectionRef.current?.getBoundingClientRect().top ??
      (targetRect ? targetRect.top - 96 : viewport.height * 0.45);
  const placeCardAbove = isConfigureExpiry
    ? cutY > viewport.height - (cutY + cutH) && cutY > 210
    : sectionTop > 230;

  const stepLabel =
    state.step === "SELECT_KEYBOARD"
      ? t.step2Badge
      : isConfigureExpiry
      ? t.step3Badge
      : t.step1Badge;
  const heading =
    state.step === "SELECT_KEYBOARD"
      ? t.step2Heading
      : isConfigureExpiry
      ? t.step3Heading
      : t.step1Heading;
  const bodyCopy =
    state.step === "SELECT_KEYBOARD"
      ? t.step2Body
      : isConfigureExpiry
      ? t.step3Body
      : t.step1Body;

  const retryNotice =
    state.step === "ENABLE_KEYBOARD" && state.attemptedEnable && !state.imeEnabled
      ? t.retryEnableNotice
      : state.step === "SELECT_KEYBOARD" &&
        state.attemptedSelect &&
        !state.imeSelected
      ? t.retrySelectNotice
      : null;

  // Hand pointer anchor near bottom-right corner of the highlighted button
  const pointerLeft = targetRect
    ? Math.min(viewport.width - 48, Math.max(16, cutX + cutW - 34))
    : 0;
  const pointerTop = targetRect
    ? Math.min(viewport.height - 52, cutY + cutH + 2)
    : 0;

  return (
    <div
      aria-live="polite"
      className="pointer-events-none fixed inset-0 z-40 overflow-hidden"
    >
      {/* 70% black scrim with clear non-blocking cutout around target button */}
      {showSpotlight && (
        <svg
          width={viewport.width}
          height={viewport.height}
          viewBox={`0 0 ${viewport.width} ${viewport.height}`}
          className="pointer-events-none fixed inset-0 h-full w-full"
          aria-hidden
        >
          <defs>
            <mask id={maskId}>
              <rect
                x="0"
                y="0"
                width={viewport.width}
                height={viewport.height}
                fill="white"
              />
              <rect
                x={cutX}
                y={cutY}
                width={cutW}
                height={cutH}
                rx={12}
                ry={12}
                fill="black"
              />
            </mask>
          </defs>
          <rect
            x="0"
            y="0"
            width={viewport.width}
            height={viewport.height}
            fill="rgba(0, 0, 0, 0.70)"
            mask={`url(#${maskId})`}
          />
        </svg>
      )}

      {/* Subtle crisp spotlight border around the active target button */}
      {showSpotlight && (
        <div
          aria-hidden
          style={{
            top: cutY,
            left: cutX,
            width: cutW,
            height: cutH,
          }}
          className="pointer-events-none fixed rounded-[12px] border-2 border-mint shadow-[0_0_0_2px_rgba(255,255,255,0.45)] clipmax-spotlight-ring-anim"
        />
      )}

      {/* Friendly floating guidance card positioned clear of the spotlight cutout */}
      {showSpotlight && (
        <div
          style={
            placeCardAbove
              ? {
                  bottom: Math.max(
                    16,
                    viewport.height - Math.min(sectionTop, cutY) + 12
                  ),
                }
              : {
                  top: Math.max(
                    12,
                    Math.min(
                      viewport.height - (isConfigureExpiry ? 240 : 185),
                      cutY + cutH + (isConfigureExpiry ? 14 : 46)
                    )
                  ),
                }
          }
          className="pointer-events-auto fixed inset-x-4 mx-auto max-w-md rounded-2xl border border-border bg-surface p-4 shadow-2xl text-start"
        >
          <div className="flex items-center justify-between gap-2">
            <span className="inline-flex items-center rounded-md bg-mint-soft px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-mint-foreground">
              {stepLabel}
            </span>
            <button
              type="button"
              onClick={onSkip}
              className="min-h-[32px] px-2 rounded-md text-xs font-semibold text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
            >
              {ui.common.skip}
            </button>
          </div>

          {justEnabledAck && state.step === "SELECT_KEYBOARD" && (
            <div className="mt-2.5 flex items-center gap-2 rounded-xl bg-mint-soft/75 px-3 py-2 text-xs font-semibold text-mint-foreground">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-mint" />
              <span>{t.justEnabledAck}</span>
            </div>
          )}

          {justSelectedAck && isConfigureExpiry && (
            <div className="mt-2.5 flex items-center gap-2 rounded-xl bg-mint-soft/75 px-3 py-2 text-xs font-semibold text-mint-foreground">
              <CheckCircle2 className="h-4 w-4 shrink-0 text-mint" />
              <span>{t.justSelectedAck}</span>
            </div>
          )}

          <h3 className="mt-2 text-sm font-bold leading-snug text-foreground">
            {heading}
          </h3>
          <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
            {bodyCopy}
          </p>

          {retryNotice && (
            <div className="mt-2.5 flex items-start gap-2 rounded-xl border border-amber-500/35 bg-amber-500/10 px-3 py-2 text-[11px] leading-snug text-foreground">
              <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
              <span>{retryNotice}</span>
            </div>
          )}

          {isConfigureExpiry && (
            <div className="mt-3 flex items-center justify-end gap-2">
              <Btn
                size="sm"
                variant="mint"
                className="h-10 w-full rounded-xl text-xs font-bold shadow-sm"
                onClick={onComplete}
              >
                <CheckCircle2 className="h-4 w-4" />
                <span>{t.finishSetupBtn}</span>
              </Btn>
            </div>
          )}
        </div>
      )}

      {/* White hand pointer angled ~45° moving gently toward the highlighted button */}
      {showSpotlight && !isConfigureExpiry && (
        <div
          aria-hidden
          style={{
            top: pointerTop,
            left: pointerLeft,
            transform: "rotate(-45deg)",
            transformOrigin: "18px 8px",
          }}
          className="pointer-events-none fixed z-50"
        >
          <div className={reducedMotion ? "" : "clipmax-hand-pointer-anim"}>
            <svg
              width="38"
              height="44"
              viewBox="0 0 38 44"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
              style={{
                filter: "drop-shadow(0px 3px 6px rgba(0, 0, 0, 0.55))",
              }}
            >
              {/* Crisp white pointing hand icon */}
              <path
                d="M15 4C15 2.34315 16.3431 1 18 1C19.6569 1 21 2.34315 21 4V17.5C21.5 17.18 22.1 17 22.75 17C24.25 17 25.5 18.05 25.78 19.48C26.25 19.18 26.82 19 27.44 19C28.94 19 30.18 20.06 30.44 21.5C30.88 21.25 31.38 21.1 31.92 21.1C33.62 21.1 35 22.48 35 24.18V30.5C35 37.4 29.4 43 22.5 43H18.8C15.1 43 11.7 41.02 9.85 37.82L4.95 29.36C4.08 27.86 4.6 25.95 6.1 25.08C7.42 24.32 9.1 24.66 10.02 25.86L15 31.5V4Z"
                fill="#FFFFFF"
                stroke="#111916"
                strokeWidth="1.75"
                strokeLinejoin="round"
              />
            </svg>
          </div>
        </div>
      )}
    </div>
  );
}
