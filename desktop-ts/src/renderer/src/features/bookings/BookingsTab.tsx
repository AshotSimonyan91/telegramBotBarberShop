import { useMemo, useState } from 'react';
import { isApiError, type CancelBookingResult } from '@shared/api/client';
import type { Booking } from '@shared/api/schemas';
import { formatDateWithWeekday } from '@shared/datetime';
import { useBookings, useCancelBooking, useMasters, useNow } from '@renderer/api/queries';
import {
  DEFAULT_FILTERS,
  defaultSort,
  rangeError,
  refineByTime,
  sortBookings,
  summarize,
  toBookingsQuery,
  toggleSort,
  type BookingFilters,
  type SortState,
} from '@renderer/lib/bookingFilters';
import { formatClient, formatPhone, plural } from '@renderer/lib/format';
import { useDebouncedValue } from '@renderer/lib/useDebouncedValue';
import { Button } from '@renderer/components/Button';
import { ConfirmDialog } from '@renderer/components/ConfirmDialog';
import { Spinner } from '@renderer/components/Spinner';
import { useToast, type ToastKind } from '@renderer/components/Toasts';
import { BookingsFiltersBar } from './BookingsFilters';
import { BookingsTable } from './BookingsTable';
import { NewBookingDialog } from './NewBookingDialog';

const SEARCH_DEBOUNCE_MS = 300;

function bookingSummary(b: Booking): string {
  return `${formatClient(b)}, ${b.serviceName}, мастер ${b.masterName}, ${formatDateWithWeekday(b.date)} в ${b.time}`;
}

/** Тост об отмене в зависимости от того, удалось ли уведомить клиента в Telegram. */
export function cancelToast({ booking, clientNotified }: CancelBookingResult): {
  kind: ToastKind;
  message: string;
} {
  const details = `№${booking.id}: ${bookingSummary(booking)}`;
  if (clientNotified === true) {
    return { kind: 'success', message: `Запись отменена, клиент уведомлён.\n${details}` };
  }
  if (clientNotified === false) {
    return {
      kind: 'warning',
      message:
        'Запись отменена, но уведомить клиента в Telegram не удалось — свяжитесь с ним по телефону ' +
        `${formatPhone(booking.phone)}.\n${details}`,
    };
  }
  return { kind: 'success', message: `Запись отменена.\n${details}` };
}

