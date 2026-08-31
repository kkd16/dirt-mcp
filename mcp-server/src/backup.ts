import { chmod, link, mkdtemp, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { readDatabasePath } from './config.ts';
import { openDatabase } from './storage.ts';

async function main(): Promise<void> {
  const destinationArgument = process.argv[2];
  if (destinationArgument === undefined || destinationArgument.trim().length === 0) {
    throw new Error('Usage: pnpm backup <destination>');
  }
  const databasePath = readDatabasePath(process.env.DIRT_DATABASE_PATH);
  const destination = resolve(destinationArgument);
  if (destination === databasePath) throw new Error('Backup destination must differ from DIRT_DATABASE_PATH.');
  const temporaryDirectory = await mkdtemp(join(dirname(destination), '.dirt-backup-'));
  const temporaryBackup = join(temporaryDirectory, 'backup.sqlite');
  try {
    const database = openDatabase(databasePath, true);
    try {
      await database.backup(temporaryBackup);
    } finally {
      database.close();
    }
    await chmod(temporaryBackup, 0o600);
    try {
      // Publishing with a hard link is atomic and fails if any destination
      // entry already exists, including a dangling symbolic link.
      await link(temporaryBackup, destination);
    } catch (error: unknown) {
      if (error instanceof Error && 'code' in error && error.code === 'EEXIST') {
        throw new Error('Backup destination already exists; refusing to overwrite it.', { cause: error });
      }
      throw error;
    }
  } finally {
    await rm(temporaryDirectory, { recursive: true, force: true });
  }
  process.stdout.write(`Dirt database backup complete: ${destination}\n`);
}

await main();
