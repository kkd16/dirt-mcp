#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { chmod, copyFile, lstat, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawn } from 'node:child_process';

const repositoryRoot = dirname(import.meta.dirname);
const releaseDirectory = join(repositoryRoot, 'build', 'release');
const cacheDirectory = join(repositoryRoot, '.dev', 'cache');
const target = 'ubuntu-24.04-x86_64';
const installedApplicationDirectory = '/opt/dirt-mcp/app';
const rootManifest = JSON.parse(await readFile(join(repositoryRoot, 'package.json'), 'utf8'));
const version = requiredString(rootManifest.version, 'package.json version');
const nodeRuntime = rootManifest.devEngines?.runtime;
if (nodeRuntime?.name !== 'node') throw new Error('package.json devEngines.runtime must select Node.js.');
const nodeVersion = requiredString(nodeRuntime.version, 'package.json devEngines.runtime.version');
if (!/^\d+\.\d+\.\d+$/u.test(nodeVersion)) throw new Error(`Unsafe Node.js version: ${nodeVersion}`);
const nodeMajor = Number.parseInt(nodeVersion, 10);
const expectedNodeEngine = `>=${nodeVersion} <${nodeMajor + 1}`;
if (rootManifest.engines?.node !== expectedNodeEngine) {
  throw new Error(`package.json engines.node must be exactly ${expectedNodeEngine}.`);
}
const expectedPnpmVersion = requiredString(rootManifest.engines?.pnpm, 'package.json engines.pnpm');
const nodeArchiveName = `node-v${nodeVersion}-linux-x64.tar.xz`;
const nodeArchiveSha256 = nodeArchiveChecksum(nodeVersion);
const nodeArchiveUrl = `https://nodejs.org/dist/v${nodeVersion}/${nodeArchiveName}`;

await assertBuildPlatform();
if (process.versions.node !== nodeVersion) {
  throw new Error(`Native packages must be built with Node.js ${nodeVersion}; found ${process.version}.`);
}
const actualPnpmVersion = (await capture('pnpm', ['--version'])).trim();
if (actualPnpmVersion !== expectedPnpmVersion) {
  throw new Error(`Native packages must be built with pnpm ${expectedPnpmVersion}; found ${actualPnpmVersion}.`);
}

const properties = parseProperties(await readFile(join(repositoryRoot, 'gradle.properties'), 'utf8'));
const gradleVersion = requiredProperty(properties, 'projectVersion');
const serverManifest = JSON.parse(await readFile(join(repositoryRoot, 'mcp-server', 'package.json'), 'utf8'));
const serverVersion = requiredString(serverManifest.version, 'mcp-server/package.json version');
if (gradleVersion !== version || serverVersion !== version) {
  throw new Error(
    `Product versions must match: package.json=${version}, gradle.properties=${gradleVersion}, mcp-server/package.json=${serverVersion}.`,
  );
}
const paperVersion = requiredProperty(properties, 'paperVersion');
const paperBuild = requiredProperty(properties, 'paperBuild');
const faweMavenVersion = requiredProperty(properties, 'faweMavenVersion');
const faweModrinthVersionId = requiredProperty(properties, 'faweModrinthVersionId');
const faweVersion = /^(\d+\.\d+\.\d+)(?:-|$)/u.exec(faweMavenVersion)?.[1];
if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/u.test(version)) throw new Error(`Unsafe product version: ${version}`);
if (!/^\d+(?:\.\d+)+$/u.test(paperVersion)) throw new Error(`Unsafe paperVersion: ${paperVersion}`);
if (!/^\d+$/u.test(paperBuild)) throw new Error(`Unsafe paperBuild: ${paperBuild}`);
if (faweVersion === undefined) throw new Error(`Unsafe faweMavenVersion: ${faweMavenVersion}`);
if (!/^[0-9A-Za-z]+$/u.test(faweModrinthVersionId)) {
  throw new Error(`Unsafe faweModrinthVersionId: ${faweModrinthVersionId}`);
}

const pluginSource = join(repositoryRoot, 'paper-plugin', 'build', 'libs', `dirt-mcp-paper-${version}.jar`);
await requireRegularFile(pluginSource, 'built Paper plugin');

