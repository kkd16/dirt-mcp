import { cimd } from '@better-auth/cimd';
import { fetchClientMetadataResource } from '@better-auth/cimd/node';
import { APIError, betterAuth, getCurrentAdapter, type Auth, type BetterAuthOptions } from 'better-auth';
import { mcp } from '@better-auth/mcp';
import { passkey } from '@better-auth/passkey';
import { jwt } from 'better-auth/plugins';
import type Database from 'better-sqlite3';
import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import * as z from 'zod';
import { AccessProfileSchema } from './access/profiles.ts';
import type { AuthConfig } from './config.ts';
import { AccessError, type AccessRepository, type OnboardingClaim } from './access/repository.ts';
import { dirtAccessSchema } from './access/schema.ts';

const ONBOARDING_TTL_SECONDS = 10 * 60;
export const SESSION_FRESH_AGE_SECONDS = 5 * 60;
const MINECRAFT_USERNAME_PATTERN = /^[A-Za-z0-9_]{3,16}$/u;

const ticketPayloadSchema = z
  .object({
    kind: z.enum(['invitation', 'recovery']),
    recordId: z.string().min(1),
    username: z.string().regex(MINECRAFT_USERNAME_PATTERN),
    minecraftUuid: z.uuid().nullable(),
    accessProfile: AccessProfileSchema,
    exp: z.number().int().safe(),
  })
  .strict();
type TicketPayload = z.infer<typeof ticketPayloadSchema>;

export function createAuth(config: AuthConfig, database: Database.Database, repository: AccessRepository): Auth {
  return betterAuth(createAuthOptions(config, database, repository));
}

