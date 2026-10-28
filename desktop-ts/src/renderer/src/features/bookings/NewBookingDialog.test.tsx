import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Booking } from '@shared/api/schemas';
import { apiError } from '@mocks/handlers';
import type { MockDb } from '@mocks/db';
import { renderWithProviders } from '@renderer/test/render';
import { resetMockDb, server } from '@renderer/test/server';
import { NewBookingDialog } from './NewBookingDialog';

let db: MockDb;

beforeEach(() => {
  db = resetMockDb();
});

function setup() {
  const onCreated = vi.fn<(booking: Booking) => void>();
  const onClose = vi.fn();
  renderWithProviders(<NewBookingDialog open onClose={onClose} onCreated={onCreated} />);
  return { user: userEvent.setup(), onCreated, onClose };
}

async function fillUpToTime(user: ReturnType<typeof userEvent.setup>, master = '') {
  await user.selectOptions(await screen.findByLabelText('Услуга'), 'Мужская стрижка');
  const date = screen.getByLabelText('Дата');
  await waitFor(() => expect(date).toBeEnabled());
  // 29.10.2026 — четверг, работают Анна и Карен
  await user.selectOptions(date, '2026-10-29');
  if (master !== '') {
    await user.selectOptions(screen.getByLabelText('Мастер'), master);
  }
  await user.click(await screen.findByRole('radio', { name: '12:00' }));
}

