import { getMigrations } from 'better-auth/db/migration';
import { AccessRepository } from './access/repository.ts';
import { createAuth } from './auth.ts';
import { readRuntimeConfig } from './config.ts';
import { openDatabase } from './storage.ts';

async function main(): Promise<void> {
  const config = readRuntimeConfig(process.env);
  const database = openDatabase(config.databasePath);
  try {
    const repository = new AccessRepository(database);
    const auth = createAuth(config, database, repository);
    const migrations = await getMigrations(auth.options, { throwOnUnsafe: false });
    if (migrations.unsafeChanges.length > 0) {
      throw new Error(`Unsafe database migration refused:\n${migrations.unsafeChanges.join('\n')}`);
    }
    await migrations.runMigrations();
    repository.assertSchema();
    process.stdout.write('Dirt database migration complete.\n');
  } finally {
    database.close();
  }
}

await main();
