import React, { createContext, useContext, useState, useCallback } from "react";
import { CheckCircle2, AlertCircle, Info, X } from "lucide-react";

export type ToastType = "success" | "error" | "info";

export type Toast = {
  id: string;
  message: string;
  description?: string;
  type?: ToastType;
  action?: {
    label: string;
    onClick: () => void;
  };
};

type ToastContextType = {
  toast: (message: string, options?: { description?: string; type?: ToastType; action?: Toast["action"] }) => void;
  success: (message: string, options?: { description?: string; action?: Toast["action"] }) => void;
  error: (message: string, options?: { description?: string; action?: Toast["action"] }) => void;
};

const ToastContext = createContext<ToastContextType | null>(null);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const addToast = useCallback(
    (message: string, options?: { description?: string; type?: ToastType; action?: Toast["action"] }) => {
      const id = `toast-${Date.now()}-${Math.random()}`;
      const newToast: Toast = {
        id,
        message,
        description: options?.description,
        type: options?.type || "info",
        action: options?.action,
      };

      setToasts((prev) => [...prev.slice(-3), newToast]);

      setTimeout(() => {
        removeToast(id);
      }, 3500);
    },
    [removeToast]
  );

  const contextValue: ToastContextType = {
    toast: (msg, opts) => addToast(msg, opts),
    success: (msg, opts) => addToast(msg, { ...opts, type: "success" }),
    error: (msg, opts) => addToast(msg, { ...opts, type: "error" }),
  };

  return (
    <ToastContext.Provider value={contextValue}>
      {children}
      {/* Toast container */}
      <div
        aria-live="polite"
        className="pointer-events-none fixed bottom-4 left-1/2 z-50 flex w-full max-w-sm -translate-x-1/2 flex-col gap-2 px-4"
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            className="pointer-events-auto flex items-start gap-2.5 rounded-xl border border-border bg-surface px-3.5 py-2.5 text-foreground shadow-lg transition-all animate-in fade-in slide-in-from-bottom-2 dark:bg-surface-2"
          >
            {t.type === "success" && <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-mint" />}
            {t.type === "error" && <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />}
            {t.type === "info" && <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />}

            <div className="min-w-0 flex-1">
              <p className="text-[13px] font-medium leading-snug">{t.message}</p>
              {t.description && (
                <p className="mt-0.5 text-[11px] leading-tight text-muted-foreground">{t.description}</p>
              )}
            </div>

            {t.action && (
              <button
                type="button"
                onClick={() => {
                  t.action?.onClick();
                  removeToast(t.id);
                }}
                className="rounded px-1.5 py-0.5 text-xs font-semibold text-mint hover:bg-mint-soft"
              >
                {t.action.label}
              </button>
            )}

            <button
              type="button"
              onClick={() => removeToast(t.id)}
              className="text-muted-foreground hover:text-foreground"
              aria-label="Close notification"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    return {
      toast: (msg: string) => console.log(msg),
      success: (msg: string) => console.log("Success:", msg),
      error: (msg: string) => console.error("Error:", msg),
    };
  }
  return ctx;
}
