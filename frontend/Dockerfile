# ---- Build the SPA ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Public, build-time settings only (they end up in the bundle). Never pass secrets here.
ARG VITE_API_BASE_URL=/api
ARG VITE_GOOGLE_LOGIN=false
ARG VITE_DEMO_MODE=false
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL VITE_GOOGLE_LOGIN=$VITE_GOOGLE_LOGIN VITE_DEMO_MODE=$VITE_DEMO_MODE
RUN npm run build

# ---- Serve with unprivileged nginx; /api is proxied to the backend (same-site cookies, no CORS) ----
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY docker/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV BACKEND_URL=http://backend:8080 \
    NGINX_ENVSUBST_FILTER=^BACKEND_URL$
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
