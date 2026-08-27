import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Dev-time proxy only -- in production the BFF serves this build's
// dist/ directly, same-origin, so there's nothing to proxy (see
// app/ui/bff/src/server.ts).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:8080",
    },
  },
});
