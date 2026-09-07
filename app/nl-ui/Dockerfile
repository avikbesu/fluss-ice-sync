# Standalone static SPA -- no BFF (see README), so there's no Node runtime
# stage the way app/ui/Dockerfile has one for its BFF; nginx just serves
# the built dist/ directly. Build context is app/nl-ui/ itself (see
# docker-compose.app.yml), matching app/ui/Dockerfile's precedent of a
# self-contained Node build with no dependency on the rest of the repo.

FROM node:22-alpine AS build
WORKDIR /web
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
# VITE_* values are baked into the static bundle at build time (Vite has
# no runtime env-var mechanism for a plain static build) -- see
# docker-compose.app.yml's build args for how this repo sets them.
ARG VITE_NL_API_BASE_URL
ARG VITE_NL_CONFIG_SERVICE_BASE_URL
ENV VITE_NL_API_BASE_URL=${VITE_NL_API_BASE_URL}
ENV VITE_NL_CONFIG_SERVICE_BASE_URL=${VITE_NL_CONFIG_SERVICE_BASE_URL}
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /web/dist /usr/share/nginx/html
# A plain SPA needs unknown paths to fall back to index.html (client-side
# routing isn't used today -- this is just two tabs in one page -- but a
# stock nginx image 404s on a hard refresh of anything but "/" otherwise,
# which would bite the moment routing is added).
RUN printf 'server { listen 8080; location / { root /usr/share/nginx/html; try_files $uri /index.html; } }' \
    > /etc/nginx/conf.d/default.conf
EXPOSE 8080
