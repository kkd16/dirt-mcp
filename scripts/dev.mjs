#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { chmod, lstat, mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { createConnection } from 'node:net';
import { dirname, join } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { setTimeout as delay } from 'node:timers/promises';

const repositoryRoot = dirname(import.meta.dirname);
const developmentDirectory = join(repositoryRoot, '.dev');
const paperDirectory = join(developmentDirectory, 'paper');
const paperLog = join(paperDirectory, 'logs', 'latest.log');
const webSecretsDirectory = join(developmentDirectory, 'secrets');
const paperSecretsDirectory = join(paperDirectory, 'plugins', 'DirtMCP', 'secrets');
const overmindSocket = join(developmentDirectory, 'overmind.sock');
const secretFiles = {
  auth: join(webSecretsDirectory, 'auth-secret'),
  bridge: join(paperSecretsDirectory, 'bridge-token'),
  control: join(paperSecretsDirectory, 'control-token'),
};

const managedProcesses = ['paper', 'web'];
const ports = { minecraft: 25_565, bridge: 8_765, web: 3_000 };
const startupTimeoutMilliseconds = 120_000;
const shutdownTimeoutMilliseconds = 315_000;
const pollIntervalMilliseconds = 250;
const tokenPattern = /^[0-9a-f]{64}$/u;
const restartMessage = 'kick @a Server restarting for a Dirt MCP update. Please reconnect in a moment.';

function usage() {
  process.stderr.write(
    'Usage: node scripts/dev.mjs {up|restart|restart-paper|restart-web|down|status|health|logs|console|command}\n',
  );
  process.exitCode = 2;
}

async function run(executable, args, options = {}) {
  const { capture = false, check = true, input, interactive = false } = options;
  const child = spawn(executable, args, {
    cwd: repositoryRoot,
    env: { ...process.env, PWD: repositoryRoot },
    stdio: [
      interactive ? 'inherit' : input === undefined ? 'ignore' : 'pipe',
      capture ? 'pipe' : 'inherit',
      capture ? 'pipe' : 'inherit',
    ],
  });

  let stdout = '';
  let stderr = '';
  if (capture) {
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
  }
  if (input !== undefined) {
    child.stdin.on('error', () => {
      // The command's exit status below remains the canonical failure signal if it closes stdin early.
    });
    child.stdin.end(input);
  }

  const code = await new Promise((resolve, reject) => {
    child.once('error', (error) => reject(new Error(`${executable} could not be started: ${error.message}`)));
    child.once('close', resolve);
  });
  if (check && code !== 0) {
    const detail = capture && stderr.trim().length > 0 ? `: ${stderr.trim()}` : '';
    throw new Error(`${executable} exited with status ${String(code)}${detail}`);
  }
  return { code, stdout, stderr };
}

