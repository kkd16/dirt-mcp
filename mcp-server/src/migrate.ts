import { getMigrations } from 'better-auth/db/migration';
import { AccessRepository } from './access/repository.ts';
import { createAuth, createAuthOptions } from './auth.ts';
import { readAuthConfig } from './config.ts';
import { openDatabase } from './storage.ts';

async function main(): Promise<void> {
  const config = readAuthConfig(process.env);
  const database = openDatabase(config.databasePath);
  try {
    const repository = new AccessRepository(database);
    const migrations = await getMigrations(createAuthOptions(config, database, repository));
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
