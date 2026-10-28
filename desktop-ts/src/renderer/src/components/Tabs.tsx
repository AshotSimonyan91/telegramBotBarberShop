import { useRef, type KeyboardEvent } from 'react';

export interface TabItem<T extends string> {
  id: T;
  title: string;
}

/** Вкладки по шаблону WAI-ARIA: стрелки ←/→, Home/End переключают вкладку. */
export function TabList<T extends string>({
  tabs,
  selected,
  onSelect,
  label,
  idPrefix,
}: {
  tabs: readonly TabItem<T>[];
  selected: T;
  onSelect: (id: T) => void;
  label: string;
  idPrefix: string;
}) {
  const refs = useRef(new Map<T, HTMLButtonElement>());

  const onKeyDown = (event: KeyboardEvent) => {
    const index = tabs.findIndex((t) => t.id === selected);
    let next: number | null = null;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabs.length - 1;
    const tab = next === null ? undefined : tabs[next];
    if (tab !== undefined) {
      event.preventDefault();
      onSelect(tab.id);
      refs.current.get(tab.id)?.focus();
    }
  };

  return (
    <div role="tablist" aria-label={label} className="flex gap-1" onKeyDown={onKeyDown}>
      {tabs.map((tab) => {
        const active = tab.id === selected;
        return (
          <button
            key={tab.id}
            ref={(el) => {
              if (el === null) refs.current.delete(tab.id);
              else refs.current.set(tab.id, el);
            }}
            type="button"
            role="tab"
            id={`${idPrefix}-tab-${tab.id}`}
            aria-selected={active}
            aria-controls={`${idPrefix}-panel-${tab.id}`}
            tabIndex={active ? 0 : -1}
            onClick={() => onSelect(tab.id)}
            className={`rounded-t-md border-b-2 px-4 py-2 text-sm font-medium focus-visible:outline-2 focus-visible:outline-indigo-500 ${
              active
                ? 'border-indigo-600 text-indigo-700 dark:border-indigo-400 dark:text-indigo-300'
                : 'border-transparent text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white'
            }`}
          >
            {tab.title}
          </button>
        );
      })}
    </div>
  );
}
