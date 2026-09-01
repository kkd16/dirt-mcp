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
    const existingTables = database
      .prepare<[], { count: number }>(
        "SELECT COUNT(*) AS count FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
      )
      .get();
    if (existingTables?.count === 0) {
      const freshSchema = await getMigrations(createAuthOptions(config, database, repository));
      await freshSchema.runMigrations();
    }
    repository.assertSchema();
    // OAuth Provider seeds the configured MCP resource during Better Auth
    // initialization. Await its public readiness promise before closing the
    // externally owned SQLite connection.
    await createAuth(config, database, repository).$context;
    process.stdout.write('Dirt database schema is ready.\n');
  } finally {
    database.close();
  }
}

await main();
