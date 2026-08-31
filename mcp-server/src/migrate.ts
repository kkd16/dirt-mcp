import { getMigrations } from 'better-auth/db/migration';
import { AccessRepository } from './access/repository.ts';
import { createAuth, createAuthOptions } from './auth.ts';
import { readRuntimeConfig } from './config.ts';
import { openDatabase } from './storage.ts';

async function main(): Promise<void> {
  const config = readRuntimeConfig(process.env);
  const database = openDatabase(config.databasePath);
  try {
    const repository = new AccessRepository(database);
    const migrations = await getMigrations(createAuthOptions(config, database, repository), { throwOnUnsafe: false });
    if (migrations.unsafeChanges.length > 0) {
      throw new Error(`Unsafe database migration refused:\n${migrations.unsafeChanges.join('\n')}`);
    }
    await migrations.runMigrations();
    repository.assertSchema();
    // OAuth Provider seeds the configured MCP resource during Better Auth
    // initialization. Await its public readiness promise before closing the
    // externally owned SQLite connection.
    await createAuth(config, database, repository).$context;
    process.stdout.write('Dirt database migration complete.\n');
  } finally {
    database.close();
  }
}

await main();
