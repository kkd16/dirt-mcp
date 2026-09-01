#!/usr/bin/env node

import { createHash, randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { createReadStream } from 'node:fs';
import { chmod, lstat, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';

const repositoryRoot = dirname(import.meta.dirname);
const releaseDirectory = join(repositoryRoot, 'build', 'release');
const rootManifest = JSON.parse(await readFile(join(repositoryRoot, 'package.json'), 'utf8'));
const productVersion = rootManifest.version;
if (typeof productVersion !== 'string' || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/u.test(productVersion)) {
  throw new Error('package.json version must be a safe product version.');
}
const expectedNodeVersion = `v${rootManifest.devEngines?.runtime?.version}`;
if (!/^v\d+\.\d+\.\d+$/u.test(expectedNodeVersion)) {
  throw new Error('package.json devEngines.runtime.version must be an exact Node.js version.');
}
const entries = await readdir(releaseDirectory);
const archives = entries.filter((entry) => /^dirt-mcp-.+-ubuntu-24\.04-x86_64\.tar\.gz$/u.test(entry));
if (archives.length !== 1) throw new Error(`Expected one native archive, found ${archives.length}.`);
const archiveName = archives[0];
const bundleName = `dirt-mcp-${productVersion}`;
const expectedArchiveName = `${bundleName}-ubuntu-24.04-x86_64.tar.gz`;
if (archiveName !== expectedArchiveName) {
  throw new Error(`Expected native archive ${expectedArchiveName}, found ${archiveName}.`);
}
const archive = join(releaseDirectory, archiveName);
const checksumFile = `${archive}.sha256`;
const bootstrap = join(releaseDirectory, 'dirt-install');

const checksumLine = (await readFile(checksumFile, 'utf8')).trim();
const expectedChecksum = checksumLine.match(/^([0-9a-f]{64})  ([^/]+)$/u);
if (expectedChecksum === null || expectedChecksum[2] !== archiveName) {
  throw new Error(`Invalid checksum file: ${basename(checksumFile)}`);
}
const actualChecksum = await sha256(archive);
if (actualChecksum !== expectedChecksum[1]) throw new Error('Native archive checksum mismatch.');
const bootstrapContents = await readFile(bootstrap, 'utf8');
if (!bootstrapContents.includes(`readonly archive_sha256='${actualChecksum}'`)) {
  throw new Error('Bootstrap does not pin the native archive checksum.');
}
await Promise.all([assertMode(archive, 0o644), assertMode(checksumFile, 0o644), assertMode(bootstrap, 0o755)]);

await run('bash', ['-n', bootstrap]);
await run('shellcheck', [bootstrap]);

const temporaryDirectory = await mkdtemp(join(tmpdir(), 'dirt-package-check-'));
try {
  const fakeBin = join(temporaryDirectory, 'fake-bin');
  await mkdir(fakeBin);
  const fakeCurl = join(fakeBin, 'curl');
  await writeFile(
    fakeCurl,
    `#!/usr/bin/env bash
set -Eeuo pipefail
output=''
url=''
while (($# > 0)); do
  case "$1" in
    --output)
      output=$2
      shift 2
      ;;
    https://*)
      url=$1
      shift
      ;;
    *)
      shift
      ;;
  esac
done
[[ "\${url}" == "\${DIRT_TEST_ARCHIVE_URL}" && -n "\${output}" ]]
cp -- "\${DIRT_TEST_ARCHIVE}" "\${output}"
`,
  );
  await chmod(fakeCurl, 0o755);
  await run('bash', [bootstrap, '--help'], {
    env: {
      ...process.env,
      DIRT_TEST_ARCHIVE: archive,
      DIRT_TEST_ARCHIVE_URL: `https://github.com/kkd16/dirt-mcp/releases/download/v${productVersion}/${archiveName}`,
      PATH: `${fakeBin}:${process.env.PATH ?? ''}`,
    },
  });

  await run('tar', ['--extract', '--gzip', '--same-permissions', '--file', archive, '--directory', temporaryDirectory]);
  const roots = await readdir(temporaryDirectory);
  const releaseRoots = roots.filter((entry) => entry !== 'fake-bin');
  if (releaseRoots.length !== 1 || releaseRoots[0] !== bundleName) {
    throw new Error('Native archive must contain one versioned top-level directory.');
  }
  const bundle = join(temporaryDirectory, releaseRoots[0]);
  const required = [
    'LICENSE',
    'Caddyfile.in',
    'app/dist/backup.js',
    'app/dist/index.js',
    'app/dist/init-db.js',
    'app/node_modules/better-sqlite3/build/Release/better_sqlite3.node',
    'dirt-mcp.service.in',
    'install.sh',
    'node/LICENSE',
    'node/bin/node',
    'paper-plugin.jar',
  ];
  await Promise.all(required.map(async (relative) => requireRegularFile(join(bundle, relative), relative)));
  await assertNormalizedModes(bundle);

  const archiveEntries = await listArchive(archive);
  for (const entry of archiveEntries) {
    if (entry.includes('/.dev/') || /(?:^|\/)(?:auth-secret|bridge-token|control-token)$/u.test(entry)) {
      throw new Error(`Native archive contains development state or a credential: ${entry}`);
    }
  }

  const installer = join(bundle, 'install.sh');
  await run('bash', ['-n', installer]);
  await run('shellcheck', [installer]);
  await run('bash', [installer, '--help']);

  const renderedUnit = (await readFile(join(bundle, 'dirt-mcp.service.in'), 'utf8'))
    .replaceAll('@HOSTNAME@', 'dirt.example')
    .replaceAll('@PAPER_CREDENTIAL_DIR@', '/srv/paper instance')
    .replaceAll('@PAPER_DIR@', '/srv/paper instance')
    .replaceAll('/opt/dirt-mcp', bundle);
  if (/@[A-Z0-9_]+@/u.test(renderedUnit)) throw new Error('Rendered systemd unit has an unresolved placeholder.');
  for (const credential of ['bridge-token', 'control-token']) {
    const expected = `LoadCredential=${credential}:/srv/paper instance/plugins/DirtMCP/secrets/${credential}`;
    if (!renderedUnit.includes(expected)) throw new Error(`Rendered systemd unit has an invalid ${credential} source.`);
  }
  const unit = join(temporaryDirectory, 'dirt-mcp.service');
  await writeFile(unit, renderedUnit);
  await run('systemd-analyze', ['verify', unit]);

  const node = join(bundle, 'node', 'bin', 'node');
  const nodeVersion = await capture(node, ['--version']);
  if (nodeVersion.trim() !== expectedNodeVersion) {
    throw new Error(`Unexpected bundled Node version: ${nodeVersion.trim()}`);
  }
  await run(
    node,
    [
      '--input-type=commonjs',
      '--eval',
      "const Database=require('./app/node_modules/better-sqlite3'); const db=new Database(':memory:'); db.exec('select 1'); db.close();",
    ],
    { cwd: bundle },
  );

  const secrets = join(temporaryDirectory, 'secrets');
  await run('mkdir', ['--mode=700', secrets]);
  const authSecret = join(secrets, 'auth-secret');
  const bridgeToken = join(secrets, 'bridge-token');
  const controlToken = join(secrets, 'control-token');
  await writeFile(authSecret, randomBytes(32).toString('hex'), { mode: 0o600 });
  await writeFile(bridgeToken, randomBytes(32).toString('hex'), { mode: 0o600 });
  await writeFile(controlToken, randomBytes(32).toString('hex'), { mode: 0o600 });
  const database = join(temporaryDirectory, 'dirt.sqlite3');
  const runtimeEnvironment = {
    ...process.env,
    DIRT_AUTH_SECRET_FILE: authSecret,
    DIRT_BRIDGE_TOKEN_FILE: bridgeToken,
    DIRT_CONTROL_TOKEN_FILE: controlToken,
    DIRT_DATABASE_PATH: database,
    DIRT_PUBLIC_ORIGIN: 'http://localhost:3000',
    NODE_ENV: 'production',
  };
  await run(node, [join(bundle, 'app', 'dist', 'init-db.js')], {
    cwd: bundle,
    env: runtimeEnvironment,
  });
  await requireRegularFile(database, 'initialized SQLite database');
  await assertPackagedServiceHealth(node, bundle, runtimeEnvironment);
  const backup = join(temporaryDirectory, 'dirt-backup.sqlite3');
  await run(node, [join(bundle, 'app', 'dist', 'backup.js'), backup], {
    cwd: bundle,
    env: runtimeEnvironment,
  });
  await requireRegularFile(backup, 'packaged SQLite backup');
} finally {
  await rm(temporaryDirectory, { recursive: true, force: true });
}

const originalUmask = process.umask(0o077);
try {
  await run(process.execPath, [join(repositoryRoot, 'scripts', 'package-native.mjs')], {
    cwd: repositoryRoot,
  });
} finally {
  process.umask(originalUmask);
}
const rebuiltChecksum = await sha256(archive);
if (rebuiltChecksum !== actualChecksum) {
  throw new Error(`Native archive is not reproducible: ${actualChecksum} != ${rebuiltChecksum}.`);
}

process.stdout.write(`Native package validation passed: ${archiveName}\n`);

async function listArchive(path) {
  return (await capture('tar', ['--list', '--gzip', '--file', path]))
    .split(/\r?\n/u)
    .filter((entry) => entry.length > 0);
}

async function sha256(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}

async function requireRegularFile(path, description) {
  let metadata;
  try {
    metadata = await stat(path);
  } catch (error) {
    throw new Error(`Missing ${description}: ${path}`, { cause: error });
  }
  if (!metadata.isFile()) throw new Error(`Expected a regular file: ${path}`);
}

async function assertMode(path, expected) {
  const actual = (await stat(path)).mode & 0o777;
  if (actual !== expected) {
    throw new Error(`Expected mode ${expected.toString(8)} for ${path}, found ${actual.toString(8)}.`);
  }
}

async function assertNormalizedModes(path) {
  const metadata = await lstat(path);
  if (metadata.isSymbolicLink()) return;
  const actual = metadata.mode & 0o777;
  if (metadata.isFile()) {
    if (actual !== 0o644 && actual !== 0o755) {
      throw new Error(`Release file has non-normalized mode ${actual.toString(8)}: ${path}`);
    }
    return;
  }
  if (!metadata.isDirectory()) throw new Error(`Unexpected release bundle entry: ${path}`);
  if (actual !== 0o755) {
    throw new Error(`Release directory has non-normalized mode ${actual.toString(8)}: ${path}`);
  }
  const children = await readdir(path);
  await Promise.all(children.map(async (entry) => assertNormalizedModes(join(path, entry))));
}

async function assertPackagedServiceHealth(node, bundle, environment) {
  await assertPortAvailable(3_000);
  const child = spawn(node, [join(bundle, 'app', 'dist', 'index.js')], {
    cwd: join(bundle, 'app'),
    env: environment,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });

  try {
    for (let attempt = 0; attempt < 50; attempt += 1) {
      if (child.exitCode !== null || child.signalCode !== null) {
        throw serviceFailure('exited before becoming healthy', stdout, stderr);
      }
      try {
        // Health polling is intentionally sequential while the packaged listener starts.
        // oxlint-disable-next-line eslint/no-await-in-loop
        const response = await fetch('http://127.0.0.1:3000/healthz', {
          signal: AbortSignal.timeout(500),
        });
        // oxlint-disable-next-line eslint/no-await-in-loop
        if (response.ok && (await response.json()).status === 'ok' && stderr.includes('"event":"runtime.started"')) {
          return;
        }
      } catch {
        // The listener may not be ready yet.
      }
      // oxlint-disable-next-line eslint/no-await-in-loop
      await delay(100);
    }
    throw serviceFailure('did not become healthy', stdout, stderr);
  } finally {
    if (child.exitCode === null && child.signalCode === null) {
      const exited = once(child, 'exit');
      child.kill('SIGTERM');
      if (
        (await Promise.race([exited.then(() => true), delay(5_000, undefined, { ref: false }).then(() => false)])) ===
        false
      ) {
        child.kill('SIGKILL');
        await exited;
      }
    }
  }
}

async function assertPortAvailable(port) {
  await new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.once('error', (error) => {
      reject(
        new Error(`TCP port ${String(port)} must be free to check the packaged service; run make down first.`, {
          cause: error,
        }),
      );
    });
    server.listen({ exclusive: true, host: '127.0.0.1', port }, () => {
      server.close((error) => {
        if (error === undefined) resolve();
        else reject(error);
      });
    });
  });
}

function serviceFailure(reason, stdout, stderr) {
  return new Error(`Packaged service ${reason}.\nstdout:\n${stdout.trim()}\nstderr:\n${stderr.trim()}`);
}

async function capture(command, arguments_) {
  return await new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    const child = spawn(command, arguments_, {
      env: process.env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve(stdout);
      else reject(new Error(`${command} failed: ${stderr.trim() || signal || code}`));
    });
  });
}

async function run(command, arguments_, options = {}) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, {
      cwd: options.cwd,
      env: options.env ?? process.env,
      stdio: 'inherit',
    });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} failed with ${signal === null ? `exit code ${code}` : `signal ${signal}`}.`));
    });
  });
}