const temporaryDirectory = await mkdtemp(join(tmpdir(), 'dirt-package-'));
try {
  const bundleName = `dirt-mcp-${version}`;
  const bundle = join(temporaryDirectory, bundleName);
  await mkdir(join(bundle, 'node', 'bin'), { recursive: true });
  await mkdir(join(bundle, 'app'), { recursive: true });

  const nodeArchive = await obtainNodeArchive();
  const extractedNode = join(temporaryDirectory, 'node-runtime');
  await mkdir(extractedNode);
  await run('tar', [
    '--extract',
    '--xz',
    '--file',
    nodeArchive,
    '--directory',
    extractedNode,
    '--strip-components=1',
    `${nodeArchiveName.slice(0, -7)}/bin/node`,
    `${nodeArchiveName.slice(0, -7)}/LICENSE`,
  ]);
  await copyFile(join(extractedNode, 'bin', 'node'), join(bundle, 'node', 'bin', 'node'));
  await chmod(join(bundle, 'node', 'bin', 'node'), 0o755);
  await copyFile(join(extractedNode, 'LICENSE'), join(bundle, 'node', 'LICENSE'));
  await run(join(bundle, 'node', 'bin', 'node'), [
    '--eval',
    'if (process.version !== `v${process.argv[1]}`) process.exit(1);',
    nodeVersion,
  ]);

  await run('pnpm', ['--filter', '@dirt-mcp/server', 'deploy', '--prod', join(bundle, 'app')], { cwd: repositoryRoot });
  await normalizeDeployment(join(bundle, 'app'));
  await requireRegularFile(join(bundle, 'app', 'dist', 'index.js'), 'deployed web service');
  await requireRegularFile(
    join(bundle, 'app', 'node_modules', 'better-sqlite3', 'build', 'Release', 'better_sqlite3.node'),
    'deployed better-sqlite3 native addon',
  );

  const pluginDestination = join(bundle, 'paper-plugin.jar');
  await copyFile(pluginSource, pluginDestination);
  await copyFile(join(repositoryRoot, 'LICENSE'), join(bundle, 'LICENSE'));

  const pluginSha256 = await sha256(pluginDestination);
  const installerTemplate = await readFile(join(repositoryRoot, 'packaging', 'install.sh'), 'utf8');
  if (!installerTemplate.includes(`readonly application_directory='${dirname(installedApplicationDirectory)}'`)) {
    throw new Error('packaging/install.sh application directory does not match packaged command shims.');
  }
  assertInstallerFaweMetadata(installerTemplate, {
    modrinthId: faweModrinthVersionId,
    release: faweVersion,
  });
  const installer = renderTemplate(installerTemplate, {
    PAPER_BUILD: paperBuild,
    VERSION: version,
    PAPER_VERSION: paperVersion,
    PLUGIN_SHA256: pluginSha256,
  });
  await writeFile(join(bundle, 'install.sh'), installer, { mode: 0o755 });
  await copyFile(join(repositoryRoot, 'packaging', 'dirt-mcp.service.in'), join(bundle, 'dirt-mcp.service.in'));
  await copyFile(join(repositoryRoot, 'packaging', 'Caddyfile.in'), join(bundle, 'Caddyfile.in'));
  await normalizeBundleModes(bundle);

  await rm(releaseDirectory, { recursive: true, force: true });
  await mkdir(releaseDirectory, { recursive: true });
  const archiveName = `${bundleName}-${target}.tar.gz`;
  const archive = join(releaseDirectory, archiveName);
  const uncompressedArchive = join(temporaryDirectory, `${archiveName.slice(0, -3)}`);
  await run('tar', [
    '--create',
    '--file',
    uncompressedArchive,
    '--directory',
    temporaryDirectory,
    '--sort=name',
    '--mtime=@0',
    '--owner=0',
    '--group=0',
    '--numeric-owner',
    '--format=posix',
    '--pax-option=delete=atime,delete=ctime',
    bundleName,
  ]);
  await run('gzip', ['--no-name', '--best', uncompressedArchive]);
  await copyFile(`${uncompressedArchive}.gz`, archive);
  await chmod(archive, 0o644);

  const archiveSha256 = await sha256(archive);
  const checksum = join(releaseDirectory, `${archiveName}.sha256`);
  await writeFile(checksum, `${archiveSha256}  ${archiveName}\n`);
  await chmod(checksum, 0o644);
  const bootstrap = bootstrapScript({ archiveName, archiveSha256, bundleName, version });
  const bootstrapPath = join(releaseDirectory, 'dirt-install');
  await writeFile(bootstrapPath, bootstrap);
  await chmod(bootstrapPath, 0o755);

  process.stdout.write(`Native release assets written to ${releaseDirectory}\n`);
  for (const entry of (await readdir(releaseDirectory)).toSorted()) process.stdout.write(`  ${entry}\n`);
} finally {
  await rm(temporaryDirectory, { recursive: true, force: true });
}

