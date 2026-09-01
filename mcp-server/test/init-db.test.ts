import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import Database from 'better-sqlite3';

test('database initialization creates the current schema and accepts it on restart', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dirt-init-db-test-'));
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
      const result = spawnSync(process.execPath, ['dist/init-db.js'], {
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
      const userColumns = new Set(
        database
          .prepare<[], { name: string }>('PRAGMA table_info("user")')
          .all()
          .map((row) => row.name),
      );
      assert.equal(userColumns.has('handle'), false);
      assert.equal(userColumns.has('minecraftName'), false);
    } finally {
      database.close();
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('database initialization rejects an existing legacy schema', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dirt-init-db-legacy-test-'));
  const databasePath = join(directory, 'dirt.sqlite');
  const authSecretPath = join(directory, 'auth-secret');
  writeFileSync(authSecretPath, 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\n', {
    mode: 0o600,
  });
  const database = new Database(databasePath);
  database.exec('CREATE TABLE "user" (id TEXT PRIMARY KEY, handle TEXT NOT NULL)');
  database.close();

  try {
    const result = spawnSync(process.execPath, ['dist/init-db.js'], {
      cwd: new URL('..', import.meta.url),
      encoding: 'utf8',
      env: {
        ...process.env,
        DIRT_AUTH_SECRET_FILE: authSecretPath,
        DIRT_PUBLIC_ORIGIN: 'http://localhost:3000',
        DIRT_DATABASE_PATH: databasePath,
      },
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /incompatible with this release/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
