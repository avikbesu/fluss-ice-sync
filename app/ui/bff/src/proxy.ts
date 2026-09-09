import { Router } from "express";

/**
 * Generic reverse proxy onto an internal service's REST API -- same trust
 * boundary as this bff's existing Trino proxying (routes/query.ts,
 * routes/schemas.ts), just forwarded verbatim rather than re-shaped. Used
 * to front nl-api and the config service so the SPA stays same-origin with
 * the bff instead of the browser calling them directly and hitting CORS --
 * see doc/design/v2-web-ui-design.md's "Ask and Config tabs" section.
 *
 * The caller mounts this at a path prefix (e.g. `app.use("/api/nl", ...)`);
 * Express strips that prefix from `req.url` for everything inside the
 * router, so the remaining path (e.g. `/api/v1/ask`) forwards straight onto
 * `baseUrl` unchanged, matching the target service's own real route shape.
 */
export function createProxyRouter(baseUrl: string): Router {
  const router = Router();

  router.all("*", async (req, res) => {
    const target = `${baseUrl}${req.url}`;
    const hasBody = req.method !== "GET" && req.method !== "HEAD" && req.body !== undefined;

    try {
      const upstream = await fetch(target, {
        method: req.method,
        headers: hasBody ? { "Content-Type": "application/json" } : undefined,
        body: hasBody ? JSON.stringify(req.body) : undefined,
      });

      const contentType = upstream.headers.get("content-type") ?? "";
      res.status(upstream.status);
      if (contentType.includes("application/json")) {
        res.json(await upstream.json().catch(() => ({})));
      } else {
        res.send(await upstream.text());
      }
    } catch (err) {
      res.status(502).json({ error: `Could not reach ${target}: ${(err as Error).message}` });
    }
  });

  return router;
}
