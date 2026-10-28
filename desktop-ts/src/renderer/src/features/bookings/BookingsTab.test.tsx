import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { MockDb } from '@mocks/db';
import { renderWithProviders } from '@renderer/test/render';
import { resetMockDb, server } from '@renderer/test/server';
import { BookingsTab } from './BookingsTab';

let db: MockDb;

beforeEach(() => {
  db = resetMockDb();
});

function rowIds(): string[] {
  const table = screen.getByRole('table');
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell')[0]?.textContent ?? '');
}

describe('BookingsTab', () => {
  it('по умолчанию показывает предстоящие активные записи', async () => {
    renderWithProviders(<BookingsTab />);
    expect(await screen.findByText('Лилит Саргсян')).toBeInTheDocument();
    // №1 — в прошлом, №3 — отменена
    expect(rowIds()).toEqual(['2']);
    expect(screen.getByText(/Показано: 1 запись/)).toBeInTheDocument();
  });

  it('фильтр «Все статусы» + «Все» и сортировка по номеру', async () => {
    const user = userEvent.setup();
    renderWithProviders(<BookingsTab />);
    await screen.findByText('Лилит Саргсян');

    await user.selectOptions(screen.getByLabelText('Период'), 'all');
    await user.selectOptions(screen.getByLabelText('Статус'), 'all');
    await waitFor(() => expect(rowIds()).toHaveLength(3));
    expect(screen.getByText('Отменена')).toBeInTheDocument();

    const header = screen.getByRole('columnheader', { name: /№/ });
    await user.click(within(header).getByRole('button'));
    expect(header).toHaveAttribute('aria-sort', 'ascending');
    expect(rowIds()).toEqual(['1', '2', '3']);
    await user.click(within(header).getByRole('button'));
    expect(header).toHaveAttribute('aria-sort', 'descending');
    expect(rowIds()).toEqual(['3', '2', '1']);
  });

  it('отмена записи с подтверждением и тостом', async () => {
    const user = userEvent.setup();
    renderWithProviders(<BookingsTab />);
    await user.click(await screen.findByRole('button', { name: /Отменить запись №2/ }));

    const dialog = screen.getByRole('dialog', { name: 'Отменить запись №2?' });
    expect(within(dialog).getByText(/Лилит Саргсян/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Отменить запись' }));

    expect(await screen.findByText(/^Запись отменена\./)).toBeInTheDocument();
    expect(db.bookings.find((b) => b.id === 2)?.status).toBe('CANCELLED');
    await waitFor(() => expect(screen.queryByRole('table')).not.toBeInTheDocument());
    expect(screen.getByText(/Записей не найдено/)).toBeInTheDocument();
  });

  it.each([
    [true, 'Готово', /Запись отменена, клиент уведомлён\./],
    [
      false,
      'Внимание',
      /Запись отменена, но уведомить клиента в Telegram не удалось — свяжитесь с ним по телефону \+374 77 554 433\./,
    ],
  ])('отмена записи из Telegram, уведомление удалось: %s', async (notifyOk, title, text) => {
    const booking = db.bookings.find((b) => b.id === 2);
    if (booking !== undefined) {
      booking.telegramId = 555;
    }
    db.telegram.notifyOk = notifyOk;
    const user = userEvent.setup();
    renderWithProviders(<BookingsTab />);
    await user.click(await screen.findByRole('button', { name: /Отменить запись №2/ }));
    const dialog = screen.getByRole('dialog', { name: 'Отменить запись №2?' });
    expect(within(dialog).getByText(/бот сообщит ему об отмене/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Отменить запись' }));

    const toast = await screen.findByText(text);
    expect(toast.parentElement).toHaveTextContent(`${title}:`);
  });

  it('телефон отформатирован, у старых записей без даты создания — «—»', async () => {
    const user = userEvent.setup();
    renderWithProviders(<BookingsTab />);
    await screen.findByText('Лилит Саргсян');
    expect(screen.getByText('+374 77 554 433')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Период'), 'all');
    await user.selectOptions(screen.getByLabelText('Статус'), 'all');
    await waitFor(() => expect(rowIds()).toHaveLength(3));
    const row = screen.getByText('Давид Акопян').closest('tr');
    expect(row).not.toBeNull();
    const cells = within(row as HTMLElement).getAllByRole('cell');
    expect(cells[8]).toHaveTextContent(/^—$/);
  });

  it('«Не отменять» закрывает диалог без запроса', async () => {
    const user = userEvent.setup();
    renderWithProviders(<BookingsTab />);
    await user.click(await screen.findByRole('button', { name: /Отменить запись №2/ }));
    await user.click(screen.getByRole('button', { name: 'Не отменять' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(db.bookings.find((b) => b.id === 2)?.status).toBe('CONFIRMED');
  });

  it('ошибка загрузки — сообщение и кнопка «Повторить»', async () => {
    server.use(
      http.get('*/api/v1/bookings', () =>
        HttpResponse.json({ error: 'storage_busy', message: 'Excel-файл занят.' }, { status: 503 }),
      ),
    );
    renderWithProviders(<BookingsTab />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Excel-файл занят.');
    expect(within(alert).getByRole('button', { name: 'Повторить' })).toBeInTheDocument();
  });
});
