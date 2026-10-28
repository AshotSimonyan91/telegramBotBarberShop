import { isApiError } from '@shared/api/client';
import { useStatus } from '@renderer/api/queries';

type Tone = 'ok' | 'warn' | 'error' | 'unknown';

const DOT: Readonly<Record<Tone, string>> = {
  ok: 'bg-emerald-500',
  warn: 'bg-amber-500',
  error: 'bg-red-500',
  unknown: 'bg-slate-400',
};

function Indicator({ tone, label, detail }: { tone: Tone; label: string; detail: string }) {
  return (
    <li className="flex items-center gap-1.5" title={detail}>
      <span aria-hidden="true" className={`size-2.5 rounded-full ${DOT[tone]}`} />
      <span>
        {label}: <span className="font-medium">{detail}</span>
      </span>
    </li>
  );
}

/** Состояние бота и хранилища по GET /status (обновляется раз в 15 с). */
export function StatusIndicator() {
  const status = useStatus();

  if (status.isPending) {
    return (
      <ul aria-label="Состояние системы" className="flex gap-4 text-sm text-slate-600 dark:text-slate-300">
        <Indicator tone="unknown" label="API" detail="проверка…" />
      </ul>
    );
  }
  if (status.isError) {
    const detail = isApiError(status.error) && status.error.isUnauthorized ? 'неверный токен' : 'недоступен';
    return (
      <ul aria-label="Состояние системы" className="flex gap-4 text-sm text-slate-600 dark:text-slate-300">
        <Indicator tone="error" label="API" detail={detail} />
      </ul>
    );
  }
  const { bot, storage } = status.data;
  return (
    <ul
      aria-label="Состояние системы"
      className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-600 dark:text-slate-300"
    >
      <Indicator
        tone={bot.running ? 'ok' : 'warn'}
        label="Бот"
        detail={bot.running ? (bot.username ? `@${bot.username}` : 'работает') : 'остановлен'}
      />
      <Indicator
        tone={storage.ok ? 'ok' : 'error'}
        label="Хранилище"
        detail={storage.ok ? (storage.file ?? 'в порядке') : 'ошибка'}
      />
    </ul>
  );
}
