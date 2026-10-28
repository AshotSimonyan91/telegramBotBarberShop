import { isApiError } from '@shared/api/client';
import { Button } from '@renderer/components/Button';
import { useStatus } from '@renderer/api/queries';

/** Баннер о недоступности API или проблемах бота/хранилища. */
export function ApiBanner({ onOpenSettings }: { onOpenSettings: () => void }) {
  const status = useStatus();

  if (status.isError) {
    const error = status.error;
    const needsSettings = isApiError(error) && (error.isUnauthorized || error.kind === 'not-configured');
    const message = isApiError(error)
      ? error.isUnauthorized
        ? 'Сервер отклонил токен API. Проверьте токен в настройках.'
        : error.message
      : 'API недоступен.';
    return (
      <div
        role="alert"
        className="flex flex-wrap items-center gap-3 border-b border-red-300 bg-red-50 px-4 py-2.5 text-sm text-red-950 dark:border-red-800 dark:bg-red-950 dark:text-red-100"
      >
        <p className="flex-1">
          <span className="font-semibold">Нет связи с API. </span>
          {message}
        </p>
        <Button size="sm" onClick={() => void status.refetch()} disabled={status.isFetching}>
          {status.isFetching ? 'Проверка…' : 'Повторить'}
        </Button>
        {needsSettings && (
          <Button size="sm" variant="primary" onClick={onOpenSettings}>
            Открыть настройки
          </Button>
        )}
      </div>
    );
  }

  if (status.data !== undefined && (!status.data.bot.running || !status.data.storage.ok)) {
    const problems = [
      !status.data.bot.running ? 'бот не запущен — клиенты не смогут записаться через Telegram' : null,
      !status.data.storage.ok ? 'хранилище недоступно — записи не сохраняются' : null,
    ].filter((p) => p !== null);
    return (
      <div
        role="status"
        className="border-b border-amber-300 bg-amber-50 px-4 py-2.5 text-sm text-amber-950 dark:border-amber-800 dark:bg-amber-950 dark:text-amber-100"
      >
        <span className="font-semibold">Внимание: </span>
        {problems.join('; ')}.
      </div>
    );
  }
  return null;
}
