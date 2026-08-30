import { constants } from 'node:fs';
import { access, stat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { readDatabasePath } from './config.ts';
import { openDatabase } from './storage.ts';

async function main(): Promise<void> {
  const destinationArgument = process.argv[2];
  if (destinationArgument === undefined || destinationArgument.trim().length === 0) {
    throw new Error('Usage: pnpm --filter @dirt-mcp/server backup -- <destination>');
  }
  const databasePath = readDatabasePath(process.env.DIRT_DATABASE_PATH);
  const destination = resolve(destinationArgument);
  if (destination === databasePath) throw new Error('Backup destination must differ from DIRT_DATABASE_PATH.');
  await access(dirname(destination), constants.W_OK);
  try {
    await stat(destination);
    throw new Error('Backup destination already exists; refusing to overwrite it.');
  } catch (error: unknown) {
    if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error;
  }

  const database = openDatabase(databasePath, true);
  try {
    await database.backup(destination);
    process.stdout.write(`Dirt database backup complete: ${destination}\n`);
  } finally {
    database.close();
  }
}

await main();
