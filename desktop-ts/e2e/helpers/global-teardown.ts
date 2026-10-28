import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, rmSync } from 'node:fs';
import { PID_FILE } from './backend';

function commandOf(pid: number): string {
  try {
    return execFileSync('ps', ['-p', String(pid), '-o', 'command='], { encoding: 'utf8' });
  } catch {
    return '';
  }
}

/**
 * Страховка: добивает java-процессы, которые тесты запустили и не остановили. Убиваются только PID из файла,
 * и только если это всё ещё тестовый бэкенд (salon-bot.jar с --telegrambots.enabled=false) — PID мог устареть.
 */
export default function globalTeardown(): void {
  if (!existsSync(PID_FILE)) {
    return;
  }
  for (const line of readFileSync(PID_FILE, 'utf8').split('\n')) {
    const pid = Number(line.trim());
    if (pid > 0) {
      const command = commandOf(pid);
      if (command.includes('salon-bot.jar') && command.includes('--telegrambots.enabled=false')) {
        try {
          process.kill(pid, 'SIGKILL');
        } catch {
          // уже завершился
        }
      }
    }
  }
  rmSync(PID_FILE, { force: true });
}
