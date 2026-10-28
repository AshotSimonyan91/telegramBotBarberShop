import type { ReactNode } from 'react';
import type { Booking } from '@shared/api/schemas';
import { formatDateTimeShort, formatDateWithWeekday } from '@shared/datetime';
import type { SortKey, SortState } from '@renderer/lib/bookingFilters';
import { formatClient, formatPhone } from '@renderer/lib/format';
import { Button } from '@renderer/components/Button';
import { StatusBadge } from '@renderer/components/StatusBadge';

interface Column {
  key: SortKey;
  title: string;
  className?: string;
  render: (b: Booking) => ReactNode;
}

const COLUMNS: readonly Column[] = [
  { key: 'id', title: '№', className: 'text-right tabular-nums', render: (b) => b.id },
  {
    key: 'start',
    title: 'Дата и время',
    className: 'whitespace-nowrap tabular-nums',
    render: (b) => (
      <>
        {formatDateWithWeekday(b.date)} <span className="font-semibold">{b.time}</span>
      </>
    ),
  },
  { key: 'clientName', title: 'Клиент', render: formatClient },
  {
    key: 'phone',
    title: 'Телефон',
    className: 'whitespace-nowrap tabular-nums',
    render: (b) => formatPhone(b.phone),
  },
  { key: 'serviceName', title: 'Услуга', render: (b) => b.serviceName },
  { key: 'masterName', title: 'Мастер', render: (b) => b.masterName },
  { key: 'status', title: 'Статус', render: (b) => <StatusBadge status={b.status} /> },
  { key: 'source', title: 'Источник', render: (b) => b.source },
  {
    key: 'createdAt',
    title: 'Создана',
    className: 'whitespace-nowrap tabular-nums text-slate-600 dark:text-slate-400',
    render: (b) =>
      b.createdAt === null ? (
        <span title="Время создания не записано">—</span>
      ) : (
        formatDateTimeShort(b.createdAt)
      ),
  },
];

const ARIA_SORT = { asc: 'ascending', desc: 'descending' } as const;

export function BookingsTable({
  bookings,
  sort,
  onSort,
  onCancel,
  cancellingId,
}: {
  bookings: readonly Booking[];
  sort: SortState;
  onSort: (key: SortKey) => void;
  onCancel: (booking: Booking) => void;
  cancellingId: number | null;
}) {
  return (
    <table className="w-full border-separate border-spacing-0 text-sm">
      <caption className="sr-only">
        Записи клиентов. Нажмите на заголовок столбца, чтобы отсортировать.
      </caption>
      <thead className="sticky top-0 z-10 bg-slate-100 dark:bg-slate-800">
        <tr>
          {COLUMNS.map((column) => {
            const active = sort.key === column.key;
            return (
              <th
                key={column.key}
                scope="col"
                aria-sort={active ? ARIA_SORT[sort.direction] : 'none'}
                className="border-b border-slate-300 px-3 py-2 text-left font-semibold whitespace-nowrap dark:border-slate-600"
              >
                <button
                  type="button"
                  onClick={() => onSort(column.key)}
                  className="inline-flex items-center gap-1 rounded hover:text-indigo-700 focus-visible:outline-2 focus-visible:outline-indigo-500 dark:hover:text-indigo-300"
                >
                  {column.title}
                  <span aria-hidden="true" className={active ? '' : 'opacity-30'}>
                    {active && sort.direction === 'desc' ? '▼' : '▲'}
                  </span>
                </button>
              </th>
            );
          })}
          <th
            scope="col"
            className="border-b border-slate-300 px-3 py-2 text-left font-semibold dark:border-slate-600"
          >
            <span className="sr-only">Действия</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {bookings.map((booking) => {
          const cancelled = booking.status === 'CANCELLED';
          return (
            <tr
              key={booking.id}
              className={`odd:bg-white even:bg-slate-50 hover:bg-indigo-50/60 dark:odd:bg-slate-900 dark:even:bg-slate-900/60 dark:hover:bg-slate-800 ${cancelled ? 'text-slate-500 dark:text-slate-400' : ''}`}
            >
              {COLUMNS.map((column) => (
                <td
                  key={column.key}
                  className={`border-b border-slate-200 px-3 py-2 align-middle dark:border-slate-700 ${column.className ?? ''}`}
                >
                  {column.render(booking)}
                </td>
              ))}
              <td className="border-b border-slate-200 px-3 py-1.5 text-right dark:border-slate-700">
                {!cancelled && (
                  <Button
                    size="sm"
                    variant="ghostDanger"
                    onClick={() => onCancel(booking)}
                    disabled={cancellingId === booking.id}
                    aria-label={`Отменить запись №${booking.id}, ${booking.clientName}`}
                  >
                    Отменить
                  </Button>
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
