import type { ScanOrthographicViewBlocksOutput, ScanOrthographicViewGridOutput } from './inspection.ts';

export function compactView(view: ScanOrthographicViewBlocksOutput): ScanOrthographicViewGridOutput {
  const width = 2 * view.viewport.horizontalRadius + 1;
  const height = 2 * view.viewport.verticalRadius + 1;

  const blockStateIndexRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const distanceRows = Array.from({ length: height }, () => Array<number>(width).fill(0));
  const blockStatePalette: string[] = [];
  const paletteIndices = new Map<string, number>();

  for (const block of view.blocks) {
    const rowIndex = view.viewport.verticalRadius - block.offset.vertical;
    const columnIndex = block.offset.horizontal + view.viewport.horizontalRadius;

    let paletteIndex = paletteIndices.get(block.blockState);
    if (paletteIndex === undefined) {
      blockStatePalette.push(block.blockState);
      paletteIndex = blockStatePalette.length;
      paletteIndices.set(block.blockState, paletteIndex);
    }
    blockStateIndexRows[rowIndex]![columnIndex] = paletteIndex;
    distanceRows[rowIndex]![columnIndex] = block.offset.distance;
  }

  const { blocks: _blocks, ...metadata } = view;
  return { ...metadata, format: 'grid', blockStatePalette, blockStateIndexRows, distanceRows };
}
