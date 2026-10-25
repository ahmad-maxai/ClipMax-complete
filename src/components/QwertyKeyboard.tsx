import React, { useState, useRef, useEffect } from "react";
import { Delete, CornerDownLeft, ArrowUp } from "lucide-react";
import { cn } from "../lib/utils";

export interface QwertyKeyboardProps {
  onKeyPress: (char: string) => void;
  onBackspace: () => void;
  onSpace: () => void;
  onEnter: () => void;
  size?: "compact" | "normal";
  className?: string;
}

type KeyboardMode = "alpha" | "numeric" | "symbols";
type ShiftState = "off" | "shifted" | "caps";

export function QwertyKeyboard({
  onKeyPress,
  onBackspace,
  onSpace,
  onEnter,
  size = "normal",
  className,
}: QwertyKeyboardProps) {
  const [mode, setMode] = useState<KeyboardMode>("alpha");
  const [shiftState, setShiftState] = useState<ShiftState>("off");
  const lastShiftTapRef = useRef<number>(0);
  const backspaceTimerRef = useRef<number | null>(null);
  const backspaceIntervalRef = useRef<number | null>(null);

  // Clean up repeat backspace on unmount
  useEffect(() => {
    return () => {
      if (backspaceTimerRef.current) window.clearTimeout(backspaceTimerRef.current);
      if (backspaceIntervalRef.current) window.clearInterval(backspaceIntervalRef.current);
    };
  }, []);

  // Standard QWERTY Layouts (Exact 26 letters: 10 + 9 + 7)
  const alphaRow1 = ["Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"];
  const alphaRow2 = ["A", "S", "D", "F", "G", "H", "J", "K", "L"];
  const alphaRow3 = ["Z", "X", "C", "V", "B", "N", "M"];
  const alphaHints = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"];

  // Numeric / Common Symbols Layout (?123)
  const numRow1 = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"];
  const numRow2 = ["@", "#", "$", "%", "&", "-", "+", "(", ")", "/"];
  const numRow3 = ["*", "\"", "'", ":", ";", "!", "?"];

  // Secondary Symbols Layout (=\<)
  const symRow1 = ["~", "`", "|", "•", "√", "π", "÷", "×", "§", "Δ"];
  const symRow2 = ["£", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\"];
  const symRow3 = ["<", ">", "%", "©", "®", "™", "[", "]"];

  const handleShiftClick = () => {
    const now = Date.now();
    if (now - lastShiftTapRef.current < 300) {
      // Double tap -> Caps Lock
      setShiftState((prev) => (prev === "caps" ? "off" : "caps"));
    } else {
      // Single tap -> Toggle Shifted
      setShiftState((prev) => {
        if (prev === "off") return "shifted";
        if (prev === "shifted") return "off";
        return "off";
      });
    }
    lastShiftTapRef.current = now;
  };

  const handleKeyClick = (char: string) => {
    if (mode === "alpha") {
      const output =
        shiftState === "off" ? char.toLowerCase() : char.toUpperCase();
      onKeyPress(output);
      // Auto-revert single-shifted state after typing a letter
      if (shiftState === "shifted") {
        setShiftState("off");
      }
    } else {
      onKeyPress(char);
    }
  };

  // Continuous Backspace handler
  const startBackspace = () => {
    onBackspace();
    if (backspaceTimerRef.current) window.clearTimeout(backspaceTimerRef.current);
    if (backspaceIntervalRef.current) window.clearInterval(backspaceIntervalRef.current);

    backspaceTimerRef.current = window.setTimeout(() => {
      backspaceIntervalRef.current = window.setInterval(() => {
        onBackspace();
      }, 55);
    }, 350);
  };

  const stopBackspace = () => {
    if (backspaceTimerRef.current) {
      window.clearTimeout(backspaceTimerRef.current);
      backspaceTimerRef.current = null;
    }
    if (backspaceIntervalRef.current) {
      window.clearInterval(backspaceIntervalRef.current);
      backspaceIntervalRef.current = null;
    }
  };

  const isUppercase = shiftState !== "off";

  // Responsive heights (Never use .tap-target to avoid forced 48px min-width)
  const keyHeightClass = size === "compact" ? "h-9" : "h-10 sm:h-11";
  const bottomKeyHeightClass = size === "compact" ? "h-9" : "h-10 sm:h-11";
  const fontSizeClass = size === "compact" ? "text-sm" : "text-sm sm:text-base";

  // Reusable Theme-Aware Style Tokens
  const keyBaseStyles =
    "relative min-w-0 rounded-[6px] sm:rounded-[7px] border font-medium shadow-[0_1px_2px_rgba(0,0,0,0.08)] dark:shadow-[0_1px_2px_rgba(0,0,0,0.4)] active:scale-[0.96] transition-transform flex items-center justify-center select-none cursor-pointer";

  const standardKeyTheme =
    "bg-white dark:bg-[#223029] text-[#111827] dark:text-[#EAF1ED] border-[#D1D9D4] dark:border-[#2C3D35] hover:bg-[#F3F6F4] dark:hover:bg-[#283830] active:bg-mint/20 active:text-[#0F766E] dark:active:text-mint";

  const modifierKeyTheme =
    "bg-[#DFE5E1] dark:bg-[#1A2520] text-[#374151] dark:text-[#9CB3A9] border-[#CFD8D2] dark:border-[#26372F] hover:bg-[#D5DCD7] dark:hover:bg-[#202D27]";

  return (
    <div
      className={cn(
        "w-full max-w-full select-none bg-[#ECEFF1] dark:bg-[#151C18] p-1 sm:p-1.5 box-border flex flex-col gap-1 sm:gap-1.5 font-sans border-t border-border dark:border-[#26372F]",
        className
      )}
      role="region"
      aria-label="QWERTY Keyboard"
    >
      {/* ================= ROW 1 ================= */}
      {mode === "alpha" && (
        <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border">
          {alphaRow1.map((k, idx) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
              aria-label={isUppercase ? k.toUpperCase() : k.toLowerCase()}
            >
              <span className="font-semibold">
                {isUppercase ? k.toUpperCase() : k.toLowerCase()}
              </span>
              {/* Secondary number hint: Q=1, W=2 ... P=0 */}
              <span className="absolute top-0.5 right-1 text-[8px] sm:text-[9px] font-mono text-[#8A9992] dark:text-[#7A8E85] pointer-events-none opacity-80">
                {alphaHints[idx]}
              </span>
            </button>
          ))}
        </div>
      )}

      {mode === "numeric" && (
        <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border">
          {numRow1.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
            >
              <span className="font-semibold">{k}</span>
            </button>
          ))}
        </div>
      )}

      {mode === "symbols" && (
        <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border">
          {symRow1.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
            >
              <span className="font-semibold">{k}</span>
            </button>
          ))}
        </div>
      )}

      {/* ================= ROW 2 (CENTERED) ================= */}
      {mode === "alpha" && (
        <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border">
          {/* Half-key proportional spacer on left to center Row 2 under Row 1 */}
          <div className="w-[4.6%] shrink-0 pointer-events-none" aria-hidden="true" />
          {alphaRow2.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
              aria-label={isUppercase ? k.toUpperCase() : k.toLowerCase()}
            >
              <span className="font-semibold">
                {isUppercase ? k.toUpperCase() : k.toLowerCase()}
              </span>
            </button>
          ))}
          {/* Half-key proportional spacer on right to center Row 2 under Row 1 */}
          <div className="w-[4.6%] shrink-0 pointer-events-none" aria-hidden="true" />
        </div>
      )}

      {mode === "numeric" && (
        <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border">
          {numRow2.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
            >
              <span className="font-semibold">{k}</span>
            </button>
          ))}
        </div>
      )}

      {mode === "symbols" && (
        <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border">
          {symRow2.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
            >
              <span className="font-semibold">{k}</span>
            </button>
          ))}
        </div>
      )}

      {/* ================= ROW 3 ================= */}
      <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border">
        {mode === "alpha" ? (
          /* Shift Key (Left) */
          <button
            type="button"
            onClick={handleShiftClick}
            className={cn(
              keyBaseStyles,
              "flex-[1.38]",
              keyHeightClass,
              shiftState === "caps"
                ? "bg-[#0D9488] dark:bg-[#2DD4BF] text-white dark:text-[#0A1F18] border-[#0D9488] dark:border-[#2DD4BF] shadow-[0_0_8px_rgba(45,212,191,0.4)]"
                : shiftState === "shifted"
                ? "bg-[#CCFBF1] dark:bg-[#263A31] text-[#0F766E] dark:text-[#2DD4BF] border-[#0D9488]/50 dark:border-[#2DD4BF]/60"
                : modifierKeyTheme
            )}
            title={
              shiftState === "caps"
                ? "Caps Lock ON (Double-tap to turn off)"
                : shiftState === "shifted"
                ? "Shift Active"
                : "Shift"
            }
            aria-label="Shift Key"
          >
            <div className="flex flex-col items-center justify-center gap-0.5">
              <ArrowUp
                className={cn(
                  "h-4 w-4 transition-transform",
                  shiftState === "caps" ? "stroke-[2.8]" : "stroke-[2.2]"
                )}
              />
              {shiftState === "caps" && (
                <span className="w-2.5 h-0.5 bg-white dark:bg-[#0A1F18] rounded-full" />
              )}
            </div>
          </button>
        ) : mode === "numeric" ? (
          /* =\< toggle to Secondary Symbols */
          <button
            type="button"
            onClick={() => setMode("symbols")}
            className={cn(
              keyBaseStyles,
              modifierKeyTheme,
              "flex-[1.38] text-xs font-bold",
              keyHeightClass
            )}
            title="More Symbols"
            aria-label="More Symbols"
          >
            =\&lt;
          </button>
        ) : (
          /* ?123 toggle back to Primary Numbers/Symbols */
          <button
            type="button"
            onClick={() => setMode("numeric")}
            className={cn(
              keyBaseStyles,
              modifierKeyTheme,
              "flex-[1.38] text-xs font-bold",
              keyHeightClass
            )}
            title="Numbers"
            aria-label="Numbers"
          >
            ?123
          </button>
        )}

        {/* 7 or 8 Center Letter/Symbol Keys */}
        {mode === "alpha" &&
          alphaRow3.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
              aria-label={isUppercase ? k.toUpperCase() : k.toLowerCase()}
            >
              <span className="font-semibold">
                {isUppercase ? k.toUpperCase() : k.toLowerCase()}
              </span>
            </button>
          ))}

        {mode === "numeric" &&
          numRow3.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
            >
              <span className="font-semibold">{k}</span>
            </button>
          ))}

        {mode === "symbols" &&
          symRow3.map((k) => (
            <button
              key={k}
              type="button"
              onClick={() => handleKeyClick(k)}
              className={cn(
                keyBaseStyles,
                standardKeyTheme,
                "flex-1",
                keyHeightClass,
                fontSizeClass
              )}
            >
              <span className="font-semibold">{k}</span>
            </button>
          ))}

        {/* Backspace Key (Right) */}
        <button
          type="button"
          onMouseDown={startBackspace}
          onMouseUp={stopBackspace}
          onMouseLeave={stopBackspace}
          onTouchStart={startBackspace}
          onTouchEnd={stopBackspace}
          className={cn(
            keyBaseStyles,
            modifierKeyTheme,
            "flex-[1.38] active:text-rose-500",
            keyHeightClass
          )}
          title="Backspace (Hold to delete continuously)"
          aria-label="Backspace"
        >
          <Delete className="h-4.5 w-4.5 stroke-[2]" />
        </button>
      </div>

      {/* ================= ROW 4 (BOTTOM ROW) ================= */}
      <div className="flex w-full max-w-full gap-1 sm:gap-1.5 box-border pt-0.5">
        {/* Mode Switcher: ?123 / ABC */}
        {mode === "alpha" ? (
          <button
            type="button"
            onClick={() => setMode("numeric")}
            className={cn(
              keyBaseStyles,
              modifierKeyTheme,
              "flex-[1.38] text-xs font-bold",
              bottomKeyHeightClass
            )}
            aria-label="Switch to numbers and symbols"
          >
            ?123
          </button>
        ) : (
          <button
            type="button"
            onClick={() => setMode("alpha")}
            className={cn(
              keyBaseStyles,
              modifierKeyTheme,
              "flex-[1.38] text-xs font-bold",
              bottomKeyHeightClass
            )}
            aria-label="Switch to letters"
          >
            ABC
          </button>
        )}

        {/* Comma Key (standard layout) */}
        <button
          type="button"
          onClick={() => handleKeyClick(",")}
          className={cn(
            keyBaseStyles,
            standardKeyTheme,
            "flex-1 text-sm font-semibold",
            bottomKeyHeightClass
          )}
          aria-label="Comma"
        >
          ,
        </button>

        {/* Spacebar (Understated "Space" label, widest key) */}
        <button
          type="button"
          onClick={onSpace}
          className={cn(
            keyBaseStyles,
            standardKeyTheme,
            "flex-[4.8] text-xs font-medium text-[#6B7280] dark:text-[#8A9992] tracking-wider",
            bottomKeyHeightClass
          )}
          aria-label="Space"
        >
          Space
        </button>

        {/* Period Key (standard layout) */}
        <button
          type="button"
          onClick={() => handleKeyClick(".")}
          className={cn(
            keyBaseStyles,
            standardKeyTheme,
            "flex-1 text-sm font-semibold",
            bottomKeyHeightClass
          )}
          aria-label="Period"
        >
          .
        </button>

        {/* Enter / Search Key (Styled in signature ClipMax Mint) */}
        <button
          type="button"
          onClick={onEnter}
          className={cn(
            keyBaseStyles,
            "flex-[1.38] bg-[#0D9488] dark:bg-[#2DD4BF] text-white dark:text-[#0A1F18] border-[#0D9488] dark:border-[#2DD4BF] shadow-[0_1px_4px_rgba(13,148,136,0.25)] dark:shadow-[0_1px_4px_rgba(45,212,191,0.35)] hover:brightness-105 active:scale-[0.96]",
            bottomKeyHeightClass
          )}
          title="Enter / Return"
          aria-label="Enter or Return"
        >
          <CornerDownLeft className="h-4.5 w-4.5 stroke-[2.6]" />
        </button>
      </div>
    </div>
  );
}
