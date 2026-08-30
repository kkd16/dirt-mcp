import type Database from 'better-sqlite3';
import { createHash, randomBytes, randomUUID } from 'node:crypto';

const PAGE_SIZE = 20;
const INVITATION_TTL_MS = 24 * 60 * 60 * 1_000;
const RECOVERY_TTL_MS = 15 * 60 * 1_000;
const LINK_TTL_MS = 10 * 60 * 1_000;

export type UserStatus = 'active' | 'disabled';

export interface UserSummary {
  readonly id: string;
  readonly handle: string;
  readonly status: UserStatus;
  readonly minecraftAccount: { readonly uuid: string; readonly name: string } | null;
  readonly createdAt: string;
}

export interface InvitationSummary {
  readonly id: string;
  readonly status: 'pending' | 'accepted' | 'revoked' | 'expired';
  readonly createdAt: string;
  readonly expiresAt: string;
}

export interface Page<T> {
  readonly page: number;
  readonly pageSize: number;
  readonly totalItems: number;
  readonly totalPages: number;
  readonly items: readonly T[];
}

export interface SecretInvitation {
  readonly invitation: InvitationSummary;
  readonly secret: string;
}

export interface SecretRecovery {
  readonly user: UserSummary;
  readonly secret: string;
  readonly expiresAt: string;
}

export interface LinkChallenge {
  readonly code: string;
  readonly expiresAt: string;
}

export interface OnboardingClaim {
  readonly kind: 'invitation' | 'recovery';
  readonly recordId: string;
  readonly handle: string;
}

export interface McpUser {
  readonly id: string;
  readonly minecraftUuid: string;
}

type UserRow = {
  id: string;
  handle: string;
  status: string;
  minecraftUuid: string | null;
  minecraftName: string | null;
  createdAt: number | string;
};

type InvitationRow = {
  id: string;
  createdAt: number | string;
  expiresAt: number | string;
  acceptedAt: number | string | null;
  revokedAt: number | string | null;
};

type LinkChallengeRow = {
  id: string;
  minecraftUuid: string;
  minecraftName: string;
  expiresAt: number | string;
  usedAt: number | string | null;
};

type InvitationClaimRow = Pick<InvitationRow, 'id' | 'expiresAt' | 'acceptedAt' | 'revokedAt'>;

type RecoveryClaimRow = {
  id: string;
  expiresAt: number | string;
  usedAt: number | string | null;
  handle: string;
};

export class AccessError extends Error {
  readonly code: 'conflict' | 'expired' | 'invalid' | 'not_found' | 'recent_authentication_required';

  constructor(
    code: 'conflict' | 'expired' | 'invalid' | 'not_found' | 'recent_authentication_required',
    message: string,
  ) {
    super(message);
    this.code = code;
    this.name = 'AccessError';
  }
}

export class AccessRepository {
  private readonly database: Database.Database;

  constructor(database: Database.Database) {
    this.database = database;
  }

  assertSchema(): void {
    const required = [
      'account',
      'credentialRecovery',
      'invitation',
      'jwks',
      'minecraftLinkChallenge',
      'oauthAccessToken',
      'oauthClient',
      'oauthClientAssertion',
      'oauthClientResource',
      'oauthConsent',
      'oauthRefreshToken',
      'oauthResource',
      'passkey',
      'session',
      'user',
      'verification',
    ];
    const rows = this.database
      .prepare<string[], { name: string }>(
        `SELECT name FROM sqlite_schema WHERE type = 'table' AND name IN (${required.map(() => '?').join(', ')})`,
      )
      .all(...required);
    if (rows.length !== required.length) {
      throw new Error('The Dirt database is not migrated; run `pnpm --filter @dirt-mcp/server migrate`.');
    }
  }

  listUsers(page: number): Page<UserSummary> {
    const normalizedPage = normalizePage(page);
    const totalItems = scalarCount(this.database, 'SELECT COUNT(*) AS count FROM "user"');
    const rows = this.database
      .prepare<[number, number], UserRow>(
        'SELECT id, handle, status, minecraftUuid, minecraftName, createdAt FROM "user" ORDER BY createdAt DESC, id DESC LIMIT ? OFFSET ?',
      )
      .all(PAGE_SIZE, (normalizedPage - 1) * PAGE_SIZE);
    return pageEnvelope(normalizedPage, totalItems, rows.map(toUserSummary));
  }

