import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SettingsUpdate } from '@shared/ipc';
import { createTestServices, renderWithProviders, TEST_SETTINGS } from './test/render';
import { resetMockDb } from './test/server';
import { App } from './App';

beforeEach(() => {
  resetMockDb();
});

describe('App', () => {
  it('без токена открывает настройки и сохраняет введённые адрес и токен', async () => {
    const save = vi.fn((update: SettingsUpdate) =>
      Promise.resolve({
        ok: true as const,
        settings: { ...TEST_SETTINGS, apiUrl: update.apiUrl, hasToken: true },
      }),
    );
    const services = createTestServices({
      settings: { get: () => Promise.resolve({ ...TEST_SETTINGS, hasToken: false }), save },
    });
    const user = userEvent.setup();
    renderWithProviders(<App />, services);

    const tab = await screen.findByRole('tab', { name: 'Настройки' });
    expect(tab).toHaveAttribute('aria-selected', 'true');

    await user.click(screen.getByRole('button', { name: 'Сохранить и подключиться' }));
    expect(await screen.findByText('Введите токен API.')).toBeInTheDocument();
    expect(save).not.toHaveBeenCalled();

    const url = screen.getByLabelText('Адрес API');
    await user.clear(url);
    await user.type(url, 'http://192.168.1.10:8787/');
    await user.type(screen.getByLabelText('Токен API'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Сохранить и подключиться' }));

    await waitFor(() =>
      expect(save).toHaveBeenCalledWith({ apiUrl: 'http://192.168.1.10:8787/', token: 'secret' }),
    );
    expect(await screen.findByText('Настройки сохранены.')).toBeInTheDocument();
  });

  it('вкладки переключаются стрелками', async () => {
    const user = userEvent.setup();
    renderWithProviders(<App />);
    const bookings = await screen.findByRole('tab', { name: 'Записи' });
    await waitFor(() => expect(bookings).toHaveAttribute('aria-selected', 'true'));
    bookings.focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Настройки' })).toHaveFocus();
    expect(screen.getByRole('tab', { name: 'Настройки' })).toHaveAttribute('aria-selected', 'true');
  });
});
