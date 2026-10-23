import JSZip from "jszip";
import { ClipItem } from "./types";
import { utf8ByteLength, MAX_BACKUP_BYTES, MAX_ITEM_BYTES } from "./chunker";

/**
 * Creates a valid ClipMax backup ZIP file adhering to ClipMax's native backup structure.
 * Stored paths:
 *   metadata/{id}.json (compatible with Kotlin metadata reader)
 *   content/{id}.txt (raw UTF-8 content)
 */
export async function createZipBackup(items: ClipItem[]): Promise<Blob> {
  const zip = new JSZip();
  const metaFolder = zip.folder("metadata");
  const contentFolder = zip.folder("content");

  if (!metaFolder || !contentFolder) {
    throw new Error("Could not initialize ZIP folders");
  }

  for (const item of items) {
    const metaRecord = {
      magic: "CMAX",
      version: 1,
      id: item.id,
      title: item.title,
      createdAt: item.createdAt,
      modifiedAt: item.modifiedAt,
      characterCount: item.characterCount,
      sizeBytes: item.sizeBytes,
      pinned: item.pinned,
      contentFile: `${item.id}.txt`,
    };

    metaFolder.file(`${item.id}.json`, JSON.stringify(metaRecord, null, 2));
    contentFolder.file(`${item.id}.txt`, item.content);
  }

  // Also include a human-readable manifest
  zip.file(
    "manifest.txt",
    `ClipMax Backup Archive\nExported: ${new Date().toISOString()}\nItem Count: ${items.length}\nFormat: ClipMax v0.5.1 / Redesign v1.0`
  );

  return await zip.generateAsync({
    type: "blob",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });
}

export type RestoreResult = {
  restoredCount: number;
  items: ClipItem[];
  warnings: string[];
};

/**
 * Restores a backup ZIP with strict safeguards:
 * - 128 MiB maximum uncompressed archive limit (matching ClipStore.kt MAX_BACKUP_BYTES)
 * - 16 MiB maximum per item (matching ClipStore.kt MAX_ITEM_BYTES)
 * - Safe relative paths check (metadata/ and content/ only; rejects path traversal)
 */
export async function restoreZipBackup(
  zipBlob: Blob,
  existingItems: ClipItem[]
): Promise<RestoreResult> {
  const zip = new JSZip();
  const loaded = await zip.loadAsync(zipBlob);
  const warnings: string[] = [];

  let totalUnpackedBytes = 0;
  const existingMap = new Map(existingItems.map((c) => [c.id, c]));
  const restoredItems: ClipItem[] = [];

  // Index files
  const metaFiles: JSZip.JSZipObject[] = [];
  const contentFiles = new Map<string, JSZip.JSZipObject>();

  loaded.forEach((relativePath, file) => {
    if (file.dir) return;

    // Path safety validation matching ClipStore.kt
    if (relativePath.includes("..") || relativePath.startsWith("/")) {
      throw new Error(`Potentially malicious path in backup: ${relativePath}`);
    }

    if (relativePath.startsWith("metadata/")) {
      metaFiles.push(file);
    } else if (relativePath.startsWith("content/")) {
      const fileName = relativePath.replace("content/", "");
      contentFiles.set(fileName, file);
    }
  });

  for (const metaFile of metaFiles) {
    const metaText = await metaFile.async("string");
    totalUnpackedBytes += metaText.length;
    if (totalUnpackedBytes > MAX_BACKUP_BYTES) {
      throw new Error("Backup exceeds ClipMax's 128 MiB restore limit.");
    }

    let metaObj: any;
    try {
      metaObj = JSON.parse(metaText);
    } catch {
      warnings.push(`Skipped malformed metadata file: ${metaFile.name}`);
      continue;
    }

    const contentFileName = metaObj.contentFile || `${metaObj.id}.txt`;
    const contentZipEntry = contentFiles.get(contentFileName);

    let content = "";
    if (contentZipEntry) {
      content = await contentZipEntry.async("string");
      totalUnpackedBytes += utf8ByteLength(content);
      if (totalUnpackedBytes > MAX_BACKUP_BYTES) {
        throw new Error("Backup exceeds ClipMax's 128 MiB restore limit.");
      }
    }

    const byteLen = utf8ByteLength(content);
    if (byteLen > MAX_ITEM_BYTES) {
      warnings.push(`Item ${metaObj.title} exceeded 16 MiB limit and was skipped.`);
      continue;
    }

    const idExists = existingMap.has(metaObj.id);
    const finalId = idExists
      ? `restored-${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 7)}`
      : metaObj.id || `restored-${Date.now().toString(36)}`;

    const finalTitle = idExists
      ? `${metaObj.title || "Untitled"} (restored)`.slice(0, 200)
      : (metaObj.title || "Untitled").slice(0, 200);

    const restoredItem: ClipItem = {
      id: finalId,
      title: finalTitle,
      content,
      createdAt: metaObj.createdAt || Date.now(),
      modifiedAt: metaObj.modifiedAt || Date.now(),
      characterCount: content.length,
      sizeBytes: byteLen,
      pinned: Boolean(metaObj.pinned),
      expiresAt: metaObj.expiresAt || null,
      contentFile: `${finalId}.txt`,
    };

    restoredItems.push(restoredItem);
  }

  // Merge restored items with existing items
  const merged = [...existingItems];
  for (const item of restoredItems) {
    const idx = merged.findIndex((x) => x.id === item.id);
    if (idx >= 0) {
      merged[idx] = item;
    } else {
      merged.unshift(item);
    }
  }

  return {
    restoredCount: restoredItems.length,
    items: merged,
    warnings,
  };
}

/**
 * Triggers a native document download for a blob or text content.
 */
export function downloadFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => {
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }, 1000);
}
