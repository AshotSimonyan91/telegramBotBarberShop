import { isApiError } from '@shared/api/client';

/** Разбор ошибки сервера для формы: ошибки известных полей и общее сообщение. */
export function splitFieldErrors<F extends string>(
  error: unknown,
  knownFields: readonly F[],
): { fields: Partial<Record<F, string>>; message: string | null } {
  if (!isApiError(error)) {
    return { fields: {}, message: 'Непредвиденная ошибка. Повторите попытку.' };
  }
  const fields: Partial<Record<F, string>> = {};
  const unknown: string[] = [];
  for (const [name, text] of Object.entries(error.fields)) {
    if ((knownFields as readonly string[]).includes(name)) {
      fields[name as F] = text;
    } else {
      unknown.push(text);
    }
  }
  const hasFieldErrors = Object.keys(fields).length > 0;
  const message = unknown.length > 0 ? [error.message, ...unknown].join(' ') : hasFieldErrors ? null : error.message;
  return { fields, message };
}