async function socketMetadata() {
  try {
    return await lstat(overmindSocket);
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

function parseStatus(output) {
  const statuses = new Map();
  for (const line of output.split('\n')) {
    const match = /^(paper|web)\s+(\d+)\s+(running|dead)\s*$/u.exec(line.trim());
    if (match !== null) statuses.set(match[1], { pid: Number(match[2]), status: match[3] });
  }
  return managedProcesses.every((name) => statuses.has(name)) ? statuses : null;
}

async function managedStatus({ retries = 3 } = {}) {
  if ((await socketMetadata()) === null) return null;
  for (let attempt = 0; attempt < retries; attempt += 1) {
    // Overmind creates its socket immediately before its command center begins accepting clients.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const result = await run('overmind', ['status'], { capture: true, check: false });
    if (result.code === 0) {
      const processStatus = parseStatus(result.stdout);
      if (processStatus !== null) return processStatus;
    }
    if (attempt + 1 < retries) {
      // oxlint-disable-next-line eslint/no-await-in-loop
      await delay(100);
    }
  }
  return null;
}

async function removeStaleSocket() {
  const metadata = await socketMetadata();
  if (metadata === null) return;
  if (!metadata.isSocket()) {
    throw new Error(`${overmindSocket} exists but is not an Overmind socket; remove it manually.`);
  }
  if ((await managedStatus({ retries: 5 })) !== null) return;
  await rm(overmindSocket);
  process.stdout.write('Removed a stale Overmind socket.\n');
}

function stripSingleLineEnding(value) {
  return value.endsWith('\r\n') ? value.slice(0, -2) : value.endsWith('\n') ? value.slice(0, -1) : value;
}

async function readCredentials() {
  const entries = await Promise.all(
    Object.entries(secretFiles).map(async ([name, path]) => {
      const value = stripSingleLineEnding(await readFile(path, 'utf8'));
      if (!tokenPattern.test(value)) throw new Error(`The managed ${name} credential is invalid.`);
      return [name, value];
    }),
  );
  const credentials = Object.fromEntries(entries);
  if (new Set(Object.values(credentials)).size !== entries.length) {
    throw new Error('Managed development credentials must be pairwise distinct.');
  }
  return credentials;
}

async function ensureCredentials() {
  const secretDirectories = [webSecretsDirectory, paperSecretsDirectory];
  await Promise.all(secretDirectories.map((path) => mkdir(path, { recursive: true, mode: 0o700 })));
  await Promise.all(secretDirectories.map((path) => chmod(path, 0o700)));
  try {
    const credentials = await readCredentials();
    await Promise.all(Object.values(secretFiles).map((path) => chmod(path, 0o600)));
    return credentials;
  } catch {
    const generated = Object.fromEntries(
      Object.keys(secretFiles).map((name) => [name, randomBytes(32).toString('hex')]),
    );
    const temporaryFiles = [];
    try {
      for (const [name, path] of Object.entries(secretFiles)) {
        const temporary = `${path}.${process.pid}.${randomUUID()}.tmp`;
        temporaryFiles.push(temporary);
        // Credential rotation is only called while both managed services are stopped.
        // oxlint-disable-next-line eslint/no-await-in-loop
        await writeFile(temporary, generated[name], { encoding: 'utf8', flag: 'wx', mode: 0o600 });
      }
      for (const [index, path] of Object.values(secretFiles).entries()) {
        // Each rename is atomic; rotating the set while stopped prevents mixed credentials in a live stack.
        // oxlint-disable-next-line eslint/no-await-in-loop
        await rename(temporaryFiles[index], path);
      }
    } finally {
      await Promise.all(temporaryFiles.map((path) => rm(path, { force: true })));
    }
    process.stdout.write('Created fresh managed development credentials.\n');
    return generated;
  }
}

async function portIsOpen(port) {
  return new Promise((resolve) => {
    const socket = createConnection({ host: '127.0.0.1', port });
    let settled = false;
    const finish = (open) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(open);
    };
    socket.setTimeout(500, () => finish(false));
    socket.once('connect', () => finish(true));
    socket.once('error', () => finish(false));
  });
}

async function assertPortsAvailable() {
  const entries = Object.entries(ports);
  const results = await Promise.all(entries.map(async ([name, port]) => [name, port, await portIsOpen(port)]));
  const occupied = results.filter(([, , open]) => open).map(([name, port]) => `${name} (${String(port)})`);
  if (occupied.length > 0) {
    throw new Error(`Required loopback ports are already in use: ${occupied.join(', ')}.`);
  }
}

async function waitForPortsToClose() {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    // Port closure checks are deliberately serial across time.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const open = await Promise.all(Object.values(ports).map(portIsOpen));
    if (open.every((value) => !value)) return true;
    // oxlint-disable-next-line eslint/no-await-in-loop
    await delay(pollIntervalMilliseconds);
  }
  return false;
}

async function prepareFreshStack() {
  process.stdout.write('Preparing the managed development stack...\n');
  await run('pnpm', ['install', '--frozen-lockfile']);
  await run('./gradlew', [':paper-plugin:assemble']);
  await run('pnpm', ['run', 'build']);
  await migrate();
}

async function migrate() {
  await run('overmind', ['run', 'pnpm', 'migrate']);
}

async function probeJson(url, options, accepts) {
  try {
    const response = await fetch(url, { ...options, signal: AbortSignal.timeout(2_000) });
    if (response.status !== 200) return false;
    return accepts(await response.json());
  } catch {
    return false;
  }
}

function callHeaders() {
  return { 'X-Dirt-Call-Id': randomUUID() };
}

