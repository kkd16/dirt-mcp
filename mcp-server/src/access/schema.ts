import type { BetterAuthPlugin } from 'better-auth';

export const dirtAccessSchema = {
  id: 'dirt-access-schema',
  schema: {
    invitation: {
      fields: {
        tokenHash: { type: 'string', unique: true, returned: false, sortable: true },
        minecraftUuid: { type: 'string', sortable: true },
        minecraftName: { type: 'string' },
        createdAt: { type: 'date', input: false, defaultValue: () => new Date() },
        expiresAt: { type: 'date', sortable: true },
        acceptedAt: { type: 'date', required: false },
        revokedAt: { type: 'date', required: false },
        acceptedByUserId: {
          type: 'string',
          required: false,
          references: { model: 'user', field: 'id', onDelete: 'set null' },
        },
      },
      indexes: [{ fields: ['createdAt'] }, { fields: ['expiresAt'] }, { fields: ['minecraftUuid'] }],
    },
    credentialRecovery: {
      fields: {
        tokenHash: { type: 'string', unique: true, returned: false, sortable: true },
        userId: { type: 'string', references: { model: 'user', field: 'id', onDelete: 'cascade' } },
        createdAt: { type: 'date', input: false, defaultValue: () => new Date() },
        expiresAt: { type: 'date', sortable: true },
        usedAt: { type: 'date', required: false },
      },
      indexes: [{ fields: ['userId'] }, { fields: ['expiresAt'] }],
    },
    minecraftLinkChallenge: {
      fields: {
        codeHash: { type: 'string', unique: true, returned: false, sortable: true },
        minecraftUuid: { type: 'string', unique: true, sortable: true },
        minecraftName: { type: 'string' },
        createdAt: { type: 'date', input: false, defaultValue: () => new Date() },
        expiresAt: { type: 'date', sortable: true },
        usedAt: { type: 'date', required: false },
        usedByUserId: {
          type: 'string',
          required: false,
          references: { model: 'user', field: 'id', onDelete: 'set null' },
        },
      },
      indexes: [{ fields: ['expiresAt'] }, { fields: ['minecraftUuid'] }],
    },
  },
} satisfies BetterAuthPlugin;
