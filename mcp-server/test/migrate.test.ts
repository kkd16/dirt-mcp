import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import Database from 'better-sqlite3';

test('migration awaits OAuth initialization before closing SQLite and is repeatable', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dirt-migrate-test-'));
  const databasePath = join(directory, 'dirt.sqlite');
  const authSecretPath = join(directory, 'auth-secret');
  writeFileSync(authSecretPath, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\n', {
    mode: 0o600,
  });
  const environment = {
    ...process.env,
    DIRT_AUTH_SECRET_FILE: authSecretPath,
    DIRT_PUBLIC_ORIGIN: 'http://localhost:3000',
    DIRT_DATABASE_PATH: databasePath,
  };

  try {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const result = spawnSync(process.execPath, ['dist/migrate.js'], {
        cwd: new URL('..', import.meta.url),
        encoding: 'utf8',
        env: environment,
      });
      assert.equal(result.status, 0, result.stderr);
    }

    const database = new Database(databasePath, { readonly: true });
    try {
      const resource = database
        .prepare<[string], { count: number }>('SELECT COUNT(*) AS count FROM oauthResource WHERE identifier = ?')
        .get('http://localhost:3000/mcp');
      assert.equal(resource?.count, 1);
    } finally {
      database.close();
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