describe('NewBookingDialog', () => {
  it('услуга → дата → мастер → время → клиент: создаёт запись', async () => {
    const { user, onCreated } = setup();
    await fillUpToTime(user, 'Карен');
    await user.type(screen.getByLabelText('Имя клиента'), 'Гаяне');
    await user.type(screen.getByLabelText('Телефон'), '+374 91 000000');
    await user.click(screen.getByRole('button', { name: 'Записать' }));

    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));
    expect(onCreated.mock.calls[0]?.[0]).toMatchObject({
      clientName: 'Гаяне',
      masterName: 'Карен',
      date: '2026-10-29',
      time: '12:00',
    });
    expect(db.bookings).toHaveLength(4);
  });

  it('в списке мастеров — «Любой мастер» и только работающие в этот день', async () => {
    const { user } = setup();
    await user.selectOptions(await screen.findByLabelText('Услуга'), 'Мужская стрижка');
    await waitFor(() => expect(screen.getByLabelText('Дата')).toBeEnabled());
    await user.selectOptions(screen.getByLabelText('Дата'), '2026-10-29');
    const options = within(screen.getByLabelText('Мастер'))
      .getAllByRole('option')
      .map((o) => o.textContent);
    expect(options).toEqual(['Любой мастер', 'Анна (10:00–19:00)', 'Карен (11:00–20:00)']);
  });

  it('рабочий день без свободного времени — подсказка выбрать другую дату или мастера', async () => {
    // Сегодня работают Анна и Карен, но после 19:30 записаться уже некуда
    db.now = () => '2026-10-28T19:30';
    const { user } = setup();
    await user.selectOptions(await screen.findByLabelText('Услуга'), 'Мужская стрижка');
    const date = screen.getByLabelText('Дата');
    await waitFor(() => expect(date).toBeEnabled());
    await user.selectOptions(date, '2026-10-28');

    expect(
      await screen.findByText(
        /Нет свободного времени на этот день \(28\.10\.2026 \(ср\)\), выберите другую дату или мастера/,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });

  it('мастер не работает в этот день — ошибка сервера у поля «Дата»', async () => {
    const { user } = setup();
    await fillUpToTime(user, 'Карен');
    await user.type(screen.getByLabelText('Имя клиента'), 'Гаяне');
    await user.type(screen.getByLabelText('Телефон'), '+37491000000');
    server.use(
      http.post('*/api/v1/bookings', () =>
        apiError('validation', 'Проверьте данные записи.', { date: 'Мастер Карен не работает в этот день.' }),
      ),
    );
    await user.click(screen.getByRole('button', { name: 'Записать' }));

    expect(await screen.findByText('Мастер Карен не работает в этот день.')).toBeInTheDocument();
    expect(screen.getByLabelText('Дата')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Дата')).toHaveFocus();
  });

  it('телефон без 5 цифр не отправляется', async () => {
    const { user, onCreated } = setup();
    await fillUpToTime(user);
    await user.type(screen.getByLabelText('Имя клиента'), 'Гаяне');
    await user.type(screen.getByLabelText('Телефон'), '+(12) 3-4');
    await user.click(screen.getByRole('button', { name: 'Записать' }));

    expect(await screen.findByText(/не меньше 5 цифр/)).toBeInTheDocument();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('проверяет поля на клиенте', async () => {
    const { user, onCreated } = setup();
    await fillUpToTime(user);
    await user.type(screen.getByLabelText('Имя клиента'), 'Г');
    await user.type(screen.getByLabelText('Телефон'), '12ab');
    await user.click(screen.getByRole('button', { name: 'Записать' }));

    expect(await screen.findByText(/Имя — не короче 2 символов/)).toBeInTheDocument();
    expect(screen.getByText(/«\+» только в начале/)).toBeInTheDocument();
    expect(screen.getByLabelText('Имя клиента')).toHaveAttribute('aria-invalid', 'true');
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('409 slot_taken — сообщение, время сброшено, слоты перезапрошены', async () => {
    const { user, onCreated } = setup();
    await fillUpToTime(user);
    await user.type(screen.getByLabelText('Имя клиента'), 'Гаяне');
    await user.type(screen.getByLabelText('Телефон'), '+37491000000');

    let slotRequests = 0;
    server.use(
      http.post('*/api/v1/bookings', () => apiError('slot_taken', 'Это время уже заняли.')),
      http.get('*/api/v1/slots', () => {
        slotRequests++;
        return undefined; // дальше — обработчик мок-данных
      }),
    );
    await user.click(screen.getByRole('button', { name: 'Записать' }));

    expect(
      await screen.findByText(/Это время уже заняли\. Список свободного времени обновлён/),
    ).toBeInTheDocument();
    await waitFor(() => expect(slotRequests).toBeGreaterThan(0));
    expect(screen.getByRole('radio', { name: '12:00' })).not.toBeChecked();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it('400 validation — подсвечивает поля из ответа сервера', async () => {
    const { user } = setup();
    await fillUpToTime(user);
    await user.type(screen.getByLabelText('Имя клиента'), 'Гаяне');
    await user.type(screen.getByLabelText('Телефон'), '+37491000000');
    server.use(
      http.post('*/api/v1/bookings', () =>
        apiError('validation', 'Проверьте данные.', { phone: 'Такого номера не бывает.' }),
      ),
    );
    await user.click(screen.getByRole('button', { name: 'Записать' }));

    expect(await screen.findByText('Такого номера не бывает.')).toBeInTheDocument();
    expect(screen.getByLabelText('Телефон')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Телефон')).toHaveFocus();
  });

  it('503 — общее сообщение, данные формы сохраняются', async () => {
    const { user } = setup();
    await fillUpToTime(user);
    await user.type(screen.getByLabelText('Имя клиента'), 'Гаяне');
    await user.type(screen.getByLabelText('Телефон'), '+37491000000');
    server.use(
      http.post('*/api/v1/bookings', () => apiError('storage_busy', 'Excel-файл занят другой программой.')),
    );
    await user.click(screen.getByRole('button', { name: 'Записать' }));

    expect(await screen.findByText('Excel-файл занят другой программой.')).toBeInTheDocument();
    expect(screen.getByLabelText('Имя клиента')).toHaveValue('Гаяне');
    expect(screen.getByRole('button', { name: 'Записать' })).toBeEnabled();
  });
});