async function assertBuildPlatform() {
  if (process.platform !== 'linux' || process.arch !== 'x64') {
    throw new Error('Native packages must be built on Ubuntu 24.04 x86-64.');
  }
  const osRelease = parseProperties(await readFile('/etc/os-release', 'utf8'));
  if (unquote(osRelease.ID) !== 'ubuntu' || unquote(osRelease.VERSION_ID) !== '24.04') {
    throw new Error('Native packages must be built on Ubuntu 24.04 x86-64.');
  }
}

async function obtainNodeArchive() {
  await mkdir(cacheDirectory, { recursive: true });
  const destination = join(cacheDirectory, nodeArchiveName);
  try {
    if ((await sha256(destination)) === nodeArchiveSha256) return destination;
  } catch {
    // A missing or unreadable cache entry is downloaded below.
  }
  await rm(destination, { force: true });
  const temporary = `${destination}.${process.pid}.tmp`;
  try {
    await run('curl', [
      '--proto',
      '=https',
      '--tlsv1.2',
      '--fail',
      '--location',
      '--silent',
      '--show-error',
      '--retry',
      '3',
      '--retry-all-errors',
      '--connect-timeout',
      '15',
      '--max-time',
      '180',
      '--output',
      temporary,
      nodeArchiveUrl,
    ]);
    const actual = await sha256(temporary);
    if (actual !== nodeArchiveSha256) throw new Error(`Node archive checksum mismatch: ${actual}`);
    await copyFile(temporary, destination);
    return destination;
  } finally {
    await rm(temporary, { force: true });
  }
}

async function normalizeDeployment(application) {
  const nodeModules = join(application, 'node_modules');
  await Promise.all([
    rm(join(nodeModules, '.modules.yaml'), { force: true }),
    rm(join(nodeModules, '.pnpm-workspace-state-v1.json'), { force: true }),
  ]);
  await normalizeCommandShims(nodeModules, application);
}

async function normalizeBundleModes(path) {
  const metadata = await lstat(path);
  if (metadata.isSymbolicLink()) return;
  if (metadata.isFile()) {
    await chmod(path, (metadata.mode & 0o111) === 0 ? 0o644 : 0o755);
    return;
  }
  if (!metadata.isDirectory()) throw new Error(`Unexpected release bundle entry: ${path}`);
  await chmod(path, 0o755);
  const entries = await readdir(path);
  await Promise.all(entries.map(async (entry) => normalizeBundleModes(join(path, entry))));
}

async function normalizeCommandShims(directory, temporaryApplication) {
  const directories = (await readdir(directory, { withFileTypes: true })).filter((entry) => entry.isDirectory());
  await Promise.all(
    directories.map(async (entry) => {
      const path = join(directory, entry.name);
      if (entry.name !== '.bin') {
        await normalizeCommandShims(path, temporaryApplication);
        return;
      }
      const shims = (await readdir(path, { withFileTypes: true })).filter((shim) => shim.isFile());
      await Promise.all(
        shims.map(async (shim) => {
          const shimPath = join(path, shim.name);
          const contents = await readFile(shimPath, 'utf8');
          if (contents.includes(temporaryApplication)) {
            await writeFile(shimPath, contents.replaceAll(temporaryApplication, installedApplicationDirectory));
          }
        }),
      );
    }),
  );
}