export function createAuthOptions(
  config: AuthConfig,
  database: Database.Database,
  repository: AccessRepository,
): BetterAuthOptions {
  const resource = `${config.publicOrigin}/mcp`;
  const secure = config.publicOrigin.startsWith('https://');

  return {
    appName: 'Dirt',
    baseURL: config.publicOrigin,
    basePath: '/api/auth',
    secret: config.authSecret,
    database,
    trustedOrigins: [config.publicOrigin],
    emailAndPassword: { enabled: false },
    user: {
      additionalFields: {
        status: {
          type: 'string',
          required: true,
          defaultValue: 'active',
          sortable: true,
          returned: true,
          input: false,
        },
        accessProfile: {
          type: 'string',
          required: true,
          returned: true,
          input: false,
        },
        minecraftUuid: { type: 'string', required: false, unique: true, returned: true, input: false },
        authorizationVersion: {
          type: 'number',
          required: true,
          defaultValue: 0,
          returned: false,
          input: false,
        },
      },
    },
    session: {
      expiresIn: 7 * 24 * 60 * 60,
      updateAge: 24 * 60 * 60,
      freshAge: SESSION_FRESH_AGE_SECONDS,
    },
    rateLimit: {
      enabled: true,
      window: 60,
      max: 100,
      customRules: {
        '/passkey/generate-authenticate-options': { window: 60, max: 20 },
        '/passkey/verify-authentication': { window: 60, max: 20 },
        '/passkey/generate-register-options': { window: 60, max: 10 },
        '/passkey/verify-registration': { window: 60, max: 10 },
      },
    },
    advanced: {
      cookiePrefix: 'dirt',
      useSecureCookies: secure,
      ipAddress: {
        ipAddressHeaders: ['x-forwarded-for'],
        trustedProxies: ['127.0.0.1/32', '::1/128'],
      },
      defaultCookieAttributes: {
        httpOnly: true,
        sameSite: 'strict',
        secure,
      },
    },
    telemetry: { enabled: false },
    logger: { disabled: true },
    onAPIError: { throw: true },
    disabledPaths: [
      '/oauth2/delete-consent',
      '/oauth2/get-consent',
      '/oauth2/get-consents',
      '/oauth2/update-consent',
      '/passkey/delete-passkey',
      '/token',
    ],
    plugins: [
      jwt({ disableSettingJwtHeader: true }),
      passkey({
        rpID: new URL(config.publicOrigin).hostname,
        rpName: 'Dirt',
        origin: config.publicOrigin,
        authenticatorSelection: {
          residentKey: 'required',
          userVerification: 'required',
        },
        registration: {
          requireSession: false,
          resolveUser({ ctx }) {
            const ticket = requireTicket(ctx.headers, config.authSecret, config.publicOrigin);
            return {
              id: ticket.kind === 'invitation' ? `invite-${ticket.recordId}` : `recovery-${ticket.recordId}`,
              name: ticket.username,
              displayName: ticket.username,
            };
          },
          async afterVerification({ ctx, verification, user }) {
            if (verification.registrationInfo?.userVerified !== true) {
              throw new APIError('UNAUTHORIZED', { message: 'User verification is required.' });
            }
            // Better Auth wraps registration in its adapter transaction only
            // when the caller requests a session. Dirt provisions or recovers
            // the account in this callback, so accepting `false` could commit
            // those changes before the passkey itself is persisted.
            if (ctx.body.createSession !== true) {
              throw new APIError('BAD_REQUEST', { message: 'Passkey registration must create a fresh session.' });
            }
            const ticket = requireTicket(ctx.headers, config.authSecret, config.publicOrigin);
            const expectedUserId = `${ticket.kind === 'invitation' ? 'invite' : 'recovery'}-${ticket.recordId}`;
            if (user.id !== expectedUserId || user.name !== ticket.username) {
              throw new APIError('UNAUTHORIZED', { message: 'The onboarding ceremony does not match this link.' });
            }
            const adapter = await getCurrentAdapter(ctx.context.adapter);
            const now = new Date();
            if (ticket.kind === 'recovery') {
              const recovery = await adapter.findOne<{
                id: string;
                userId: string;
                expiresAt: Date;
                usedAt: Date | null;
              }>({ model: 'credentialRecovery', where: [{ field: 'id', value: ticket.recordId }] });
              if (recovery === null || recovery.usedAt !== null || recovery.expiresAt.getTime() <= now.getTime()) {
                throw new APIError('UNAUTHORIZED', { message: 'Recovery link is invalid or expired.' });
              }
              const consumed = await adapter.updateMany({
                model: 'credentialRecovery',
                where: [
                  { field: 'id', value: ticket.recordId },
                  { field: 'usedAt', value: null },
                ],
                update: { usedAt: now },
              });
              if (consumed !== 1) throw new APIError('UNAUTHORIZED', { message: 'Recovery link was already used.' });
              repository.revokeAuthorization(recovery.userId, now);
              await adapter.deleteMany({ model: 'passkey', where: [{ field: 'userId', value: recovery.userId }] });
              return { userId: recovery.userId, name: 'Recovered passkey' };
            }

            const invitation = await adapter.findOne<{
              id: string;
              minecraftUuid: string;
              minecraftName: string;
              accessProfile: string;
              expiresAt: Date;
              acceptedAt: Date | null;
              revokedAt: Date | null;
            }>({ model: 'invitation', where: [{ field: 'id', value: ticket.recordId }] });
            if (
              invitation === null ||
              ticket.minecraftUuid === null ||
              invitation.minecraftUuid !== ticket.minecraftUuid ||
              invitation.minecraftName !== ticket.username ||
              invitation.accessProfile !== ticket.accessProfile ||
              invitation.acceptedAt !== null ||
              invitation.revokedAt !== null ||
              invitation.expiresAt.getTime() <= now.getTime()
            ) {
              throw new APIError('UNAUTHORIZED', { message: 'Invitation is invalid or expired.' });
            }
            const userId = randomUUID();
            await adapter.create({
              model: 'user',
              forceAllowId: true,
              data: {
                id: userId,
                name: ticket.username,
                email: `${userId}@dirt.placeholder.invalid`,
                emailVerified: false,
                status: 'active',
                accessProfile: ticket.accessProfile,
                minecraftUuid: ticket.minecraftUuid,
                createdAt: now,
                updatedAt: now,
              },
            });
            const accepted = await adapter.updateMany({
              model: 'invitation',
              where: [
                { field: 'id', value: ticket.recordId },
                { field: 'acceptedAt', value: null },
                { field: 'revokedAt', value: null },
              ],
              update: { acceptedAt: now, acceptedByUserId: userId },
            });
            if (accepted !== 1) throw new APIError('UNAUTHORIZED', { message: 'Invitation was already used.' });
            return { userId, name: 'Primary passkey' };
          },
        },
        authentication: {
          afterVerification({ verification, clientData }) {
            if (!verification.authenticationInfo.userVerified) {
              throw new APIError('UNAUTHORIZED', { message: 'User verification is required.' });
            }
            try {
              repository.assertCredentialUserActive(clientData.id);
            } catch (error: unknown) {
              if (error instanceof AccessError) {
                throw new APIError('UNAUTHORIZED', { message: 'Authentication failed.' });
              }
              throw error;
            }
          },
        },
      }),
      // @ts-expect-error -- https://github.com/better-auth/better-auth/issues/10213
      mcp({
        loginPage: '/',
        consentPage: '/consent',
        resource,
        scopes: ['dirt:mcp', 'offline_access'],
        grantTypes: ['authorization_code', 'refresh_token'],
        accessTokenExpiresIn: 300,
        codeExpiresIn: 300,
        refreshTokenExpiresIn: 2_592_000,
        allowDynamicClientRegistration: false,
        allowUnauthenticatedClientRegistration: false,
        clientRegistrationRequirePKCE: true,
        clientPrivileges: () => false,
        resourcePrivileges: () => false,
        customAccessTokenClaims({ user }) {
          return user === null || user === undefined
            ? {}
            : { dirt_auth_version: repository.requireAuthorizationVersion(user.id) };
        },
      }),
      cimd({
        fetchClientMetadataResource,
        metadataProfile: 'mcp-2026-07-28',
      }),
      dirtAccessSchema,
    ],
  };
}

