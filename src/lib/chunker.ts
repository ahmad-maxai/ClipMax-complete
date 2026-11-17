/**
 * Exact TypeScript port of ClipMax's native TextChunker.kt
 * Streams Unicode text in bounded chunks (default 50,000 UTF-16 code units),
 * never splitting a valid surrogate pair between chunks.
 */

export const DEFAULT_CHUNK_CHARS = 50_000;
export const MAX_ITEM_BYTES = 16 * 1024 * 1024; // 16 MiB
export const MAX_BACKUP_BYTES = 128 * 1024 * 1024; // 128 MiB

/**
 * Checks if char code is a high surrogate (0xD800 - 0xDBFF)
 */
export function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff;
}

/**
 * Checks if char code is a low surrogate (0xDC00 - 0xDFFF)
 */
export function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff;
}

/**
 * Calculates exact UTF-8 byte length, matching ClipStore.kt's utf8Length()
 */
export function utf8ByteLength(text: string): number {
  let bytes = 0;
  let i = 0;
  while (i < text.length) {
    const code = text.charCodeAt(i);
    if (code <= 0x7f) {
      bytes += 1;
    } else if (code <= 0x7ff) {
      bytes += 2;
    } else if (isHighSurrogate(code) && i + 1 < text.length && isLowSurrogate(text.charCodeAt(i + 1))) {
      bytes += 4;
      i++;
    } else if (code >= 0xd800 && code <= 0xdfff) {
      // Unpaired surrogate
      bytes += 3;
    } else {
      bytes += 3;
    }
    i++;
  }
  return bytes;
}

/**
 * Counts the exact greedy chunk total without allocating full chunk arrays.
 * Matches TextChunker.chunkCount()
 */
export function chunkCount(text: string, chunkChars = DEFAULT_CHUNK_CHARS): number {
  if (chunkChars < 2) throw new Error("Chunk size must be at least two UTF-16 code units.");
  if (text.length === 0) return 0;

  let chunks = 0;
  let used = 0;
  let index = 0;

  while (index < text.length) {
    const first = text.charCodeAt(index);
    const width = isHighSurrogate(first) && index + 1 < text.length && isLowSurrogate(text.charCodeAt(index + 1)) ? 2 : 1;

    if (used + width > chunkChars) {
      chunks++;
      used = 0;
    }
    used += width;
    index += width;
  }

  return chunks + (used > 0 ? 1 : 0);
}

/**
 * Splits text into bounded chunks of at most `chunkChars` code units,
 * guaranteeing no surrogate pairs are broken.
 */
export function splitIntoChunks(text: string, chunkChars = DEFAULT_CHUNK_CHARS): string[] {
  if (chunkChars < 2) throw new Error("Chunk size must be at least two UTF-16 code units.");
  if (text.length === 0) return [];

  const result: string[] = [];
  let index = 0;

  while (index < text.length) {
    let targetEnd = Math.min(index + chunkChars, text.length);

    // If the slice ends on a high surrogate, retreat by 1 so the surrogate pair isn't split
    if (targetEnd < text.length && isHighSurrogate(text.charCodeAt(targetEnd - 1))) {
      targetEnd--;
    }

    // Edge case: if chunkChars was somehow 1 and we retreated to index
    if (targetEnd <= index) {
      targetEnd = Math.min(index + 2, text.length);
    }

    const chunk = text.slice(index, targetEnd);
    result.push(chunk);
    index = targetEnd;
  }

  return result;
}

/**
 * Formats byte sizes into human-readable strings (B, KB, MB)
 */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}
