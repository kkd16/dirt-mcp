import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { openDatabase } from '../dist/storage.js';

test('writers secure SQLite files while readonly opens leave filesystem metadata unchanged', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dirt-storage-test-'));
  const path = join(directory, 'dirt.sqlite');
  const previousUmask = process.umask(0);
  let openedDatabase: ReturnType<typeof openDatabase> | undefined;
  try {
    openedDatabase = openDatabase(path);
  } finally {
    process.umask(previousUmask);
  }
  assert.ok(openedDatabase !== undefined);
  const database = openedDatabase;

  try {
    database.exec("CREATE TABLE marker (value TEXT NOT NULL); INSERT INTO marker VALUES ('safe')");
    assert.equal(statSync(path).mode & 0o777, 0o600);
    assert.equal(statSync(`${path}-wal`).mode & 0o777, 0o600);
    assert.equal(statSync(`${path}-shm`).mode & 0o777, 0o600);
    chmodSync(path, 0o444);
    chmodSync(`${path}-wal`, 0o444);
    chmodSync(`${path}-shm`, 0o444);
    const concurrentReadonly = openDatabase(path, true);
    try {
      assert.equal(statSync(path).mode & 0o777, 0o444);
      assert.equal(statSync(`${path}-wal`).mode & 0o777, 0o444);
      assert.equal(statSync(`${path}-shm`).mode & 0o777, 0o444);
    } finally {
      concurrentReadonly.close();
    }
  } finally {
    database.close();
  }

  chmodSync(path, 0o444);
  const readonlyDatabase = openDatabase(path, true);
  try {
    assert.equal(statSync(path).mode & 0o777, 0o444);
    assert.equal(readonlyDatabase.prepare<[], { value: string }>('SELECT value FROM marker').get()?.value, 'safe');
  } finally {
    readonlyDatabase.close();
    rmSync(directory, { recursive: true, force: true });
  }
});