export type DirtAuth = ReturnType<typeof createAuth>;

export function createOnboardingTicket(
  claim: OnboardingClaim,
  secret: string,
  now = new Date(),
): { readonly value: string; readonly maxAge: number } {
  const payload: TicketPayload = {
    ...claim,
    exp: Math.floor(now.getTime() / 1_000) + ONBOARDING_TTL_SECONDS,
  };
  const encoded = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  return { value: `${encoded}.${sign(encoded, secret)}`, maxAge: ONBOARDING_TTL_SECONDS };
}

export function onboardingCookieName(publicOrigin: string): string {
  return publicOrigin.startsWith('https://') ? '__Host-dirt-onboarding' : 'dirt-onboarding';
}

export function onboardingCookieHeader(
  publicOrigin: string,
  ticket: { readonly value: string; readonly maxAge: number },
): string {
  const secure = publicOrigin.startsWith('https://');
  return `${onboardingCookieName(publicOrigin)}=${ticket.value}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${ticket.maxAge}${secure ? '; Secure' : ''}`;
}

export function readOnboardingClaim(headers: Headers, secret: string, publicOrigin: string): OnboardingClaim {
  const ticket = requireTicket(headers, secret, publicOrigin);
  return {
    kind: ticket.kind,
    recordId: ticket.recordId,
    username: ticket.username,
    minecraftUuid: ticket.minecraftUuid,
    accessProfile: ticket.accessProfile,
  };
}

function requireTicket(headers: Headers | undefined, secret: string, publicOrigin: string): TicketPayload {
  const cookie = headers?.get('cookie');
  const expectedName = onboardingCookieName(publicOrigin);
  const match = cookie
    ?.split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${expectedName}=`));
  const value = match?.slice(expectedName.length + 1);
  if (value === undefined)
    throw new APIError('UNAUTHORIZED', { message: 'A valid invitation or recovery link is required.' });
  const separator = value.lastIndexOf('.');
  if (separator <= 0) throw new APIError('UNAUTHORIZED', { message: 'Invalid onboarding ticket.' });
  const encoded = value.slice(0, separator);
  const signature = value.slice(separator + 1);
  const expected = sign(encoded, secret);
  if (!safeEqual(signature, expected)) throw new APIError('UNAUTHORIZED', { message: 'Invalid onboarding ticket.' });
  try {
    const parsed = ticketPayloadSchema.parse(JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8')));
    if (parsed.exp <= Math.floor(Date.now() / 1_000)) {
      throw new Error('invalid ticket');
    }
    return parsed;
  } catch {
    throw new APIError('UNAUTHORIZED', { message: 'Invalid or expired onboarding ticket.' });
  }
}

function sign(value: string, secret: string): string {
  return createHmac('sha256', secret).update(value, 'utf8').digest('base64url');
}

function safeEqual(left: string, right: string): boolean {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length && timingSafeEqual(leftBuffer, rightBuffer);
}
