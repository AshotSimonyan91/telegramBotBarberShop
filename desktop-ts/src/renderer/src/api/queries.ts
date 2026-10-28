import {
  keepPreviousData,
  QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryKey,
} from '@tanstack/react-query';
import { isApiError } from '@shared/api/client';
import type { BookingsQuery, CreateBookingRequest, LocalDate } from '@shared/api/schemas';
import { currentDateTime, datePart, type ServerClock } from '@shared/datetime';
import { useApi, useServices } from './ApiContext';

export const BOOKINGS_REFRESH_MS = 30_000;
export const STATUS_REFRESH_MS = 15_000;
export const DATES_AHEAD = 14;

export const queryKeys = {
  all: ['api'] as const,
  settings: ['settings'] as const,
  health: () => [...queryKeys.all, 'health'] as const,
  status: () => [...queryKeys.all, 'status'] as const,
  services: () => [...queryKeys.all, 'services'] as const,
  masters: () => [...queryKeys.all, 'masters'] as const,
  bookings: () => [...queryKeys.all, 'bookings'] as const,
  bookingList: (query: BookingsQuery) => [...queryKeys.bookings(), query] as const,
  dates: (serviceName: string) => [...queryKeys.all, 'dates', serviceName] as const,
  slotsAll: () => [...queryKeys.all, 'slots'] as const,
  slots: (serviceName: string, date: string, masterName: string) =>
    [...queryKeys.slotsAll(), serviceName, date, masterName] as const,
} satisfies Record<string, QueryKey | ((...args: never[]) => QueryKey)>;

/** Повторять только сетевые сбои и 5xx; ошибки 4xx повтором не исправить. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= 2) {
    return false;
  }
  if (!isApiError(error)) {
    return failureCount < 1;
  }
  return error.kind === 'network' || (error.status !== null && error.status >= 500);
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetry, refetchOnWindowFocus: true, staleTime: 5_000 },
      mutations: { retry: false },
    },
  });
}

export function useSettings() {
  const { settings } = useServices();
  return useQuery({ queryKey: queryKeys.settings, queryFn: () => settings.get(), staleTime: Infinity });
}

export function useStatus() {
  const api = useApi();
  return useQuery({
    queryKey: queryKeys.status(),
    queryFn: () => api.status(),
    refetchInterval: STATUS_REFRESH_MS,
    retry: false,
  });
}

/** Часы сервера (GET /health): «сегодня» и «сейчас» считаются в его часовом поясе. */
export function useServerClock() {
  const api = useApi();
  return useQuery({
    queryKey: queryKeys.health(),
    queryFn: async (): Promise<ServerClock & { timezone: string }> => {
      const health = await api.health();
      return { serverNow: health.now, receivedAt: Date.now(), timezone: health.timezone };
    },
    staleTime: 10 * 60_000,
    refetchInterval: 10 * 60_000,
  });
}

/** Сегодняшняя дата и текущий момент по часам сервера (или компьютера, пока /health не ответил). */
export function useNow(): { now: string; today: LocalDate } {
  const clock = useServerClock().data ?? null;
  const now = currentDateTime(clock);
  return { now, today: datePart(now) };
}

export function useServicesList() {
  const api = useApi();
  return useQuery({ queryKey: queryKeys.services(), queryFn: () => api.services(), staleTime: 5 * 60_000 });
}

export function useMasters() {
  const api = useApi();
  return useQuery({ queryKey: queryKeys.masters(), queryFn: () => api.masters(), staleTime: 5 * 60_000 });
}

export function useBookings(query: BookingsQuery, enabled = true) {
  const api = useApi();
  return useQuery({
    queryKey: queryKeys.bookingList(query),
    queryFn: () => api.bookings(query),
    refetchInterval: BOOKINGS_REFRESH_MS,
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function useDates(serviceName: string) {
  const api = useApi();
  return useQuery({
    queryKey: queryKeys.dates(serviceName),
    queryFn: () => api.dates({ serviceName, days: DATES_AHEAD }),
    enabled: serviceName !== '',
    staleTime: 0,
  });
}

/** `masterName` пустой — любой мастер. */
export function useSlots(serviceName: string, date: string, masterName: string) {
  const api = useApi();
  return useQuery({
    queryKey: queryKeys.slots(serviceName, date, masterName),
    queryFn: () => api.slots({ serviceName, date, masterName: masterName === '' ? null : masterName }),
    enabled: serviceName !== '' && date !== '',
    staleTime: 0,
  });
}

export function useCreateBooking() {
  const api = useApi();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateBookingRequest) => api.createBooking(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: queryKeys.bookings() });
    },
  });
}

export function useCancelBooking() {
  const api = useApi();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.cancelBooking(id),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: queryKeys.bookings() });
    },
  });
}
