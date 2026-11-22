import "dotenv/config";
import express from "express";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 3000;
const KLIPY_API_BASE = "https://api.klipy.com/api/v1";

interface RateBucket {
  count: number;
  resetAt: number;
}

const RATE_WINDOW_MS = 60_000;
const RATE_MAX_REQUESTS = 60;
const rateBuckets = new Map<string, RateBucket>();

function isRateLimited(clientKey: string): boolean {
  const now = Date.now();
  const bucket = rateBuckets.get(clientKey);
  if (!bucket || now >= bucket.resetAt) {
    rateBuckets.set(clientKey, { count: 1, resetAt: now + RATE_WINDOW_MS });
    return false;
  }
  if (bucket.count >= RATE_MAX_REQUESTS) {
    return true;
  }
  bucket.count += 1;
  return false;
}

function isAllowedKlipyMediaHost(rawUrl: string): boolean {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== "https:") return false;
    const host = parsed.hostname.toLowerCase();
    return host === "klipy.com" || host.endsWith(".klipy.com");
  } catch {
    return false;
  }
}

interface NormalizedGifItem {
  id: string;
  title: string;
  previewUrl: string;
  gifUrl: string;
  width: number;
  height: number;
}

function extractKlipyFormatUrl(fmt: unknown): { url: string; width: number; height: number } | null {
  if (!fmt || typeof fmt !== "object") return null;
  const obj = fmt as Record<string, unknown>;
  const url = typeof obj.url === "string" ? obj.url.trim() : "";
  if (!url || !isAllowedKlipyMediaHost(url)) return null;
  const w = typeof obj.width === "number" && obj.width > 0 ? Math.round(obj.width) : 320;
  const h = typeof obj.height === "number" && obj.height > 0 ? Math.round(obj.height) : 240;
  return { url, width: w, height: h };
}

function normalizeKlipyResponse(raw: unknown, fallbackPage: number, perPage: number) {
  const root = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const dataObj =
    root.data && typeof root.data === "object" ? (root.data as Record<string, unknown>) : root;
  const rawList = Array.isArray(dataObj.data)
    ? dataObj.data
    : Array.isArray(root.data)
      ? root.data
      : [];

  const items: NormalizedGifItem[] = [];
  for (const entry of rawList) {
    if (!entry || typeof entry !== "object") continue;
    const obj = entry as Record<string, unknown>;
    const id = String(obj.id ?? obj.slug ?? "").trim();
    if (!id) continue;
    const title = String(obj.title ?? "GIF").trim() || "GIF";
    const fileObj = (obj.file && typeof obj.file === "object" ? obj.file : {}) as Record<
      string,
      unknown
    >;

    const smGif = extractKlipyFormatUrl(
      (fileObj.sm as Record<string, unknown> | undefined)?.gif
    );
    const xsGif = extractKlipyFormatUrl(
      (fileObj.xs as Record<string, unknown> | undefined)?.gif
    );
    const mdGif = extractKlipyFormatUrl(
      (fileObj.md as Record<string, unknown> | undefined)?.gif
    );
    const hdGif = extractKlipyFormatUrl(
      (fileObj.hd as Record<string, unknown> | undefined)?.gif
    );

    const preview = smGif ?? xsGif ?? mdGif ?? hdGif;
    const full = mdGif ?? hdGif ?? smGif ?? xsGif;
    if (!preview || !full) continue;

    items.push({
      id,
      title,
      previewUrl: preview.url,
      gifUrl: full.url,
      width: preview.width,
      height: preview.height,
    });
  }

  const currentPage =
    typeof dataObj.current_page === "number" ? dataObj.current_page : fallbackPage;
  const hasNext =
    typeof dataObj.has_next === "boolean" ? dataObj.has_next : items.length >= perPage;

  return {
    items,
    page: currentPage,
    perPage,
    hasNext,
    attribution: "KLIPY",
  };
}

async function handleKlipyProxy(
  endpoint: "trending" | "search",
  req: express.Request,
  res: express.Response
) {
  const clientIp = req.ip || req.socket.remoteAddress || "unknown";
  if (isRateLimited(clientIp)) {
    return res.status(429).json({
      error: "rate_limited",
      message: "Too many GIF requests. Please wait a moment and try again.",
    });
  }

  const apiKey = (process.env.KLIPY_API_KEY ?? "").trim();
  if (!apiKey) {
    return res.status(503).json({
      error: "service_unconfigured",
      message: "GIF service is not configured on this proxy.",
    });
  }

  const pageRaw = Number.parseInt(String(req.query.page ?? "1"), 10);
  const page = Number.isFinite(pageRaw) ? Math.min(50, Math.max(1, pageRaw)) : 1;

  const perPageRaw = Number.parseInt(String(req.query.per_page ?? "20"), 10);
  const perPage = Number.isFinite(perPageRaw) ? Math.min(30, Math.max(1, perPageRaw)) : 20;

  const rawLocale = String(req.query.locale ?? "en")
    .trim()
    .toLowerCase()
    .replace(/[^a-z_-]/g, "")
    .slice(0, 12) || "en";

  const params = new URLSearchParams({
    page: String(page),
    per_page: String(perPage),
    locale: rawLocale,
    content_filter: "high",
    customer_id: (process.env.KLIPY_CLIENT_KEY ?? "clipmax_android").trim() || "clipmax_android",
  });

  if (endpoint === "search") {
    const query = String(req.query.q ?? "").trim().slice(0, 100);
    if (!query) {
      return res.status(400).json({
        error: "invalid_query",
        message: "Search query parameter 'q' is required.",
      });
    }
    params.set("q", query);
  }

  const targetUrl = `${KLIPY_API_BASE}/${encodeURIComponent(apiKey)}/gifs/${endpoint}?${params.toString()}`;

  try {
    const upstream = await fetch(targetUrl, {
      method: "GET",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(8_000),
    });

    if (upstream.status === 429) {
      return res.status(429).json({
        error: "rate_limited",
        message: "GIF service rate limit reached. Please retry shortly.",
      });
    }

    if (!upstream.ok) {
      return res.status(502).json({
        error: "upstream_error",
        message: `GIF upstream service returned HTTP ${upstream.status}.`,
      });
    }

    const json = await upstream.json();
    const payload = normalizeKlipyResponse(json, page, perPage);
    return res.json(payload);
  } catch {
    return res.status(504).json({
      error: "upstream_timeout",
      message: "Timed out connecting to GIF service.",
    });
  }
}

async function createServer() {
  const app = express();
  app.set("trust proxy", 1);

  app.get("/api/health", (_req, res) => {
    res.json({
      ok: true,
      gifProvider: "klipy",
      configured: Boolean((process.env.KLIPY_API_KEY ?? "").trim()),
    });
  });

  app.get("/api/gifs/trending", (req, res) => void handleKlipyProxy("trending", req, res));
  app.get("/api/gifs/search", (req, res) => void handleKlipyProxy("search", req, res));

  if (process.env.NODE_ENV === "production") {
    const distDir = path.join(__dirname, "dist");
    app.use(express.static(distDir));
    app.get("*", (_req, res) => {
      res.sendFile(path.join(distDir, "index.html"));
    });
  } else {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`ClipMax companion & GIF proxy listening on port ${PORT}`);
  });
}

void createServer();
