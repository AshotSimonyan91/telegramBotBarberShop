import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';

export type ToastKind = 'success' | 'info' | 'warning' | 'error';

/** Предупреждения и ошибки объявляются скринридером сразу (assertive), остальное — вежливо. */
const URGENT: ReadonlySet<ToastKind> = new Set<ToastKind>(['warning', 'error']);

interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

interface ToastApi {
  show: (kind: ToastKind, message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const DURATION_MS: Readonly<Record<ToastKind, number>> = {
  success: 5_000,
  info: 6_000,
  // в предупреждении может быть телефон, который нужно успеть прочитать или переписать
  warning: 20_000,
  error: 10_000,
};

const STYLES: Readonly<Record<ToastKind, string>> = {
  success: 'border-emerald-600 bg-emerald-50 text-emerald-950 dark:bg-emerald-950 dark:text-emerald-100',
  info: 'border-sky-600 bg-sky-50 text-sky-950 dark:bg-sky-950 dark:text-sky-100',
  warning: 'border-amber-500 bg-amber-50 text-amber-950 dark:bg-amber-950 dark:text-amber-100',
  error: 'border-red-600 bg-red-50 text-red-950 dark:bg-red-950 dark:text-red-100',
};

const TITLES: Readonly<Record<ToastKind, string>> = {
  success: 'Готово',
  info: 'Сообщение',
  warning: 'Внимание',
  error: 'Ошибка',
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setToasts((list) => list.filter((t) => t.id !== id)), []);

  const show = useCallback(
    (kind: ToastKind, message: string) => {
      const id = nextId.current++;
      setToasts((list) => [...list.slice(-3), { id, kind, message }]);
      window.setTimeout(() => dismiss(id), DURATION_MS[kind]);
    },
    [dismiss],
  );

  const api = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      {/* Живые регионы существуют всегда, чтобы скринридеры объявляли новые сообщения */}
      <section
        aria-label="Уведомления"
        className="pointer-events-none fixed right-4 bottom-4 z-50 flex w-96 max-w-[calc(100%-2rem)] flex-col gap-2"
      >
        <div aria-live="polite" aria-atomic="false" className="contents">
          {toasts
            .filter((t) => !URGENT.has(t.kind))
            .map((t) => (
              <ToastItem key={t.id} toast={t} onDismiss={dismiss} />
            ))}
        </div>
        <div aria-live="assertive" aria-atomic="false" className="contents">
          {toasts
            .filter((t) => URGENT.has(t.kind))
            .map((t) => (
              <ToastItem key={t.id} toast={t} onDismiss={dismiss} />
            ))}
        </div>
      </section>
    </ToastContext.Provider>
  );
}

function ToastItem({ toast, onDismiss }: { toast: Toast; onDismiss: (id: number) => void }) {
  return (
    <div
      className={`pointer-events-auto flex items-start gap-3 rounded-lg border-l-4 px-4 py-3 shadow-lg ${STYLES[toast.kind]}`}
    >
      <div className="flex-1 text-sm">
        <span className="sr-only">{TITLES[toast.kind]}: </span>
        <span className="whitespace-pre-line">{toast.message}</span>
      </div>
      <button
        type="button"
        onClick={() => onDismiss(toast.id)}
        aria-label="Скрыть уведомление"
        className="rounded px-1 text-lg leading-none opacity-70 hover:opacity-100 focus-visible:outline-2 focus-visible:outline-current"
      >
        <span aria-hidden="true">×</span>
      </button>
    </div>
  );
}

export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (api === null) {
    throw new Error('useToast вызван вне ToastProvider');
  }
  return api;
}
