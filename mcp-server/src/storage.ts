import Database from 'better-sqlite3';
import { configureDatabase } from './access/repository.ts';

export function openDatabase(path: string, readonly = false): Database.Database {
  const database = new Database(path, { readonly, fileMustExist: readonly });
  configureDatabase(database, readonly);
  return database;
}
