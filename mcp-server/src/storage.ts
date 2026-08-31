import Database from 'better-sqlite3';
import { chmodSync, closeSync, constants, openSync } from 'node:fs';

export function openDatabase(path: string, readonly = false): Database.Database {
  if (!readonly) {
    const descriptor = openSync(path, constants.O_CREAT | constants.O_RDONLY, 0o600);
    closeSync(descriptor);
    chmodSync(path, 0o600);
    secureSidecars(path);
  }
  const database = new Database(path, { readonly, fileMustExist: readonly });
  try {
    configureDatabase(database, readonly);
    if (!readonly) secureSidecars(path);
    return database;
  } catch (error: unknown) {
    database.close();
    throw error;
  }
}

function configureDatabase(database: Database.Database, readonly: boolean): void {
  database.pragma('foreign_keys = ON');
  database.pragma('busy_timeout = 5000');
  if (readonly) return;
  database.pragma('journal_mode = WAL');
  database.pragma('synchronous = FULL');
}

function secureSidecars(path: string): void {
  for (const suffix of ['-journal', '-shm', '-wal']) {
    try {
      chmodSync(`${path}${suffix}`, 0o600);
    } catch (error: unknown) {
      if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error;
    }
  }
}
