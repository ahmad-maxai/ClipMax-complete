import React, { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "../lib/utils";
import logoImg from "../assets/logo.jpg";

export interface BtnProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "mint" | "outline" | "ghost" | "subtle" | "danger" | "dangerOutline";
  size?: "md" | "sm" | "icon" | "iconSm";
  children?: ReactNode;
}

export function Btn({
  className,
  variant = "outline",
  size = "md",
  disabled,
  children,
  ...props
}: BtnProps) {
  const baseStyles =
    "inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors select-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-40 disabled:pointer-events-none active:scale-[0.98]";

  const variantStyles = {
    primary: "bg-primary text-primary-foreground hover:opacity-90",
    mint: "bg-mint text-primary-foreground hover:opacity-90 dark:text-background",
    outline: "border border-border bg-surface text-foreground hover:bg-accent",
    ghost: "text-foreground hover:bg-accent",
    subtle: "bg-secondary text-secondary-foreground hover:bg-accent",
    danger: "bg-destructive text-destructive-foreground hover:opacity-90",
    dangerOutline: "border border-destructive/40 text-destructive hover:bg-destructive/10",
  }[variant];

  const sizeStyles = {
    md: "h-11 px-4 text-sm",
    sm: "h-9 px-3 text-[13px]",
    icon: "h-11 w-11",
    iconSm: "h-9 w-9",
  }[size];

  return (
    <button
      type="button"
      disabled={disabled}
      className={cn(baseStyles, variantStyles, sizeStyles, className)}
      {...props}
    >
      {children}
    </button>
  );
}

export function IconBtn({
  label,
  className,
  variant = "ghost",
  size = "icon",
  children,
  ...props
}: BtnProps & { label: string }) {
  return (
    <Btn
      aria-label={label}
      title={label}
      variant={variant}
      size={size}
      className={cn("tap-target", className)}
      {...props}
    >
      {children}
    </Btn>
  );
}

export function TopBar({
  left,
  title,
  right,
  subtitle,
}: {
  left?: ReactNode;
  title: ReactNode;
  right?: ReactNode;
  subtitle?: ReactNode;
}) {
  return (
    <header className="sticky top-0 z-10 border-b border-border bg-background/95 backdrop-blur-sm pt-[env(safe-area-inset-top,0px)]">
      <div className="flex h-14 items-center gap-1.5 px-3">
        {left}
        <div className="min-w-0 flex-1 px-1">
          {typeof title === "string" ? (
            <h1 className="truncate text-[15px] font-semibold tracking-tight">{title}</h1>
          ) : (
            title
          )}
          {subtitle && <div className="text-[11px] text-muted-foreground truncate">{subtitle}</div>}
        </div>
        <div className="flex items-center gap-1">{right}</div>
      </div>
    </header>
  );
}

export function Section({
  title,
  children,
  className,
}: {
  title?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("rounded-xl border border-border bg-surface overflow-hidden", className)}>
      {title && (
        <h2 className="px-4 pt-3 pb-1 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground">
          {title}
        </h2>
      )}
      {children}
    </section>
  );
}

export function ClipMaxLogo({
  size = 28,
  className = "",
}: {
  size?: number;
  className?: string;
}) {
  return (
    <div
      style={{ width: size, height: size }}
      className={cn(
        "relative shrink-0 overflow-hidden rounded-[24%] border border-border/80 bg-[#f0f4f2] dark:bg-[#1a2621] shadow-xs flex items-center justify-center select-none",
        className
      )}
    >
      {/* Crisp SVG Vector Fallback / Base (Always visible, never blank) */}
      <svg
        viewBox="0 0 100 100"
        className="absolute inset-0 h-full w-full p-[14%] text-[#111916] dark:text-[#EAF1ED]"
        fill="currentColor"
      >
        {/* Bold X character */}
        <polygon points="20,16 38,16 50,36 62,16 80,16 61,46 82,78 64,78 50,56 36,78 18,78 39,46" />
        {/* Rounded clipboard pill loop below */}
        <path
          d="M 28 84 C 28 81, 72 81, 72 84 C 72 87, 28 87, 28 84 Z"
          fill="none"
          stroke="currentColor"
          strokeWidth="6"
          strokeLinecap="round"
        />
      </svg>

      {/* Actual High-Res Rendered Logo from assets */}
      <img
        src={logoImg}
        alt="ClipMax"
        className="absolute inset-0 h-full w-full object-cover scale-[1.03]"
        onError={(e) => {
          e.currentTarget.style.display = "none";
        }}
      />
    </div>
  );
}

export function Wordmark() {
  return (
    <div className="flex items-center gap-2 select-none">
      <ClipMaxLogo size={28} />
      <span className="text-[16px] font-bold tracking-tight text-foreground flex items-center">
        Clip<span className="text-mint font-extrabold">Max</span>
      </span>
      <span className="rounded-xs bg-mint-soft px-1.5 py-0.5 text-[9px] font-bold tracking-wider uppercase text-mint-foreground">
        v0.5.1
      </span>
    </div>
  );
}

export function ConfirmModal({
  open,
  title,
  description,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  destructive = true,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  if (!open) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-xs animate-in fade-in"
    >
      <div className="w-full max-w-sm rounded-2xl border border-border bg-surface p-5 shadow-2xl animate-in zoom-in-95">
        <h3 className="text-base font-semibold leading-tight">{title}</h3>
        <p className="mt-2 text-[13px] leading-relaxed text-muted-foreground">{description}</p>
        <div className="mt-5 flex justify-end gap-2">
          <Btn variant="ghost" size="sm" onClick={onClose}>
            {cancelLabel}
          </Btn>
          <Btn
            variant={destructive ? "danger" : "primary"}
            size="sm"
            onClick={() => {
              onConfirm();
              onClose();
            }}
          >
            {confirmLabel}
          </Btn>
        </div>
      </div>
    </div>
  );
}
