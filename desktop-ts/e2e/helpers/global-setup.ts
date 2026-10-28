import { rmSync } from 'node:fs';
import { PID_FILE } from './backend';

export default function globalSetup(): void {
  rmSync(PID_FILE, { force: true });
}
