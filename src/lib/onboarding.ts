import { isNativeAndroid } from "./nativeBridge";

export type OnboardingStep =
  | "INTRODUCTION"
  | "ENABLE_KEYBOARD"
  | "SELECT_KEYBOARD"
  | "COMPLETION";

export interface OnboardingState {
  completed: boolean;
  step: OnboardingStep;
  imeEnabled: boolean;
  imeSelected: boolean;
  attemptedEnable: boolean;
  attemptedSelect: boolean;
}

const LS_KEY_COMPLETED = "clipmax_onboarding_completed";
const LS_KEY_STEP = "clipmax_onboarding_step";

const DEFAULT_INCOMPLETE_STATE: OnboardingState = {
  completed: false,
  step: "INTRODUCTION",
  imeEnabled: false,
  imeSelected: false,
  attemptedEnable: false,
  attemptedSelect: false,
};

export function normalizeOnboardingStep(raw?: string | null): OnboardingStep {
  const upper = (raw ?? "").trim().toUpperCase();
  if (
    upper === "INTRODUCTION" ||
    upper === "ENABLE_KEYBOARD" ||
    upper === "SELECT_KEYBOARD" ||
    upper === "COMPLETION"
  ) {
    return upper;
  }
  return "INTRODUCTION";
}

export function parseOnboardingStateJson(raw: unknown): OnboardingState {
  try {
    const obj = typeof raw === "string" ? JSON.parse(raw) : raw;
    if (!obj || typeof obj !== "object") return DEFAULT_INCOMPLETE_STATE;
    const rec = obj as Record<string, unknown>;
    return {
      completed: Boolean(rec.completed),
      step: normalizeOnboardingStep(typeof rec.step === "string" ? rec.step : "INTRODUCTION"),
      imeEnabled: Boolean(rec.imeEnabled),
      imeSelected: Boolean(rec.imeSelected),
      attemptedEnable: Boolean(rec.attemptedEnable),
      attemptedSelect: Boolean(rec.attemptedSelect),
    };
  } catch {
    return DEFAULT_INCOMPLETE_STATE;
  }
}

export function getOnboardingState(): OnboardingState {
  if (isNativeAndroid() && window.ClipMaxNative?.getOnboardingState) {
    try {
      const raw = window.ClipMaxNative.getOnboardingState();
      return parseOnboardingStateJson(raw);
    } catch {
      return DEFAULT_INCOMPLETE_STATE;
    }
  }

  try {
    const completed = localStorage.getItem(LS_KEY_COMPLETED) === "true";
    const step = normalizeOnboardingStep(localStorage.getItem(LS_KEY_STEP));
    return {
      completed,
      step: completed ? "COMPLETION" : step,
      imeEnabled: false,
      imeSelected: false,
      attemptedEnable: false,
      attemptedSelect: false,
    };
  } catch {
    return DEFAULT_INCOMPLETE_STATE;
  }
}

export function advanceOnboardingFromIntro(): OnboardingState {
  if (isNativeAndroid() && window.ClipMaxNative?.advanceOnboardingFromIntro) {
    try {
      const raw = window.ClipMaxNative.advanceOnboardingFromIntro();
      return parseOnboardingStateJson(raw);
    } catch {
      return { ...DEFAULT_INCOMPLETE_STATE, step: "ENABLE_KEYBOARD" };
    }
  }

  try {
    localStorage.setItem(LS_KEY_COMPLETED, "false");
    localStorage.setItem(LS_KEY_STEP, "ENABLE_KEYBOARD");
  } catch {}
  return {
    ...DEFAULT_INCOMPLETE_STATE,
    step: "ENABLE_KEYBOARD",
  };
}

export function returnOnboardingToIntro(): OnboardingState {
  if (isNativeAndroid() && window.ClipMaxNative?.returnOnboardingToIntro) {
    try {
      const raw = window.ClipMaxNative.returnOnboardingToIntro();
      return parseOnboardingStateJson(raw);
    } catch {}
  }

  try {
    localStorage.setItem(LS_KEY_COMPLETED, "false");
    localStorage.setItem(LS_KEY_STEP, "INTRODUCTION");
  } catch {}
  return {
    ...DEFAULT_INCOMPLETE_STATE,
    step: "INTRODUCTION",
  };
}

export function completeOnboarding(): OnboardingState {
  if (isNativeAndroid() && window.ClipMaxNative?.completeOnboarding) {
    try {
      const raw = window.ClipMaxNative.completeOnboarding();
      return parseOnboardingStateJson(raw);
    } catch {}
  }

  try {
    localStorage.setItem(LS_KEY_COMPLETED, "true");
    localStorage.setItem(LS_KEY_STEP, "COMPLETION");
  } catch {}
  return {
    completed: true,
    step: "COMPLETION",
    imeEnabled: true,
    imeSelected: true,
    attemptedEnable: false,
    attemptedSelect: false,
  };
}

export function isReducedMotionPreferred(): boolean {
  try {
    if (isNativeAndroid() && window.ClipMaxNative?.areAnimationsReduced?.()) {
      return true;
    }
    if (typeof window !== "undefined" && window.matchMedia) {
      return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    }
  } catch {}
  return false;
}