async function healthChecks() {
  let credentials;
  try {
    credentials = await readCredentials();
  } catch {
    return { web: false, control: false, paper: false };
  }
  const [web, control, paper, minecraft] = await Promise.all([
    probeJson(`http://127.0.0.1:${String(ports.web)}/healthz`, {}, (body) => body?.status === 'ok'),
    probeJson(
      `http://127.0.0.1:${String(ports.web)}/internal/v1/access/users?page=1`,
      { headers: { ...callHeaders(), Authorization: `Bearer ${credentials.control}` } },
      (body) => Array.isArray(body?.items),
    ),
    probeJson(
      `http://127.0.0.1:${String(ports.bridge)}/v1/ping`,
      { headers: { ...callHeaders(), Authorization: `Bearer ${credentials.bridge}` } },
      (body) => body?.status === 'ok',
    ),
    portIsOpen(ports.minecraft),
  ]);
  return { web, control, paper: paper && minecraft };
}

function allHealthy(checks) {
  return checks.web && checks.control && checks.paper;
}

function allRunning(statuses) {
  return statuses !== null && managedProcesses.every((name) => statuses.get(name)?.status === 'running');
}

async function waitForReady({ paperOnly = false } = {}) {
  const deadline = Date.now() + startupTimeoutMilliseconds;
  while (Date.now() < deadline) {
    // Startup state and health are intentionally observed in order on each bounded retry.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const statuses = await managedStatus();
    if (statuses !== null) {
      if (statuses.get('paper')?.status === 'dead' || (!paperOnly && statuses.get('web')?.status === 'dead')) {
        throw new Error('A managed process exited before the stack became ready. Run `make logs` for details.');
      }
      // oxlint-disable-next-line eslint/no-await-in-loop
      const checks = await healthChecks();
      if (
        paperOnly
          ? statuses.get('paper')?.status === 'running' && checks.paper
          : allRunning(statuses) && allHealthy(checks)
      ) {
        return;
      }
    }
    // oxlint-disable-next-line eslint/no-await-in-loop
    await delay(pollIntervalMilliseconds);
  }
  throw new Error(
    `The managed stack did not become ready within ${String(startupTimeoutMilliseconds / 1_000)} seconds.`,
  );
}

async function validatePaperLog(mode, checkpoint) {
  const args = [join(repositoryRoot, 'scripts', 'validate-paper-log.sh'), mode];
  if (mode === 'shutdown') args.push(String(await shutdownLogStart(checkpoint)));
  const result = await run(args[0], args.slice(1), { capture: true, check: false });
  if (result.code !== 0) {
    return result.stderr.trim() || result.stdout.trim() || `Paper ${mode} log validation failed.`;
  }
  if (result.stdout.length > 0) process.stdout.write(result.stdout);
  return null;
}

