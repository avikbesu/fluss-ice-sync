/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Standalone SPA -- no BFF (see README's "why no backend-for-frontend"),
// so there's no dev-time API proxy the way app/ui/web has one; this build
// talks to trino-nl-api/trino-nl-config-service base URLs directly (see
// src/config/settings.ts), configured via VITE_* env vars at build time.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
