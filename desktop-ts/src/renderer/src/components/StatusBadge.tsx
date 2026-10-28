import type { BookingStatus } from '@shared/api/schemas';
import { STATUS_TITLES } from '@renderer/lib/format';

const STYLES: Readonly<Record<BookingStatus, string>> = {
  CONFIRMED: 'bg-emerald-100 text-emerald-900 ring-emerald-600/30 dark:bg-emerald-900/50 dark:text-emerald-200',
  CANCELLED: 'bg-slate-200 text-slate-700 ring-slate-500/30 dark:bg-slate-700 dark:text-slate-200',
};

export function StatusBadge({ status }: { status: BookingStatus }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ring-1 ring-inset ${STYLES[status]}`}
    >
      {STATUS_TITLES[status]}
    </span>
  );
}
