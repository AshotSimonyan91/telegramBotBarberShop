import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { normalizeBaseUrl } from '@shared/api/transport';
import type { SettingsView } from '@shared/ipc';
import { useServices } from '@renderer/api/ApiContext';
import { queryKeys, useSettings } from '@renderer/api/queries';
import { Button } from '@renderer/components/Button';
import { Field, inputClass } from '@renderer/components/Field';
import { Spinner } from '@renderer/components/Spinner';
import { useToast } from '@renderer/components/Toasts';

const settingsFormSchema = z.object({
  apiUrl: z
    .string()
    .trim()
    .min(1, 'Укажите адрес API.')
    .refine((v) => normalizeBaseUrl(v) !== null, 'Адрес вида http://127.0.0.1:8787 (только http или https).'),
  token: z.string().trim().max(1000, 'Слишком длинный токен.'),
});

type SettingsForm = z.infer<typeof settingsFormSchema>;

export function SettingsTab() {
  const settings = useSettings();

  if (settings.isPending) {
    return (
      <div className="p-6">
        <Spinner label="Загрузка настроек…" />
      </div>
    );
  }
  if (settings.isError) {
    return (
      <p role="alert" className="p-6 text-red-700 dark:text-red-400">
        Не удалось прочитать настройки приложения.
      </p>
    );
  }
  return <SettingsFormView settings={settings.data} />;
}

function SettingsFormView({ settings }: { settings: SettingsView }) {
  const services = useServices();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [saveError, setSaveError] = useState<string | null>(null);
  const [showToken, setShowToken] = useState(false);

  const form = useForm<SettingsForm>({
    resolver: zodResolver(settingsFormSchema),
    defaultValues: { apiUrl: settings.apiUrl, token: '' },
  });
  const { errors, isSubmitting } = form.formState;

  const onSubmit = form.handleSubmit(async (values) => {
    setSaveError(null);
    if (!settings.hasToken && values.token === '') {
      form.setError('token', { message: 'Введите токен API.' }, { shouldFocus: true });
      return;
    }
    const result = await services.settings.save({
      apiUrl: values.apiUrl,
      ...(values.token !== '' ? { token: values.token } : {}),
    });
    if (!result.ok) {
      setSaveError(result.message);
      return;
    }
    queryClient.setQueryData(queryKeys.settings, result.settings);
    form.reset({ apiUrl: result.settings.apiUrl, token: '' });
    // Новые адрес/токен: всё, что загружено со старыми, перечитывается.
    await queryClient.resetQueries({ queryKey: queryKeys.all });
    toast.show('success', 'Настройки сохранены.');
  });

  return (
    <section aria-labelledby="settings-title" className="mx-auto w-full max-w-xl p-6">
      <h2 id="settings-title" className="text-xl font-semibold">
        Подключение к API
      </h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
        Приложение работает с ботом через его HTTP API. Токен хранится только в main-процессе
        {settings.tokenEncrypted ? ' в зашифрованном виде' : ''} и на страницу не передаётся.
      </p>

      {settings.mockApi && (
        <p className="mt-4 rounded-md border border-sky-300 bg-sky-50 px-3 py-2 text-sm text-sky-950 dark:border-sky-800 dark:bg-sky-950 dark:text-sky-100">
          Включён режим разработки с мок-API: данные не настоящие и не сохраняются.
        </p>
      )}

      <form onSubmit={(e) => void onSubmit(e)} noValidate className="mt-6 flex flex-col gap-4">
        <Field
          label="Адрес API"
          error={errors.apiUrl?.message}
          hint={settings.apiUrlFromEnv ? 'Задан переменной окружения SALON_API_URL — здесь не меняется.' : undefined}
        >
          {(control) => (
            <input
              {...control}
              {...form.register('apiUrl')}
              type="url"
              inputMode="url"
              autoComplete="off"
              spellCheck={false}
              placeholder="http://127.0.0.1:8787"
              readOnly={settings.apiUrlFromEnv}
              className={inputClass}
            />
          )}
        </Field>

        <Field
          label="Токен API"
          error={errors.token?.message}
          hint={
            settings.tokenFromEnv
              ? 'Задан переменной окружения SALON_API_TOKEN — здесь не меняется.'
              : settings.hasToken
                ? 'Токен сохранён. Оставьте поле пустым, чтобы не менять его.'
                : 'Токен ещё не задан.'
          }
        >
          {(control) => (
            <div className="flex gap-2">
              <input
                {...control}
                {...form.register('token')}
                type={showToken ? 'text' : 'password'}
                autoComplete="off"
                spellCheck={false}
                placeholder={settings.hasToken ? '••••••••' : ''}
                readOnly={settings.tokenFromEnv}
                className={inputClass}
              />
              <Button
                onClick={() => setShowToken((v) => !v)}
                aria-pressed={showToken}
                disabled={settings.tokenFromEnv}
              >
                {showToken ? 'Скрыть' : 'Показать'}
              </Button>
            </div>
          )}
        </Field>

        {saveError !== null && (
          <p role="alert" className="text-sm font-medium text-red-700 dark:text-red-400">
            {saveError}
          </p>
        )}

        <div className="flex gap-2">
          <Button type="submit" variant="primary" disabled={isSubmitting}>
            {isSubmitting ? 'Сохранение…' : 'Сохранить и подключиться'}
          </Button>
        </div>
      </form>
    </section>
  );
}
