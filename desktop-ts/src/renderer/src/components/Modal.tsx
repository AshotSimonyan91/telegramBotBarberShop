import { useEffect, useId, useRef, type ReactNode } from 'react';

/**
 * Модальное окно на нативном <dialog>: фокус удерживается внутри, Escape закрывает,
 * после закрытия фокус возвращается туда, где был.
 */
export function Modal({
  open,
  title,
  description,
  onClose,
  children,
  footer,
  size = 'md',
  closeDisabled = false,
}: {
  open: boolean;
  title: string;
  description?: ReactNode;
  onClose: () => void;
  children?: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'md' | 'lg';
  /** Пока идёт отправка, закрыть окно нельзя. */
  closeDisabled?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null) {
      return;
    }
    if (open && !dialog.open) {
      const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      dialog.showModal();
      return () => {
        if (dialog.open) {
          dialog.close();
        }
        previous?.focus();
      };
    }
    return undefined;
  }, [open]);

  if (!open) {
    return null;
  }

  const width = size === 'sm' ? 'max-w-md' : size === 'lg' ? 'max-w-3xl' : 'max-w-xl';
  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description !== undefined ? descriptionId : undefined}
      onCancel={(event) => {
        event.preventDefault();
        if (!closeDisabled) {
          onClose();
        }
      }}
      className={`m-auto w-[calc(100%-2rem)] ${width} rounded-xl border border-slate-200 bg-white p-0 text-slate-900 shadow-2xl dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100`}
    >
      <div className="flex max-h-[calc(100vh-4rem)] flex-col">
        <header className="flex items-start justify-between gap-4 border-b border-slate-200 px-5 py-4 dark:border-slate-700">
          <div>
            <h2 id={titleId} className="text-lg font-semibold">
              {title}
            </h2>
            {description !== undefined && (
              <div id={descriptionId} className="mt-1 text-sm text-slate-600 dark:text-slate-300">
                {description}
              </div>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={closeDisabled}
            aria-label="Закрыть"
            className="rounded-md p-1 text-xl leading-none text-slate-500 hover:bg-slate-100 hover:text-slate-800 focus-visible:outline-2 focus-visible:outline-indigo-500 disabled:opacity-40 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-100"
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>
        {children !== undefined && <div className="overflow-y-auto px-5 py-4">{children}</div>}
        {footer !== undefined && (
          <footer className="flex flex-wrap justify-end gap-2 border-t border-slate-200 px-5 py-3 dark:border-slate-700">
            {footer}
          </footer>
        )}
      </div>
    </dialog>
  );
}
