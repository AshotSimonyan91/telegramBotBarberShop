import { useEffect, useMemo, useState } from 'react';
import { useForm, useWatch, type UseFormRegisterReturn } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { isApiError } from '@shared/api/client';
import { BOOKING_FIELDS, type Booking, type BookingField } from '@shared/api/schemas';
import { formatDateLong, formatDateWithWeekday, relativeDayLabel } from '@shared/datetime';
import {
  ANY_MASTER,
  createBookingFormSchema,
  type BookingFormInput,
  type BookingFormValues,
} from '@shared/validation';
import {
  DATES_AHEAD,
  queryKeys,
  useCreateBooking,
  useDates,
  useMasters,
  useNow,
  useServicesList,
  useSlots,
} from '@renderer/api/queries';
import { mastersWorkingOn } from '@renderer/lib/bookingFilters';
import { formatService } from '@renderer/lib/format';
import { splitFieldErrors } from '@renderer/lib/formErrors';
import { Button } from '@renderer/components/Button';
import { Field, inputClass } from '@renderer/components/Field';
import { Modal } from '@renderer/components/Modal';
import { Spinner } from '@renderer/components/Spinner';

/** Поля формы совпадают с `fields` ошибок сервера; первое поле с ошибкой получает фокус. */
const FORM_FIELDS = BOOKING_FIELDS;

const EMPTY: BookingFormInput = {
  serviceName: '',
  date: '',
  masterName: ANY_MASTER,
  time: '',
  clientName: '',
  phone: '',
};

export function NewBookingDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (booking: Booking) => void;
}) {
  const create = useCreateBooking();
  return (
    <Modal
      open={open}
      title="Новая запись"
      description="Запись клиента по телефону или на месте. Источник — «администратор»."
      onClose={onClose}
      closeDisabled={create.isPending}
      size="lg"
    >
      {open && <NewBookingForm create={create} onCancel={onClose} onCreated={onCreated} />}
    </Modal>
  );
}

