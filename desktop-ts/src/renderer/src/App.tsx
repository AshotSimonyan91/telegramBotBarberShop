import { useState } from 'react';
import { useSettings } from './api/queries';
import { Spinner } from './components/Spinner';
import { TabList, type TabItem } from './components/Tabs';
import { BookingsTab } from './features/bookings/BookingsTab';
import { SettingsTab } from './features/settings/SettingsTab';
import { ApiBanner } from './features/status/ApiBanner';
import { StatusIndicator } from './features/status/StatusIndicator';

type TabId = 'bookings' | 'settings';

const TABS: readonly TabItem<TabId>[] = [
  { id: 'bookings', title: 'Записи' },
  { id: 'settings', title: 'Настройки' },
];

export function App() {
  const settings = useSettings();
  const [chosenTab, setTab] = useState<TabId | null>(null);

  // Пока пользователь сам не выбрал вкладку: без токена открываются настройки, иначе — записи.
  const needsSetup = settings.data !== undefined && !settings.data.hasToken;
  const tab: TabId = chosenTab ?? (needsSetup ? 'settings' : 'bookings');

  if (settings.isPending) {
    return (
      <div className="grid h-full place-items-center">
        <Spinner label="Запуск…" />
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2 border-b border-slate-200 bg-white px-4 pt-3 dark:border-slate-700 dark:bg-slate-900">
        <div className="flex items-end gap-6">
          <h1 className="pb-2 text-base font-bold">Салон</h1>
          <TabList tabs={TABS} selected={tab} onSelect={setTab} label="Разделы" idPrefix="main" />
        </div>
        <div className="pb-2">{!needsSetup && <StatusIndicator />}</div>
      </header>
      {!needsSetup && <ApiBanner onOpenSettings={() => setTab('settings')} />}
      <main className="min-h-0 flex-1">
        {TABS.map((t) => (
          <div
            key={t.id}
            role="tabpanel"
            id={`main-panel-${t.id}`}
            aria-labelledby={`main-tab-${t.id}`}
            hidden={t.id !== tab}
            className="h-full overflow-auto"
          >
            {t.id === tab && (t.id === 'bookings' ? <BookingsTab /> : <SettingsTab />)}
          </div>
        ))}
      </main>
    </div>
  );
}