function parseProperties(contents) {
  return Object.fromEntries(
    contents
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .filter((line) => line.length > 0 && !line.startsWith('#'))
      .map((line) => {
        const separator = line.indexOf('=');
        return separator === -1 ? [line, ''] : [line.slice(0, separator), line.slice(separator + 1)];
      }),
  );
}

function requiredProperty(propertyValues, name) {
  const value = propertyValues[name];
  if (value === undefined || value.length === 0) throw new Error(`${name} is required in gradle.properties.`);
  return value;
}

function requiredString(value, description) {
  if (typeof value !== 'string' || value.length === 0) throw new Error(`${description} is required.`);
  return value;
}

function nodeArchiveChecksum(runtimeVersion) {
  const checksums = {
    '24.20.0': '2f2c0da162318f0de47665410c7c8c2ed3d36c8f3105de4bbc61176c70a7cbf2',
  };
  const checksum = checksums[runtimeVersion];
  if (checksum === undefined)
    throw new Error(`No verified Linux x64 archive checksum is pinned for Node.js ${runtimeVersion}.`);
  return checksum;
}

function assertInstallerFaweMetadata(installer, { modrinthId, release }) {
  const filename = `FastAsyncWorldEdit-Paper-${release}.jar`;
  const expected = [
    `readonly fawe_version='${release}'`,
    `readonly fawe_filename='${filename}'`,
    `readonly fawe_url='https://cdn.modrinth.com/data/z4HZZnLr/versions/${modrinthId}/${filename}'`,
  ];
  const missing = expected.find((metadata) => !installer.includes(metadata));
  if (missing !== undefined) {
    throw new Error('packaging/install.sh FAWE metadata does not match gradle.properties.');
  }
}

function unquote(value) {
  if (value === undefined) return undefined;
  return value.replace(/^(?:"(.*)"|'(.*)')$/u, '$1$2');
}

function renderTemplate(template, replacements) {
  let rendered = template;
  for (const [name, value] of Object.entries(replacements)) rendered = rendered.replaceAll(`@${name}@`, value);
  const runtimePlaceholders = new Set(['@HOSTNAME@', '@PAPER_DIR@']);
  const unresolved = [...rendered.matchAll(/@[A-Z0-9_]+@/gu)].map((match) => match[0]);
  const invalid = unresolved.find((placeholder) => !runtimePlaceholders.has(placeholder));
  if (invalid !== undefined) throw new Error(`Unresolved installer placeholder: ${invalid}`);
  return rendered;
}

function bootstrapScript({ archiveName, archiveSha256, bundleName, version: releaseVersion }) {
  return `#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly version='${releaseVersion}'
readonly archive_name='${archiveName}'
readonly archive_sha256='${archiveSha256}'
readonly archive_url='https://github.com/kkd16/dirt-mcp/releases/download/v${releaseVersion}/${archiveName}'

temporary_directory="$(mktemp -d)"
cleanup() {
  rm -rf -- "\${temporary_directory}"
}
trap cleanup EXIT

printf 'Downloading Dirt MCP %s...\n' "\${version}"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \\
  --retry 3 --retry-all-errors --connect-timeout 15 --max-time 300 \\
  --output "\${temporary_directory}/\${archive_name}" "\${archive_url}"
printf '%s  %s\\n' "\${archive_sha256}" "\${temporary_directory}/\${archive_name}" | sha256sum --check --status
tar --extract --gzip --file "\${temporary_directory}/\${archive_name}" --directory "\${temporary_directory}"
"\${temporary_directory}/${bundleName}/install.sh" "$@"
`;
}

async function sha256(path) {
  await stat(path);
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
  if (!metadata.isFile()) throw new Error(`Expected ${description} to be a regular file: ${path}`);
}

async function run(command, arguments_, options = {}) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, arguments_, {
      cwd: options.cwd,
      env: process.env,
      stdio: 'inherit',
    });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} failed with ${signal === null ? `exit code ${code}` : `signal ${signal}`}.`));
    });
  });
}

async function capture(command, arguments_, options = {}) {
  return await new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    const child = spawn(command, arguments_, {
      cwd: options.cwd,
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
