import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, readFileSync, appendFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

/**
 * Изолированный экземпляр бэкенда для E2E. Всегда: рабочая папка — новая временная (поэтому корневые
 * `.env`, `salon_bookings.xlsx`, `backups/`, `logs/` проекта не затрагиваются), свободный порт (не 8787),
 * тестовый токен, фиктивный токен бота `1:F` и отключённый long polling Telegram.
 */

const REPO_ROOT = resolve(__dirname, '../../..');
export const JAR = process.env.E2E_JAR ?? join(REPO_ROOT, 'target', 'salon-bot.jar');
export const API_TOKEN = 'e2e-test-token-0123456789abcdef';
export const FORBIDDEN_PORT = 8787;

/** Файл со списком PID запущенных java-процессов — для зачистки в globalTeardown при аварии. */
export const PID_FILE = join(tmpdir(), 'salon-e2e-pids.txt');

const SCRUBBED_PREFIXES = ['BOT_', 'API_', 'EXCEL', 'ADMIN_', 'TIMEZONE', 'SALON_', 'SPRING_'];

export function freePort(): Promise<number> {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address !== null ? address.port : 0;
      server.close(() => resolvePort(port));
    });
  });
}

export interface ApiResult<T = unknown> {
  status: number;
  body: T;
  headers: Headers;
}

export class Backend {
  private constructor(
    readonly dir: string,
    readonly port: number,
    readonly token: string,
    private child: ChildProcess,
    private readonly env: NodeJS.ProcessEnv,
  ) {}

  get url(): string {
    return `http://127.0.0.1:${this.port}`;
  }

  get excelFile(): string {
    return join(this.dir, 'e2e.xlsx');
  }

  get log(): string {
    try {
      return readFileSync(join(this.dir, 'backend.log'), 'utf8');
    } catch {
      return '';
    }
  }

  static async start(): Promise<Backend> {
    if (!existsSync(JAR)) {
      throw new Error(`Нет ${JAR}: соберите бэкенд (mvn package -DskipTests в корне репозитория).`);
    }
    const dir = mkdtempSync(join(tmpdir(), 'salon-e2e-'));
    const port = await freePort();
    if (port === FORBIDDEN_PORT) {
      throw new Error('Выбран порт 8787 — он зарезервирован под боевой бот');
    }
    const env: NodeJS.ProcessEnv = {};
    for (const [key, value] of Object.entries(process.env)) {
      if (!SCRUBBED_PREFIXES.some((p) => key.startsWith(p))) {
        env[key] = value;
      }
    }
    Object.assign(env, {
      BOT_TOKEN: '1:F',
      EXCEL_PATH: join(dir, 'e2e.xlsx'),
      API_PORT: String(port),
      API_TOKEN,
      API_ENABLED: 'true',
    });
    return Backend.spawnIn(dir, port, env);
  }

  private static async spawnIn(dir: string, port: number, env: NodeJS.ProcessEnv): Promise<Backend> {
    if (existsSync(join(dir, '.env'))) {
      throw new Error('В рабочей папке бэкенда неожиданно есть .env');
    }
    const logFile = join(dir, 'backend.log');
    const child = spawn('java', ['-jar', JAR, '--telegrambots.enabled=false'], {
      cwd: dir,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const append = (chunk: Buffer) => appendFileSync(logFile, chunk);
    child.stdout?.on('data', append);
    child.stderr?.on('data', append);
    if (child.pid !== undefined) {
      appendFileSync(PID_FILE, `${child.pid}\n`);
    }
    const backend = new Backend(dir, port, env.API_TOKEN ?? '', child, env);
    await backend.waitReady();
    return backend;
  }

  private async waitReady(): Promise<void> {
    const deadline = Date.now() + 60_000;
    while (Date.now() < deadline) {
      if (this.child.exitCode !== null) {
        throw new Error(`Бэкенд завершился (код ${this.child.exitCode}):\n${this.log}`);
      }
      try {
        const res = await fetch(`${this.url}/api/v1/health`);
        if (res.ok) {
          return;
        }
      } catch {
        // ещё не поднялся
      }
      await new Promise((r) => setTimeout(r, 250));
    }
    await this.stop();
    throw new Error(`Бэкенд не поднялся за 60 с:\n${this.log}`);
  }

  /** Останавливает процесс; true, если он завершился сам по SIGTERM. */
  async stop(): Promise<boolean> {
    const child = this.child;
    if (child.exitCode !== null || child.signalCode !== null) {
      return true;
    }
    const exited = new Promise<void>((r) => child.once('exit', () => r()));
    child.kill('SIGTERM');
    const timer = setTimeout(() => child.kill('SIGKILL'), 10_000);
    await exited;
    clearTimeout(timer);
    return child.signalCode !== 'SIGKILL';
  }

  /** Полная очистка: процесс и временная папка. */
  async dispose(): Promise<void> {
    await this.stop();
    rmSync(this.dir, { recursive: true, force: true });
  }

  /** Остановить, удалить xlsx и поднять заново на том же порту — чистые данные. */
  async reset(): Promise<void> {
    await this.stop();
    for (const name of ['e2e.xlsx', '.e2e.xlsx.lock']) {
      rmSync(join(this.dir, name), { force: true });
    }
    rmSync(join(this.dir, 'backups'), { recursive: true, force: true });
    const fresh = await Backend.spawnIn(this.dir, this.port, this.env);
    this.child = fresh.child;
  }

  /** Остановить (если ещё работает) и поднять заново на тех же данных, порту и токене. */
  async restartKeepingData(): Promise<void> {
    await this.stop();
    const fresh = await Backend.spawnIn(this.dir, this.port, this.env);
    this.child = fresh.child;
  }

  async api<T = unknown>(
    method: 'GET' | 'POST',
    path: string,
    options: { query?: Record<string, string>; body?: unknown; token?: string | null } = {},
  ): Promise<ApiResult<T>> {
    const url = new URL(`${this.url}/api/v1${path}`);
    for (const [k, v] of Object.entries(options.query ?? {})) {
      url.searchParams.set(k, v);
    }
    const headers: Record<string, string> = {};
    const token = options.token === undefined ? this.token : options.token;
    if (token !== null) {
      headers.Authorization = `Bearer ${token}`;
    }
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(url, {
      method,
      headers,
      ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
    });
    const text = await res.text();
    let body: unknown = null;
    try {
      body = text === '' ? null : (JSON.parse(text) as unknown);
    } catch {
      body = text;
    }
    return { status: res.status, body: body as T, headers: res.headers };
  }
}