  listInvitations(page: number, now = new Date()): Page<InvitationSummary> {
    const normalizedPage = normalizePage(page);
    const totalItems = scalarCount(this.database, 'SELECT COUNT(*) AS count FROM invitation');
    const rows = this.database
      .prepare<[number, number], InvitationRow>(
        'SELECT id, createdAt, expiresAt, acceptedAt, revokedAt FROM invitation ORDER BY createdAt DESC, id DESC LIMIT ? OFFSET ?',
      )
      .all(PAGE_SIZE, (normalizedPage - 1) * PAGE_SIZE);
    return pageEnvelope(
      normalizedPage,
      totalItems,
      rows.map((row) => toInvitationSummary(row, now)),
    );
  }

  createInvitation(now = new Date()): SecretInvitation {
    const id = randomUUID();
    const secret = randomSecret();
    const expiresAt = new Date(now.getTime() + INVITATION_TTL_MS);
    this.database
      .prepare(
        'INSERT INTO invitation (id, tokenHash, createdAt, expiresAt, acceptedAt, revokedAt, acceptedByUserId) VALUES (?, ?, ?, ?, NULL, NULL, NULL)',
      )
      .run(id, hashSecret(secret), now.getTime(), expiresAt.getTime());
    return {
      invitation: {
        id,
        status: 'pending',
        createdAt: now.toISOString(),
        expiresAt: expiresAt.toISOString(),
      },
      secret,
    };
  }

  revokeInvitation(id: string, now = new Date()): InvitationSummary {
    const changed = this.database
      .prepare('UPDATE invitation SET revokedAt = ? WHERE id = ? AND revokedAt IS NULL AND acceptedAt IS NULL')
      .run(now.getTime(), id);
    if (changed.changes !== 1) {
      const existing = this.getInvitation(id, now);
      if (existing === null) throw new AccessError('not_found', 'Invitation not found.');
      if (existing.status !== 'revoked') throw new AccessError('conflict', 'Invitation can no longer be revoked.');
      return existing;
    }
    const invitation = this.getInvitation(id, now);
    if (invitation === null) throw new AccessError('not_found', 'Invitation not found.');
    return invitation;
  }

  disableUser(handle: string): UserSummary {
    return this.setUserStatus(handle, 'disabled');
  }

  enableUser(handle: string): UserSummary {
    return this.setUserStatus(handle, 'active');
  }

  unlinkUser(handle: string): UserSummary {
    const changed = this.database
      .prepare('UPDATE "user" SET minecraftUuid = NULL, minecraftName = NULL, updatedAt = ? WHERE handle = ?')
      .run(Date.now(), handle);
    if (changed.changes !== 1) throw new AccessError('not_found', 'User not found.');
    return this.requireUserByHandle(handle);
  }

  createRecovery(handle: string, now = new Date()): SecretRecovery {
    const user = this.requireUserByHandle(handle);
    const secret = randomSecret();
    const expiresAt = new Date(now.getTime() + RECOVERY_TTL_MS);
    const transaction = this.database.transaction(() => {
      this.database
        .prepare('UPDATE credentialRecovery SET usedAt = ? WHERE userId = ? AND usedAt IS NULL')
        .run(now.getTime(), user.id);
      this.database
        .prepare(
          'INSERT INTO credentialRecovery (id, tokenHash, userId, createdAt, expiresAt, usedAt) VALUES (?, ?, ?, ?, ?, NULL)',
        )
        .run(randomUUID(), hashSecret(secret), user.id, now.getTime(), expiresAt.getTime());
    });
    transaction();
    return { user, secret, expiresAt: expiresAt.toISOString() };
  }

  createMinecraftLinkChallenge(minecraftUuid: string, minecraftName: string, now = new Date()): LinkChallenge {
    const code = humanCode();
    const expiresAt = new Date(now.getTime() + LINK_TTL_MS);
    const transaction = this.database.transaction(() => {
      this.database.prepare('DELETE FROM minecraftLinkChallenge WHERE expiresAt <= ?').run(now.getTime());
      const pending = this.database
        .prepare<[string], { found: number }>(
          'SELECT 1 AS found FROM minecraftLinkChallenge WHERE minecraftUuid = ? LIMIT 1',
        )
        .get(minecraftUuid);
      if (pending !== undefined) {
        throw new AccessError(
          'conflict',
          'A link code was recently issued for this player. Try again after it expires.',
        );
      }
      this.database
        .prepare(
          'INSERT INTO minecraftLinkChallenge (id, codeHash, minecraftUuid, minecraftName, createdAt, expiresAt, usedAt, usedByUserId) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)',
        )
        .run(randomUUID(), hashSecret(code), minecraftUuid, minecraftName, now.getTime(), expiresAt.getTime());
    });
    transaction.immediate();
    return { code, expiresAt: expiresAt.toISOString() };
  }

