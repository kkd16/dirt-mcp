import * as z from 'zod';

export const ACCESS_PROFILES = ['viewer', 'builder', 'operator'] as const;

export type AccessProfile = (typeof ACCESS_PROFILES)[number];

export const AccessProfileSchema = z.enum(ACCESS_PROFILES);

export const ACCESS_PROFILE_DETAILS = {
  viewer: {
    title: 'Viewer',
    description: 'Inspect server and world state without changing it.',
  },
  builder: {
    title: 'Builder',
    description: 'Inspect the world and make bounded edits with undo.',
  },
  operator: {
    title: 'Operator',
    description: 'Use every Dirt tool, including console-equivalent Minecraft commands.',
  },
} as const satisfies Readonly<Record<AccessProfile, { readonly title: string; readonly description: string }>>;

const ACCESS_PROFILE_RANK: Readonly<Record<AccessProfile, number>> = {
  viewer: 0,
  builder: 1,
  operator: 2,
};

export function profileGrants(profile: AccessProfile, requiredProfile: AccessProfile): boolean {
  return ACCESS_PROFILE_RANK[profile] >= ACCESS_PROFILE_RANK[requiredProfile];
}
