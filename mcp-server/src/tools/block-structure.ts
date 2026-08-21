import type * as z from 'zod/v4';
import {
  BLOCK_AXES,
  type BlockPositionSchema,
  type BoundsSchema,
  INT32_MAX,
  INT32_MIN,
  type PalettePlacementSchema,
  type PaletteRunSchema,
} from './common.ts';

export type BlockPosition = z.infer<typeof BlockPositionSchema>;
export type Bounds = z.infer<typeof BoundsSchema>;
export type PalettePlacement = z.infer<typeof PalettePlacementSchema>;
export type PaletteRun = z.infer<typeof PaletteRunSchema>;

export function resolveOffset(origin: BlockPosition, x: number, y: number, z: number): BlockPosition | undefined {
  const resolved = { x: origin.x + x, y: origin.y + y, z: origin.z + z };
  return BLOCK_AXES.some((axis) => resolved[axis] < INT32_MIN || resolved[axis] > INT32_MAX) ? undefined : resolved;
}

export function isForwardRun(run: PaletteRun): boolean {
  return run[1] <= run[4] && run[2] <= run[5] && run[3] <= run[6];
}

export function structureBlockCount(placements: readonly PalettePlacement[], runs: readonly PaletteRun[]): bigint {
  let count = BigInt(placements.length);
  for (const run of runs) {
    count +=
      (BigInt(run[4]) - BigInt(run[1]) + 1n) *
      (BigInt(run[5]) - BigInt(run[2]) + 1n) *
      (BigInt(run[6]) - BigInt(run[3]) + 1n);
  }
  return count;
}

export function structureBounds(
  origin: BlockPosition,
  placements: readonly PalettePlacement[],
  runs: readonly PaletteRun[],
): Bounds | null {
  let minimum: BlockPosition | undefined;
  let maximum: BlockPosition | undefined;
  const include = (x: number, y: number, z: number): void => {
    const position = { x, y, z };
    if (minimum === undefined || maximum === undefined) {
      minimum = { ...position };
      maximum = { ...position };
      return;
    }
    for (const axis of BLOCK_AXES) {
      minimum[axis] = Math.min(minimum[axis], position[axis]);
      maximum[axis] = Math.max(maximum[axis], position[axis]);
    }
  };

  for (const placement of placements) {
    include(placement[1], placement[2], placement[3]);
  }
  for (const run of runs) {
    include(run[1], run[2], run[3]);
    include(run[4], run[5], run[6]);
  }
  if (minimum === undefined || maximum === undefined) return null;
  return {
    min: { x: origin.x + minimum.x, y: origin.y + minimum.y, z: origin.z + minimum.z },
    max: { x: origin.x + maximum.x, y: origin.y + maximum.y, z: origin.z + maximum.z },
  };
}

export function runContains(run: PaletteRun, x: number, y: number, z: number): boolean {
  return x >= run[1] && x <= run[4] && y >= run[2] && y <= run[5] && z >= run[3] && z <= run[6];
}

export function runsOverlap(left: PaletteRun, right: PaletteRun): boolean {
  return (
    left[1] <= right[4] &&
    right[1] <= left[4] &&
    left[2] <= right[5] &&
    right[2] <= left[5] &&
    left[3] <= right[6] &&
    right[3] <= left[6]
  );
}
