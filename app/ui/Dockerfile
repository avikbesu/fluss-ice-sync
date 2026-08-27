# Multi-stage build: compile the React SPA and the Node BFF separately,
# then ship only their build output in a slim runtime image -- mirrors
# app/sync/Dockerfile's "don't ship a JDK/build toolchain at runtime"
# convention from v0.
#
# Build context is app/ui/ itself (see docker-compose.app.yml), not the
# repo root, since this Dockerfile now lives alongside what it builds --
# paths below are relative to app/ui/, not the repo root.

FROM node:22-alpine AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM node:22-alpine AS bff-build
WORKDIR /bff
COPY bff/package.json bff/package-lock.json* ./
RUN npm ci
COPY bff/ ./
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY bff/package.json bff/package-lock.json* ./
RUN npm ci --omit=dev
COPY --from=bff-build /bff/dist ./dist
COPY --from=web-build /web/dist ./web-dist

EXPOSE 8080
CMD ["node", "dist/server.js"]
