import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { after, test } from 'node:test';
import Database from 'better-sqlite3';

const directory = mkdtempSync(join(tmpdir(), 'dirt-backup-test-'));
const source = join(directory, 'source.sqlite');
const destination = join(directory, 'backup.sqlite');
const database = new Database(source);
database.exec("CREATE TABLE marker (value TEXT NOT NULL); INSERT INTO marker (value) VALUES ('safe')");
database.close();

const environment = {
  ...process.env,
  DIRT_MCP_BRIDGE_TOKEN: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  DIRT_CONTROL_TOKEN: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
  DIRT_AUTH_SECRET: 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
  DIRT_PUBLIC_ORIGIN: 'http://127.0.0.1:3000',
  DIRT_DATABASE_PATH: source,
};

after(() => rmSync(directory, { recursive: true, force: true }));

test('backup command uses SQLite online backup and refuses overwrite', () => {
  const first = spawnSync(process.execPath, ['dist/backup.js', destination], {
    cwd: new URL('..', import.meta.url),
    encoding: 'utf8',
    env: environment,
  });
  assert.equal(first.status, 0, first.stderr);
  assert.equal(existsSync(destination), true);
  const backup = new Database(destination, { readonly: true });
  assert.equal((backup.prepare('SELECT value FROM marker').get() as { value: string }).value, 'safe');
  backup.close();

  const second = spawnSync(process.execPath, ['dist/backup.js', destination], {
    cwd: new URL('..', import.meta.url),
    encoding: 'utf8',
    env: environment,
  });
  assert.notEqual(second.status, 0);
  assert.match(second.stderr, /refusing to overwrite/u);
  const unchanged = new Database(destination, { readonly: true });
  assert.equal((unchanged.prepare('SELECT value FROM marker').get() as { value: string }).value, 'safe');
  unchanged.close();
});
