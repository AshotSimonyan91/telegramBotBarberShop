import type { Master } from '@shared/api/schemas';
import {
  PERIODS,
  PERIOD_TITLES,
  STATUS_FILTERS,
  STATUS_FILTER_TITLES,
  masterFilterOptions,
  rangeError,
  type BookingFilters,
  type Period,
  type StatusFilter,
} from '@renderer/lib/bookingFilters';
import { inputClass } from '@renderer/components/Field';

const labelClass = 'flex flex-col gap-1 text-xs font-medium text-slate-600 dark:text-slate-300';

export function BookingsFiltersBar({
  filters,
  masters,
  onChange,
}: {
  filters: BookingFilters;
  masters: readonly Master[];
  onChange: (next: BookingFilters) => void;
}) {
  const set = <K extends keyof BookingFilters>(key: K, value: BookingFilters[K]) =>
    onChange({ ...filters, [key]: value });
  const error = rangeError(filters);

  return (
    <div role="search" aria-label="Фильтры записей" className="flex flex-wrap items-end gap-3">
      <label className={labelClass}>
        Период
        <select
          value={filters.period}
          onChange={(e) => set('period', e.target.value as Period)}
          className={`${inputClass} w-48`}
        >
          {PERIODS.map((p) => (
            <option key={p} value={p}>
              {PERIOD_TITLES[p]}
            </option>
          ))}
        </select>
      </label>

      {filters.period === 'range' && (
        <>
          <label className={labelClass}>
            С
            <input
              type="date"
              value={filters.from}
              onChange={(e) => set('from', e.target.value)}
              aria-invalid={error !== null}
              className={`${inputClass} w-40`}
            />
          </label>
          <label className={labelClass}>
            По
            <input
              type="date"
              value={filters.to}
              onChange={(e) => set('to', e.target.value)}
              aria-invalid={error !== null}
              aria-describedby={error !== null ? 'range-error' : undefined}
              className={`${inputClass} w-40`}
            />
          </label>
        </>
      )}

      <label className={labelClass}>
        Статус
        <select
          value={filters.status}
          onChange={(e) => set('status', e.target.value as StatusFilter)}
          className={`${inputClass} w-40`}
        >
          {STATUS_FILTERS.map((s) => (
            <option key={s} value={s}>
              {STATUS_FILTER_TITLES[s]}
            </option>
          ))}
        </select>
      </label>

      <label className={labelClass}>
        Мастер
        <select
          value={filters.masterName}
          onChange={(e) => set('masterName', e.target.value)}
          className={`${inputClass} w-44`}
        >
          <option value="">Все мастера</option>
          {masterFilterOptions(masters, filters.masterName).map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </label>

      <label className={`${labelClass} min-w-48 flex-1`}>
        Поиск
        <input
          type="search"
          value={filters.q}
          onChange={(e) => set('q', e.target.value)}
          placeholder="Имя, телефон или №"
          className={inputClass}
        />
      </label>

      {error !== null && (
        <p id="range-error" role="alert" className="w-full text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