async function paperLogPosition() {
  try {
    const [metadata, content] = await Promise.all([stat(paperLog), readFile(paperLog, 'utf8')]);
    return {
      identity: `${String(metadata.dev)}:${String(metadata.ino)}`,
      lines: content.split('\n').length - 1,
    };
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

async function shutdownLogStart(checkpoint) {
  if (checkpoint === null) return 0;
  const current = await paperLogPosition();
  return current !== null && current.identity === checkpoint.identity && current.lines >= checkpoint.lines
    ? checkpoint.lines
    : 0;
}

async function waitForProcess(name, desiredStatus, timeoutMilliseconds) {
  const deadline = Date.now() + timeoutMilliseconds;
  while (Date.now() < deadline) {
    // Process state polling is deliberately serial across time.
    // oxlint-disable-next-line eslint/no-await-in-loop
    const statuses = await managedStatus();
    if (statuses === null) return desiredStatus === 'dead';
    if (statuses.get(name)?.status === desiredStatus) return true;
    // oxlint-disable-next-line eslint/no-await-in-loop
    await delay(pollIntervalMilliseconds);
  }
  return false;
}

async function stopProcess(name, timeoutMilliseconds) {
  const statuses = await managedStatus();
  if (statuses === null || statuses.get(name)?.status === 'dead') return false;
  await run('overmind', ['stop', name]);
  if (await waitForProcess(name, 'dead', timeoutMilliseconds)) return false;
  await run('overmind', ['stop', name]);
  if (!(await waitForProcess(name, 'dead', 10_000))) {
    throw new Error(`${name} did not stop after Overmind escalated to SIGKILL.`);
  }
  return true;
}

async function restartProcess(name) {
  await run('overmind', ['restart', name]);
  if (!(await waitForProcess(name, 'running', 10_000))) {
    throw new Error(`${name} did not start after Overmind restarted it.`);
  }
}

async function waitForSupervisorExit() {
  const deadline = Date.now() + shutdownTimeoutMilliseconds + 10_000;
  let unreachableChecks = 0;
  while (Date.now() < deadline) {
    // Supervisor shutdown polling is deliberately serial across time.
    // oxlint-disable-next-line eslint/no-await-in-loop
    if ((await socketMetadata()) === null) return true;
    // oxlint-disable-next-line eslint/no-await-in-loop
    unreachableChecks = (await managedStatus({ retries: 1 })) === null ? unreachableChecks + 1 : 0;
    if (unreachableChecks >= 8) return true;
    // oxlint-disable-next-line eslint/no-await-in-loop
    await delay(pollIntervalMilliseconds);
  }
  return false;
}

async function stopStack() {
  const issues = [];
  const statuses = await managedStatus({ retries: 5 });
  if (statuses === null) {
    await removeStaleSocket();
    return issues;
  }

  process.stdout.write('Stopping the managed development stack...\n');
  const paperWasRunning = statuses.get('paper')?.status === 'running';
  const checkpoint = paperWasRunning ? await paperLogPosition() : null;

  if (statuses.get('web')?.status === 'running') {
    try {
      if (await stopProcess('web', shutdownTimeoutMilliseconds)) issues.push('web required SIGKILL to stop');
    } catch (error) {
      issues.push(error.message);
    }
  }

  const quit = await run('overmind', ['quit'], { capture: true, check: false });
  if (quit.code !== 0) issues.push(quit.stderr.trim() || 'Overmind could not begin shutdown');
  if (!(await waitForSupervisorExit())) {
    issues.push('Overmind exceeded its graceful shutdown deadline');
    await run('overmind', ['kill'], { capture: true, check: false });
    await waitForSupervisorExit();
  }
  await removeStaleSocket();

  if (paperWasRunning) {
    const validationFailure = await validatePaperLog('shutdown', checkpoint);
    if (validationFailure !== null) issues.push(validationFailure);
  } else if (statuses.get('paper')?.status === 'dead') {
    const validationFailure = await validatePaperLog('running');
    if (validationFailure !== null) issues.push(validationFailure);
  }
  if (!(await waitForPortsToClose())) issues.push('one or more managed loopback ports remained open after shutdown');

  process.stdout.write('Managed Paper and web/MCP services are stopped.\n');
  return issues;
}

async function startFresh() {
  await mkdir(developmentDirectory, { recursive: true, mode: 0o700 });
  await chmod(developmentDirectory, 0o700);
  await removeStaleSocket();
  await assertPortsAvailable();
  await ensureCredentials();
  await prepareFreshStack();
  await assertPortsAvailable();
  await run('overmind', ['start']);
  await waitForReady();
  const validationFailure = await validatePaperLog('running');
  if (validationFailure !== null) throw new Error(validationFailure);
}

function reportReady() {
  process.stdout.write(
    `Dirt MCP is ready: dashboard/MCP http://localhost:${String(ports.web)}, Minecraft port ${String(ports.minecraft)}, bridge 127.0.0.1:${String(ports.bridge)}.\n`,
  );
}

function throwIssues(issues) {
  if (issues.length > 0) throw new Error(`Lifecycle checks failed:\n- ${issues.join('\n- ')}`);
}

async function up() {
  const statuses = await managedStatus({ retries: 5 });
  if (allRunning(statuses) && allHealthy(await healthChecks())) {
    const validationFailure = await validatePaperLog('running');
    if (validationFailure !== null) throw new Error(validationFailure);
    process.stdout.write(
      `Dirt MCP is already ready on Minecraft port ${String(ports.minecraft)} with dashboard and MCP on port ${String(ports.web)}.\n`,
    );
    return;
  }

  const issues = statuses === null ? [] : await stopStack();
  await startFresh();
  throwIssues(issues);
  reportReady();
}

async function restart() {
  const statuses = await managedStatus({ retries: 5 });
  if (statuses === null) {
    await startFresh();
    reportReady();
    return;
  }
  if (statuses.get('paper')?.status === 'running') await sendPaperCommand(restartMessage);
  const issues = await stopStack();
  await startFresh();
  throwIssues(issues);
  reportReady();
}

async function restartWeb() {
  const statuses = await managedStatus({ retries: 5 });
  if (statuses === null || statuses.get('paper')?.status !== 'running') {
    const issues = statuses === null ? [] : await stopStack();
    await startFresh();
    throwIssues(issues);
    reportReady();
    return;
  }

  const issues = [];
  if (await stopProcess('web', shutdownTimeoutMilliseconds)) issues.push('web required SIGKILL to stop');
  await run('pnpm', ['install', '--frozen-lockfile']);
  await run('pnpm', ['run', 'build']);
  await migrate();
  await restartProcess('web');
  await waitForReady();
  throwIssues(issues);
  process.stdout.write('The web/MCP service restarted; Paper stayed online.\n');
}

async function restartPaper() {
  const statuses = await managedStatus({ retries: 5 });
  if (statuses === null || statuses.get('paper')?.status !== 'running') {
    const issues = statuses === null ? [] : await stopStack();
    await startFresh();
    throwIssues(issues);
    reportReady();
    return;
  }

  const issues = [];
  await sendPaperCommand(restartMessage);
  if (statuses.get('web')?.status === 'running' && (await stopProcess('web', shutdownTimeoutMilliseconds))) {
    issues.push('web required SIGKILL to stop');
  }
  const checkpoint = await paperLogPosition();
  if (await stopProcess('paper', shutdownTimeoutMilliseconds)) issues.push('Paper required SIGKILL to stop');
  const shutdownFailure = await validatePaperLog('shutdown', checkpoint);
  if (shutdownFailure !== null) issues.push(shutdownFailure);

  await run('./gradlew', [':paper-plugin:assemble']);
  await ensureCredentials();
  await restartProcess('paper');
  await waitForReady({ paperOnly: true });
  const startupFailure = await validatePaperLog('running');
  if (startupFailure !== null) {
    issues.push(startupFailure);
    throwIssues(issues);
  }
  await restartProcess('web');
  await waitForReady();
  throwIssues(issues);
  process.stdout.write('Paper restarted safely; the web/MCP service was drained and restored.\n');
}

async function status() {
  const statuses = await managedStatus({ retries: 5 });
  if (statuses === null) {
    process.stdout.write('Dirt MCP is stopped.\n');
    process.exitCode = 1;
    return;
  }
  for (const name of managedProcesses) {
    const processStatus = statuses.get(name);
    process.stdout.write(`${name}: ${processStatus.status} (pid ${String(processStatus.pid)})\n`);
  }
  const checks = await healthChecks();
  process.stdout.write(`readiness: ${allRunning(statuses) && allHealthy(checks) ? 'ready' : 'unhealthy'}\n`);
  if (!allRunning(statuses) || !allHealthy(checks)) process.exitCode = 1;
}

async function health() {
  const statuses = await managedStatus({ retries: 5 });
  const checks = await healthChecks();
  process.stdout.write(`web: ${checks.web ? 'ok' : 'failed'}\n`);
  process.stdout.write(`control: ${checks.control ? 'ok' : 'failed'}\n`);
  process.stdout.write(`paper/FAWE: ${checks.paper ? 'ok' : 'failed'}\n`);
  if (!allRunning(statuses) || !allHealthy(checks)) process.exitCode = 1;
}

async function down() {
  const hadSupervisor = (await managedStatus({ retries: 5 })) !== null;
  const issues = await stopStack();
  if (!hadSupervisor) process.stdout.write('Dirt MCP is stopped.\n');
  throwIssues(issues);
}

async function overmindConnection(processName) {
  return new Promise((resolve, reject) => {
    const client = createConnection(overmindSocket);
    let response = '';
    const fail = (error) => {
      client.destroy();
      reject(new Error(`Could not resolve the managed ${processName} console: ${error.message}`));
    };
    client.setEncoding('utf8');
    client.setTimeout(2_000, () => fail(new Error('Overmind did not respond')));
    client.once('error', fail);
    client.on('data', (chunk) => {
      response += chunk;
      const newline = response.indexOf('\n');
      if (newline === -1) return;
      client.end();
      const parts = response.slice(0, newline).trim().split(' ');
      if (parts.length < 2) {
        reject(new Error(`Overmind does not have a ${processName} process.`));
        return;
      }
      resolve({ socket: parts[0], target: parts[1] });
    });
    client.once('connect', () => client.write(`get-connection ${processName}\n`));
  });
}

async function logs() {
  const statuses = await managedStatus({ retries: 5 });
  if (statuses !== null) {
    for (const name of managedProcesses) {
      // The two captures stay ordered so their labels cannot interleave.
      // oxlint-disable-next-line eslint/no-await-in-loop
      const connection = await overmindConnection(name);
      // oxlint-disable-next-line eslint/no-await-in-loop
      const result = await run(
        'tmux',
        ['-L', connection.socket, 'capture-pane', '-p', '-J', '-t', connection.target, '-S', '-100'],
        { capture: true },
      );
      process.stdout.write(`==> ${name} <==\n${result.stdout.trimEnd()}\n`);
    }
    return;
  }
  try {
    const content = await readFile(paperLog, 'utf8');
    process.stdout.write(`==> paper <==\n${content.trimEnd().split('\n').slice(-100).join('\n')}\n`);
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
    process.stdout.write('No managed process output is available.\n');
  }
}

async function consoleAttach() {
  const statuses = await managedStatus({ retries: 5 });
  if (statuses?.get('paper')?.status !== 'running') throw new Error('Managed Paper is not running.');
  await run('overmind', ['connect', 'paper'], { interactive: true });
}

async function readPaperCommand() {
  let commandText;
  if (process.stdin.isTTY) {
    const readline = createInterface({ input: process.stdin, output: process.stdout });
    try {
      commandText = await readline.question('Paper command: ');
    } finally {
      readline.close();
    }
  } else {
    commandText = '';
    process.stdin.setEncoding('utf8');
    for await (const chunk of process.stdin) commandText += chunk;
    commandText = stripSingleLineEnding(commandText);
  }
  if (commandText.length === 0) throw new Error('Paper command must not be empty.');
  if (commandText.includes('\n') || commandText.includes('\r')) {
    throw new Error('Paper command must be exactly one line.');
  }
  return commandText;
}

async function sendPaperCommand(commandText) {
  const statuses = await managedStatus({ retries: 5 });
  if (statuses?.get('paper')?.status !== 'running') throw new Error('Managed Paper is not running.');
  const connection = await overmindConnection('paper');
  const buffer = `dirt-command-${randomUUID()}`;
  try {
    const loaded = await run('tmux', ['-L', connection.socket, 'load-buffer', '-b', buffer, '-'], {
      capture: true,
      check: false,
      input: commandText,
    });
    if (loaded.code !== 0) throw new Error('Paper command could not be queued.');
    const pasted = await run('tmux', ['-L', connection.socket, 'paste-buffer', '-b', buffer, '-t', connection.target], {
      capture: true,
      check: false,
    });
    if (pasted.code !== 0) throw new Error('Paper command could not be queued.');
  } finally {
    await run('tmux', ['-L', connection.socket, 'delete-buffer', '-b', buffer], {
      capture: true,
      check: false,
    });
  }
  const submitted = await run('tmux', ['-L', connection.socket, 'send-keys', '-t', connection.target, 'Enter'], {
    capture: true,
    check: false,
  });
  if (submitted.code !== 0) throw new Error('Paper command could not be queued.');
}

async function queueCommand() {
  const commandText = await readPaperCommand();
  await sendPaperCommand(commandText);
  process.stdout.write('Paper command queued.\n');
}

const requestedCommand = process.argv[2];
if (process.argv.length !== 3) {
  usage();
} else {
  try {
    switch (requestedCommand) {
      case 'up':
        await up();
        break;
      case 'restart':
        await restart();
        break;
      case 'restart-paper':
        await restartPaper();
        break;
      case 'restart-web':
        await restartWeb();
        break;
      case 'down':
        await down();
        break;
      case 'status':
        await status();
        break;
      case 'health':
        await health();
        break;
      case 'logs':
        await logs();
        break;
      case 'console':
        await consoleAttach();
        break;
      case 'command':
        await queueCommand();
        break;
      default:
        usage();
    }
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