  consumeMinecraftLinkChallenge(userId: string, code: string, now = new Date()): UserSummary {
    const transaction = this.database.transaction(() => {
      const challenge = this.database
        .prepare<[string], LinkChallengeRow>(
          'SELECT id, minecraftUuid, minecraftName, expiresAt, usedAt FROM minecraftLinkChallenge WHERE codeHash = ?',
        )
        .get(hashSecret(normalizeCode(code)));
      if (challenge === undefined || challenge.usedAt !== null) throw new AccessError('invalid', 'Invalid link code.');
      if (toEpochMilliseconds(challenge.expiresAt) <= now.getTime()) {
        throw new AccessError('expired', 'The link code has expired.');
      }
      const user = this.database
        .prepare<[string], { status: string; minecraftUuid: string | null }>(
          'SELECT status, minecraftUuid FROM "user" WHERE id = ?',
        )
        .get(userId);
      if (user === undefined) throw new AccessError('not_found', 'User not found.');
      if (user.status !== 'active') throw new AccessError('conflict', 'Disabled accounts cannot be linked.');
      if (user.minecraftUuid !== null && user.minecraftUuid !== challenge.minecraftUuid) {
        throw new AccessError('conflict', 'Unlink the current Minecraft account before linking another one.');
      }
      const linked = this.database
        .prepare<[string, string], { id: string }>('SELECT id FROM "user" WHERE minecraftUuid = ? AND id <> ?')
        .get(challenge.minecraftUuid, userId);
      if (linked !== undefined) throw new AccessError('conflict', 'That Minecraft account is already linked.');
      this.database
        .prepare('UPDATE "user" SET minecraftUuid = ?, minecraftName = ?, updatedAt = ? WHERE id = ?')
        .run(challenge.minecraftUuid, challenge.minecraftName, now.getTime(), userId);
      const consumed = this.database
        .prepare('UPDATE minecraftLinkChallenge SET usedAt = ?, usedByUserId = ? WHERE id = ? AND usedAt IS NULL')
        .run(now.getTime(), userId, challenge.id);
      if (consumed.changes !== 1) throw new AccessError('conflict', 'The link code was already used.');
      return this.requireUserById(userId);
    });
    return transaction.immediate();
  }

  resolveInvitation(secret: string, now = new Date()): OnboardingClaim {
    const row = this.database
      .prepare<[string], InvitationClaimRow>(
        'SELECT id, expiresAt, acceptedAt, revokedAt FROM invitation WHERE tokenHash = ?',
      )
      .get(hashSecret(secret));
    if (row === undefined || row.acceptedAt !== null || row.revokedAt !== null) {
      throw new AccessError('invalid', 'Invalid invitation.');
    }
    if (toEpochMilliseconds(row.expiresAt) <= now.getTime()) throw new AccessError('expired', 'Invitation expired.');
    return { kind: 'invitation', recordId: row.id, handle: '' };
  }

  resolveRecovery(secret: string, now = new Date()): OnboardingClaim {
    const row = this.database
      .prepare<[string], RecoveryClaimRow>(
        'SELECT credentialRecovery.id, credentialRecovery.expiresAt, credentialRecovery.usedAt, "user".handle FROM credentialRecovery JOIN "user" ON "user".id = credentialRecovery.userId WHERE credentialRecovery.tokenHash = ?',
      )
      .get(hashSecret(secret));
    if (row === undefined || row.usedAt !== null) throw new AccessError('invalid', 'Invalid recovery link.');
    if (toEpochMilliseconds(row.expiresAt) <= now.getTime()) throw new AccessError('expired', 'Recovery link expired.');
    return { kind: 'recovery', recordId: row.id, handle: row.handle };
  }

  findMcpUser(userId: string): McpUser | null {
    const row = this.database
      .prepare<[string, UserStatus], McpUser>(
        'SELECT id, minecraftUuid FROM "user" WHERE id = ? AND status = ? AND minecraftUuid IS NOT NULL',
      )
      .get(userId, 'active');
    return row ?? null;
  }

  requireUserById(id: string): UserSummary {
    const row = this.database
      .prepare<[string], UserRow>(
        'SELECT id, handle, status, minecraftUuid, minecraftName, createdAt FROM "user" WHERE id = ?',
      )
      .get(id);
    if (row === undefined) throw new AccessError('not_found', 'User not found.');
    return toUserSummary(row);
  }

