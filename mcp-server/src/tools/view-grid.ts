import { ToolFailure } from '../bridge/errors.ts';
import type { ScanOrthographicViewBlocksOutput, ScanOrthographicViewGridOutput } from './inspection.ts';

const MAX_ARRAY_LENGTH = 0xffff_ffff;

interface RequestedViewport {
  readonly depth: number;
  readonly horizontalRadius: number;
  readonly maxDistance: number;
  readonly verticalRadius: number;
}

export function compactView(
  view: ScanOrthographicViewBlocksOutput,
  requested: RequestedViewport,
): ScanOrthographicViewGridOutput {
  if (
    view.viewport.depth !== requested.depth ||
    view.viewport.horizontalRadius !== requested.horizontalRadius ||
    view.viewport.verticalRadius !== requested.verticalRadius ||
    view.viewport.maxDistance !== requested.maxDistance
  ) {
    throw invalidView('Bridge returned a viewport different from the requested view.');
  }
  if (view.visibleBlockCount !== view.blocks.length) {
    throw invalidView('Bridge returned an inconsistent visible block count.');
  }

  const width = 2 * requested.horizontalRadius + 1;
  const height = 2 * requested.verticalRadius + 1;
  const sightlineCount = width * height;
  const maximumScannedVolume = sightlineCount * requested.maxDistance;
  if (
    !Number.isSafeInteger(sightlineCount) ||
    !Number.isSafeInteger(maximumScannedVolume) ||
    width > MAX_ARRAY_LENGTH ||
    height > MAX_ARRAY_LENGTH ||
    view.scannedVolume < sightlineCount ||
    view.scannedVolume > maximumScannedVolume
  ) {
    throw invalidView('Bridge returned invalid orthographic scan dimensions.');
  }

  const blockStateIndexRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const distanceRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const blockStatePalette: string[] = [];
  const paletteIndices = new Map<string, number>();
  const occupiedCells = new Set<number>();

  for (const block of view.blocks) {
    const rowIndex = requested.verticalRadius - block.offset.vertical;
    const columnIndex = block.offset.horizontal + requested.horizontalRadius;
    const blockStateIndexRow = blockStateIndexRows[rowIndex];
    const distanceRow = distanceRows[rowIndex];
    if (
      blockStateIndexRow === undefined ||
      distanceRow === undefined ||
      columnIndex < 0 ||
      columnIndex >= width ||
      block.offset.distance > requested.maxDistance
    ) {
      throw invalidView('Bridge returned a view block outside its viewport.');
    }

    const cell = rowIndex * width + columnIndex;
    if (occupiedCells.has(cell)) {
      throw invalidView('Bridge returned multiple view blocks for one sightline.');
    }
    occupiedCells.add(cell);

    let paletteIndex = paletteIndices.get(block.blockState);
    if (paletteIndex === undefined) {
      blockStatePalette.push(block.blockState);
      paletteIndex = blockStatePalette.length;
      paletteIndices.set(block.blockState, paletteIndex);
    }
    blockStateIndexRow[columnIndex] = paletteIndex;
    distanceRow[columnIndex] = block.offset.distance;
  }

  const { blocks: _blocks, ...metadata } = view;
  return { ...metadata, format: 'grid', blockStatePalette, blockStateIndexRows, distanceRows };
}

function invalidView(message: string): ToolFailure {
  return new ToolFailure('bridge_invalid_response', message);
}
