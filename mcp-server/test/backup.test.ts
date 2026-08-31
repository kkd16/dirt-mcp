import assert from 'node:assert/strict';
import { existsSync, lstatSync, mkdtempSync, readdirSync, rmSync, statSync, symlinkSync } from 'node:fs';
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
  assert.equal(statSync(destination).mode & 0o777, 0o600);
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

test('backup command refuses dangling symbolic links and removes temporary files', () => {
  const symlinkDestination = join(directory, 'dangling-backup.sqlite');
  const absentTarget = join(directory, 'absent.sqlite');
  symlinkSync(absentTarget, symlinkDestination);

  const result = spawnSync(process.execPath, ['dist/backup.js', symlinkDestination], {
    cwd: new URL('..', import.meta.url),
    encoding: 'utf8',
    env: environment,
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /refusing to overwrite/u);
  assert.equal(lstatSync(symlinkDestination).isSymbolicLink(), true);
  assert.equal(existsSync(absentTarget), false);
  assert.deepEqual(
    readdirSync(directory).filter((entry) => entry.startsWith('.dirt-backup-')),
    [],
  );
});