function NewBookingForm({
  create,
  onCancel,
  onCreated,
}: {
  create: ReturnType<typeof useCreateBooking>;
  onCancel: () => void;
  onCreated: (booking: Booking) => void;
}) {
  const queryClient = useQueryClient();
  const { today } = useNow();
  const schema = useMemo(() => createBookingFormSchema(today), [today]);
  const [formError, setFormError] = useState<string | null>(null);

  const form = useForm<BookingFormInput, unknown, BookingFormValues>({
    resolver: zodResolver(schema),
    defaultValues: EMPTY,
  });
  const { errors } = form.formState;
  const [serviceName, date, masterName, time] = useWatch({
    control: form.control,
    name: ['serviceName', 'date', 'masterName', 'time'],
  });

  const services = useServicesList();
  const masters = useMasters();
  const dates = useDates(serviceName);
  const slots = useSlots(serviceName, date, masterName);

  const workingMasters = useMemo(
    () => (date === '' || masters.data === undefined ? [] : mastersWorkingOn(masters.data, date)),
    [date, masters.data],
  );

  // Значения ниже по цепочке сбрасываются, если после смены выбора или обновления данных они больше не доступны.
  useEffect(() => {
    if (date !== '' && dates.data !== undefined && !dates.data.includes(date)) {
      form.setValue('date', '');
    }
  }, [date, dates.data, form]);
  useEffect(() => {
    if (
      masterName !== ANY_MASTER &&
      masters.data !== undefined &&
      date !== '' &&
      !workingMasters.some((m) => m.name === masterName)
    ) {
      form.setValue('masterName', ANY_MASTER);
    }
  }, [masterName, workingMasters, masters.data, date, form]);
  useEffect(() => {
    if (time !== '' && slots.data !== undefined && !slots.data.slots.includes(time)) {
      form.setValue('time', '');
    }
  }, [time, slots.data, form]);

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const booking = await create.mutateAsync({
        clientName: values.clientName,
        phone: values.phone,
        serviceName: values.serviceName,
        masterName: values.masterName === ANY_MASTER ? null : values.masterName,
        date: values.date,
        time: values.time,
      });
      onCreated(booking);
    } catch (error) {
      if (isApiError(error) && error.code === 'slot_taken') {
        form.setValue('time', '');
        setFormError(`${error.message} Список свободного времени обновлён.`);
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.slotsAll() }),
          queryClient.invalidateQueries({ queryKey: queryKeys.dates(values.serviceName) }),
        ]);
        return;
      }
      const { fields, message } = splitFieldErrors<BookingField>(error, FORM_FIELDS);
      let first = true;
      for (const name of FORM_FIELDS) {
        const text = fields[name];
        if (text !== undefined) {
          form.setError(name, { type: 'server', message: text }, { shouldFocus: first });
          first = false;
        }
      }
      setFormError(message);
    }
  });

  if (services.isPending || masters.isPending) {
    return <Spinner label="Загрузка услуг и мастеров…" />;
  }
  if (services.isError || masters.isError) {
    const error = services.error ?? masters.error;
    return (
      <div role="alert" className="flex flex-col items-start gap-3 text-sm">
        <p className="text-red-700 dark:text-red-400">
          Не удалось загрузить справочники. {isApiError(error) ? error.message : ''}
        </p>
        <Button
          onClick={() => {
            void services.refetch();
            void masters.refetch();
          }}
        >
          Повторить
        </Button>
      </div>
    );
  }
  if (services.data.length === 0 || !masters.data.some((m) => m.active)) {
    return (
      <p role="alert" className="text-sm">
        В хранилище нет услуг или активных мастеров — запись невозможна.
      </p>
    );
  }

  const pending = create.isPending;

  return (
    <form onSubmit={(e) => void onSubmit(e)} noValidate className="flex flex-col gap-5" aria-busy={pending}>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Услуга" error={errors.serviceName?.message}>
          {(control) => (
            <select
              {...control}
              {...form.register('serviceName', {
                onChange: () => form.setValue('time', ''),
              })}
              autoFocus
              disabled={pending}
              className={inputClass}
            >
              <option value="">Выберите услугу</option>
              {services.data.map((s) => (
                <option key={s.name} value={s.name}>
                  {formatService(s)}
                </option>
              ))}
            </select>
          )}
        </Field>

        <Field
          label="Дата"
          error={errors.date?.message}
          hint={
            serviceName === ''
              ? 'Сначала выберите услугу.'
              : dates.isPending
                ? 'Загрузка дат…'
                : dates.isError
                  ? undefined
                  : dates.data.length === 0
                    ? `Мастера не работают в ближайшие ${DATES_AHEAD} дней.`
                    : undefined
          }
        >
          {(control) => (
            <select
              {...control}
              {...form.register('date', { onChange: () => form.setValue('time', '') })}
              disabled={pending || serviceName === '' || dates.data === undefined || dates.data.length === 0}
              className={inputClass}
            >
              <option value="">Выберите дату</option>
              {(dates.data ?? []).map((d) => {
                const relative = relativeDayLabel(d, today);
                return (
                  <option key={d} value={d}>
                    {relative !== '' ? `${relative}, ` : ''}
                    {formatDateLong(d)}
                  </option>
                );
              })}
            </select>
          )}
        </Field>
        {dates.isError && (
          <p role="alert" className="text-sm text-red-700 sm:col-span-2 dark:text-red-400">
            Не удалось загрузить даты. {isApiError(dates.error) ? dates.error.message : ''}{' '}
            <button type="button" className="underline" onClick={() => void dates.refetch()}>
              Повторить
            </button>
          </p>
        )}

        <Field
          label="Мастер"
          error={errors.masterName?.message}
          hint={
            date === ''
              ? 'Сначала выберите дату.'
              : workingMasters.length === 0
                ? 'В этот день мастера не работают.'
                : undefined
          }
        >
          {(control) => (
            <select
              {...control}
              {...form.register('masterName', { onChange: () => form.setValue('time', '') })}
              disabled={pending || date === ''}
              className={inputClass}
            >
              <option value={ANY_MASTER}>Любой мастер</option>
              {workingMasters.map((m) => (
                <option key={m.name} value={m.name}>
                  {m.name} ({m.workStart}–{m.workEnd})
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>

      <SlotPicker
        disabled={pending}
        date={date}
        slots={slots}
        error={errors.time?.message}
        register={form.register('time')}
      />

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Имя клиента" error={errors.clientName?.message}>
          {(control) => (
            <input
              {...control}
              {...form.register('clientName')}
              autoComplete="off"
              maxLength={100}
              disabled={pending}
              className={inputClass}
            />
          )}
        </Field>
        <Field label="Телефон" error={errors.phone?.message} hint="Например, +374 91 123456">
          {(control) => (
            <input
              {...control}
              {...form.register('phone')}
              type="tel"
              inputMode="tel"
              autoComplete="off"
              maxLength={20}
              disabled={pending}
              className={inputClass}
            />
          )}
        </Field>
      </div>

      {formError !== null && (
        <p
          role="alert"
          className="rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-100"
        >
          {formError}
        </p>
      )}

      <div className="flex flex-wrap justify-end gap-2 border-t border-slate-200 pt-4 dark:border-slate-700">
        <Button onClick={onCancel} disabled={pending}>
          Отмена
        </Button>
        <Button type="submit" variant="primary" disabled={pending}>
          {pending ? 'Сохранение…' : 'Записать'}
        </Button>
      </div>
    </form>
  );
}

function SlotPicker({
  date,
  slots,
  error,
  disabled,
  register,
}: {
  date: string;
  slots: ReturnType<typeof useSlots>;
  error: string | undefined;
  disabled: boolean;
  register: UseFormRegisterReturn<'time'>;
}) {
  const errorId = 'slot-error';
  let content;
  if (date === '') {
    content = <p className="text-sm text-slate-600 dark:text-slate-400">Сначала выберите услугу и дату.</p>;
  } else if (slots.isPending) {
    content = <Spinner label="Загрузка свободного времени…" />;
  } else if (slots.isError) {
    content = (
      <p role="alert" className="text-sm text-red-700 dark:text-red-400">
        Не удалось загрузить свободное время. {isApiError(slots.error) ? slots.error.message : ''}{' '}
        <button type="button" className="underline" onClick={() => void slots.refetch()}>
          Повторить
        </button>
      </p>
    );
  } else if (slots.data.slots.length === 0) {
    content = (
      <p role="status" className="text-sm text-slate-600 dark:text-slate-400">
        Нет свободного времени на этот день ({formatDateWithWeekday(date)}), выберите другую дату или мастера.
      </p>
    );
  } else {
    content = (
      <div className="flex flex-wrap gap-2">
        {slots.data.slots.map((slot) => (
          <label key={slot} className="relative">
            <input {...register} type="radio" value={slot} disabled={disabled} className="peer sr-only" />
            <span className="block cursor-pointer rounded-md border border-slate-300 px-3 py-1.5 text-sm tabular-nums peer-checked:border-indigo-600 peer-checked:bg-indigo-600 peer-checked:text-white peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-indigo-500 peer-disabled:cursor-not-allowed peer-disabled:opacity-50 hover:bg-slate-100 peer-checked:hover:bg-indigo-700 dark:border-slate-600 dark:hover:bg-slate-800 dark:peer-checked:border-indigo-400 dark:peer-checked:bg-indigo-500 dark:peer-checked:text-slate-950">
              {slot}
            </span>
          </label>
        ))}
      </div>
    );
  }
  return (
    <fieldset aria-describedby={error !== undefined ? errorId : undefined}>
      <legend className="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">
        Время{slots.isFetching && !slots.isPending ? ' (обновление…)' : ''}
      </legend>
      {content}
      {error !== undefined && (
        <p id={errorId} className="mt-1 text-xs font-medium text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
    </fieldset>
  );
}
