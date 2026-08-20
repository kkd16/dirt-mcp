import type * as z from 'zod/v4';
import { ToolFailure } from '../bridge/errors.ts';
import { BlockPositionSchema, BoundsSchema } from './common.ts';

type BlockPosition = z.infer<typeof BlockPositionSchema>;
type Bounds = z.infer<typeof BoundsSchema>;
type CoordinateTriple = { readonly x: number; readonly y: number; readonly z: number };

export function normalizedBounds(first: BlockPosition, second: BlockPosition): Bounds {
  return {
    min: {
      x: Math.min(first.x, second.x),
      y: Math.min(first.y, second.y),
      z: Math.min(first.z, second.z),
    },
    max: {
      x: Math.max(first.x, second.x),
      y: Math.max(first.y, second.y),
      z: Math.max(first.z, second.z),
    },
  };
}

export function sameCoordinates(left: CoordinateTriple, right: CoordinateTriple): boolean {
  return left.x === right.x && left.y === right.y && left.z === right.z;
}

export function sameBounds(left: Bounds, right: Bounds): boolean {
  return sameCoordinates(left.min, right.min) && sameCoordinates(left.max, right.max);
}

export function containsPosition(bounds: Bounds, position: BlockPosition): boolean {
  return (
    position.x >= bounds.min.x &&
    position.x <= bounds.max.x &&
    position.y >= bounds.min.y &&
    position.y <= bounds.max.y &&
    position.z >= bounds.min.z &&
    position.z <= bounds.max.z
  );
}

export function inclusiveBlockVolume(bounds: Bounds): bigint {
  const xSize = BigInt(bounds.max.x) - BigInt(bounds.min.x) + 1n;
  const ySize = BigInt(bounds.max.y) - BigInt(bounds.min.y) + 1n;
  const zSize = BigInt(bounds.max.z) - BigInt(bounds.min.z) + 1n;
  return xSize * ySize * zSize;
}

export function invalidBridgeResponse(message: string): never {
  throw new ToolFailure('bridge_invalid_response', message);
}

export function requireMatchingWorld(expected: string, actual: string): void {
  if (actual !== expected) {
    invalidBridgeResponse('Paper bridge response world did not match the request.');
  }
}
