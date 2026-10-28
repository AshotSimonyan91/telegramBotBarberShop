import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createTestServices, renderWithProviders } from '@renderer/test/render';
import { resetMockDb, server } from '@renderer/test/server';
import { ApiBanner } from './ApiBanner';
import { StatusIndicator } from './StatusIndicator';

beforeEach(() => {
  resetMockDb();
});

describe('ApiBanner и StatusIndicator', () => {
  it('всё в порядке — баннера нет, индикаторы зелёные', async () => {
    renderWithProviders(
      <>
        <ApiBanner onOpenSettings={vi.fn()} />
        <StatusIndicator />
      </>,
    );
    expect(await screen.findByText('@AshotSimonyanBot')).toBeInTheDocument();
    expect(screen.getByText('salon_bookings.xlsx')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('401 — баннер с кнопкой «Открыть настройки»', async () => {
    const onOpenSettings = vi.fn();
    renderWithProviders(
      <ApiBanner onOpenSettings={onOpenSettings} />,
      createTestServices({ token: 'wrong' }),
    );
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/отклонил токен/);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Открыть настройки' }));
    expect(onOpenSettings).toHaveBeenCalled();
  });

  it('API недоступен — баннер без кнопки настроек', async () => {
    server.use(http.get('*/api/v1/status', () => HttpResponse.error()));
    renderWithProviders(<ApiBanner onOpenSettings={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/Нет связи с API/);
    expect(screen.queryByRole('button', { name: 'Открыть настройки' })).not.toBeInTheDocument();
  });

  it('бот остановлен — предупреждение', async () => {
    server.use(
      http.get('*/api/v1/status', () =>
        HttpResponse.json({ bot: { running: false }, storage: { ok: true, file: 'x.xlsx' }, time: 'now' }),
      ),
    );
    renderWithProviders(<ApiBanner onOpenSettings={vi.fn()} />);
    expect(await screen.findByText(/бот не запущен/)).toBeInTheDocument();
  });
});