export function BookingsTab() {
  const toast = useToast();
  const { now, today } = useNow();
  const [filters, setFilters] = useState<BookingFilters>(DEFAULT_FILTERS);
  const [sort, setSort] = useState<SortState>(defaultSort(DEFAULT_FILTERS.period));
  const [newOpen, setNewOpen] = useState(false);
  const [cancelTarget, setCancelTarget] = useState<Booking | null>(null);

  const q = useDebouncedValue(filters.q, SEARCH_DEBOUNCE_MS);
  const invalidRange = rangeError(filters) !== null;
  const query = useMemo(() => toBookingsQuery({ ...filters, q }, today), [filters, q, today]);
  const bookings = useBookings(query, !invalidRange);
  const masters = useMasters();
  const cancel = useCancelBooking();

  const rows = useMemo(
    () => sortBookings(refineByTime(bookings.data ?? [], filters.period, now), sort),
    [bookings.data, filters.period, now, sort],
  );
  const { total, active } = summarize(rows);

  const changeFilters = (next: BookingFilters) => {
    if (next.period !== filters.period) {
      setSort(defaultSort(next.period));
    }
    setFilters(next);
  };

  const confirmCancel = () => {
    if (cancelTarget === null) {
      return;
    }
    const target = cancelTarget;
    cancel.mutate(target.id, {
      onSuccess: (result) => {
        const { kind, message } = cancelToast(result);
        toast.show(kind, message);
      },
      onError: (error) => {
        if (isApiError(error) && error.code === 'already_cancelled') {
          toast.show('info', `Запись №${target.id} уже была отменена.`);
        } else {
          toast.show('error', isApiError(error) ? error.message : 'Не удалось отменить запись.');
        }
      },
      onSettled: () => setCancelTarget(null),
    });
  };

  const updatedAt =
    bookings.dataUpdatedAt > 0
      ? new Date(bookings.dataUpdatedAt).toLocaleTimeString('ru-RU', {
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
        })
      : null;

  return (
    <section aria-labelledby="bookings-title" className="flex h-full min-h-0 flex-col">
      <div className="flex flex-col gap-3 border-b border-slate-200 px-4 py-3 dark:border-slate-700">
        <div className="flex flex-wrap items-center gap-2">
          <h2 id="bookings-title" className="mr-auto text-lg font-semibold">
            Записи
          </h2>
          <Button onClick={() => void bookings.refetch()} disabled={bookings.isFetching || invalidRange}>
            {bookings.isFetching ? 'Обновление…' : 'Обновить'}
          </Button>
          <Button variant="primary" onClick={() => setNewOpen(true)}>
            Новая запись
          </Button>
        </div>
        <BookingsFiltersBar filters={filters} masters={masters.data ?? []} onChange={changeFilters} />
      </div>

      <div className="min-h-0 flex-1 overflow-auto" aria-busy={bookings.isFetching}>
        {bookings.isPending && !invalidRange ? (
          <div className="p-6">
            <Spinner label="Загрузка записей…" />
          </div>
        ) : bookings.isError && bookings.data === undefined ? (
          <div role="alert" className="flex flex-col items-start gap-3 p-6">
            <p className="text-red-700 dark:text-red-400">
              Не удалось загрузить записи. {isApiError(bookings.error) ? bookings.error.message : ''}
            </p>
            <Button onClick={() => void bookings.refetch()}>Повторить</Button>
          </div>
        ) : rows.length === 0 ? (
          <p className="p-6 text-slate-600 dark:text-slate-300">
            {invalidRange
              ? 'Исправьте период, чтобы увидеть записи.'
              : 'Записей не найдено. Измените фильтры или создайте новую запись.'}
          </p>
        ) : (
          <BookingsTable
            bookings={rows}
            sort={sort}
            onSort={(key) => setSort((current) => toggleSort(current, key))}
            onCancel={setCancelTarget}
            cancellingId={cancel.isPending ? cancel.variables : null}
          />
        )}
      </div>

      <footer className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-slate-200 px-4 py-2 text-sm text-slate-600 dark:border-slate-700 dark:text-slate-300">
        <span role="status">
          Показано: {total} {plural(total, ['запись', 'записи', 'записей'])} (активных {active})
        </span>
        {updatedAt !== null && <span>Обновлено в {updatedAt}, автообновление раз в 30 с</span>}
        {bookings.isError && bookings.data !== undefined && (
          <span className="text-red-700 dark:text-red-400">
            Не удалось обновить: {isApiError(bookings.error) ? bookings.error.message : 'ошибка'} Показаны
            прежние данные.
          </span>
        )}
      </footer>

      <NewBookingDialog
        open={newOpen}
        onClose={() => setNewOpen(false)}
        onCreated={(booking) => {
          setNewOpen(false);
          toast.show('success', `Запись №${booking.id} сохранена.\n${bookingSummary(booking)}`);
        }}
      />

      <ConfirmDialog
        open={cancelTarget !== null}
        title={cancelTarget !== null ? `Отменить запись №${cancelTarget.id}?` : 'Отменить запись?'}
        confirmLabel="Отменить запись"
        danger
        pending={cancel.isPending}
        onConfirm={confirmCancel}
        onClose={() => setCancelTarget(null)}
      >
        {cancelTarget !== null && (
          <div className="flex flex-col gap-2 text-sm">
            <p>{bookingSummary(cancelTarget)}.</p>
            <p>Телефон: {formatPhone(cancelTarget.phone)}</p>
            {cancelTarget.telegramId > 0 && (
              <p className="text-slate-600 dark:text-slate-300">
                Клиент записывался через Telegram — бот сообщит ему об отмене.
              </p>
            )}
          </div>
        )}
      </ConfirmDialog>
    </section>
  );
}
