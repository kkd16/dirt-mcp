export class BodyTooLargeError extends Error {}

export class InvalidBodyEncodingError extends Error {}

type HttpBody = Pick<Request, 'body' | 'headers'>;

export async function readBoundedText(source: HttpBody, maximumBytes: number): Promise<string> {
  const contentLength = source.headers.get('Content-Length');
  if (contentLength !== null && (!/^\d+$/u.test(contentLength) || Number(contentLength) > maximumBytes)) {
    await cancelBody(source.body);
    throw new BodyTooLargeError();
  }
  if (source.body === null) return '';

  let bytes = new Uint8Array(Math.min(maximumBytes, 8_192));
  let size = 0;
  try {
    await source.body.pipeTo(
      new WritableStream<Uint8Array>({
        write(chunk) {
          const nextSize = size + chunk.byteLength;
          if (nextSize > maximumBytes) throw new BodyTooLargeError();
          if (nextSize > bytes.byteLength) {
            const grown = new Uint8Array(Math.min(maximumBytes, Math.max(nextSize, bytes.byteLength * 2)));
            grown.set(bytes.subarray(0, size));
            bytes = grown;
          }
          bytes.set(chunk, size);
          size = nextSize;
        },
      }),
    );
  } catch (error: unknown) {
    if (error instanceof BodyTooLargeError) await cancelBody(source.body);
    throw error;
  }

  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(0, size));
  } catch {
    throw new InvalidBodyEncodingError();
  }
}

async function cancelBody(body: ReadableStream<Uint8Array> | null): Promise<void> {
  try {
    await body?.cancel();
  } catch {
    // pipeTo may already have canceled the stream.
  }
}
