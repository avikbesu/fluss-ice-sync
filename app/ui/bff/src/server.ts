import express from "express";
import * as fs from "fs";
import * as path from "path";
import { loadConfig } from "./config";
import { chatRouter } from "./routes/chat";
import { configRouter } from "./routes/config";
import { queryRouter } from "./routes/query";
import { schemasRouter } from "./routes/schemas";

const config = loadConfig();
const app = express();

app.use(express.json());

app.use("/api", configRouter(config));
app.use("/api", schemasRouter(config));
app.use("/api", queryRouter(config));
if (config.chat.enabled) {
  app.use("/api", chatRouter(config));
}

// Custom branding (a logo mounted at /branding, see docker-compose.app.yml's
// fluss-ice-sync-ui volumes) -- served only if actually mounted, so a plain
// `npm run dev` with no /branding directory doesn't crash on startup.
const BRANDING_DIR = "/branding";
if (fs.existsSync(BRANDING_DIR)) {
  app.use("/branding", express.static(BRANDING_DIR));
}

// Built React SPA -- see config/docker/ui/Dockerfile for how web/dist gets
// here. Same-origin with /api/*, so the browser never needs CORS config
// and never sees a Trino credential (see doc/design/v2-web-ui-design.md).
const staticDir = path.join(__dirname, "..", "web-dist");
app.use(express.static(staticDir));
app.get("*", (_req, res) => {
  res.sendFile(path.join(staticDir, "index.html"));
});

app.listen(config.server.port, () => {
  console.log(`fluss-ice-sync-ui listening on :${config.server.port}`);
});