  hasPasskey(userId: string): boolean {
    return (
      this.database
        .prepare<[string], { found: number }>('SELECT 1 AS found FROM passkey WHERE userId = ? LIMIT 1')
        .get(userId) !== undefined
    );
  }

  isHandleAvailable(handle: string): boolean {
    return (
      this.database
        .prepare<[string], { found: number }>('SELECT 1 AS found FROM "user" WHERE handle = ? LIMIT 1')
        .get(handle) === undefined
    );
  }

  assertCredentialUserActive(credentialId: string): void {
    const row = this.database
      .prepare<[string], { status: string }>(
        'SELECT "user".status FROM passkey JOIN "user" ON "user".id = passkey.userId WHERE passkey.credentialID = ?',
      )
      .get(credentialId);
    if (row === undefined || row.status !== 'active') {
      throw new AccessError('invalid', 'Passkey authentication is not available for this account.');
    }
  }

  private getInvitation(id: string, now: Date): InvitationSummary | null {
    const row = this.database
      .prepare<[string], InvitationRow>(
        'SELECT id, createdAt, expiresAt, acceptedAt, revokedAt FROM invitation WHERE id = ?',
      )
      .get(id);
    return row === undefined ? null : toInvitationSummary(row, now);
  }

  private setUserStatus(handle: string, status: UserStatus): UserSummary {
    const changed = this.database
      .prepare('UPDATE "user" SET status = ?, updatedAt = ? WHERE handle = ?')
      .run(status, Date.now(), handle);
    if (changed.changes !== 1) throw new AccessError('not_found', 'User not found.');
    return this.requireUserByHandle(handle);
  }

  private requireUserByHandle(handle: string): UserSummary {
    const row = this.database
      .prepare<[string], UserRow>(
        'SELECT id, handle, status, minecraftUuid, minecraftName, createdAt FROM "user" WHERE handle = ?',
      )
      .get(handle);
    if (row === undefined) throw new AccessError('not_found', 'User not found.');
    return toUserSummary(row);
  }
}

export function configureDatabase(database: Database.Database, readonly = false): void {
  database.pragma('foreign_keys = ON');
  database.pragma('busy_timeout = 5000');
  if (!readonly) {
    database.pragma('journal_mode = WAL');
    database.pragma('synchronous = FULL');
  }
}

export function hashSecret(secret: string): string {
  return createHash('sha256').update(secret, 'utf8').digest('hex');
}

function randomSecret(): string {
  return randomBytes(32).toString('base64url');
}

function humanCode(): string {
  const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
  const bytes = randomBytes(12);
  return Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join('');
}

function normalizeCode(code: string): string {
  return code.replaceAll('-', '').trim().toUpperCase();
}

function normalizePage(page: number): number {
  return Number.isSafeInteger(page) && page > 0 ? page : 1;
}

function pageEnvelope<T>(page: number, totalItems: number, items: readonly T[]): Page<T> {
  return {
    page,
    pageSize: PAGE_SIZE,
    totalItems,
    totalPages: Math.ceil(totalItems / PAGE_SIZE),
    items,
  };
}

function scalarCount(database: Database.Database, sql: string): number {
  const row = database.prepare<[], { count: number }>(sql).get();
  if (row === undefined) throw new Error('Count query did not return a row.');
  return row.count;
}

function toUserSummary(row: UserRow): UserSummary {
  const status: UserStatus = row.status === 'disabled' ? 'disabled' : 'active';
  return {
    id: row.id,
    handle: row.handle,
    status,
    minecraftAccount:
      row.minecraftUuid === null || row.minecraftName === null
        ? null
        : { uuid: row.minecraftUuid, name: row.minecraftName },
    createdAt: toRfc3339(row.createdAt),
  };
}

function toInvitationSummary(row: InvitationRow, now: Date): InvitationSummary {
  const status =
    row.revokedAt !== null
      ? 'revoked'
      : row.acceptedAt !== null
        ? 'accepted'
        : toEpochMilliseconds(row.expiresAt) <= now.getTime()
          ? 'expired'
          : 'pending';
  return {
    id: row.id,
    status,
    createdAt: toRfc3339(row.createdAt),
    expiresAt: toRfc3339(row.expiresAt),
  };
}

function toRfc3339(value: number | string): string {
  return new Date(toEpochMilliseconds(value)).toISOString();
}

function toEpochMilliseconds(value: number | string): number {
  if (typeof value === 'number') return value;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : new Date(value).getTime();
}
