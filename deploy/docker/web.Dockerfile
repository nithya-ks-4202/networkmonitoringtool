# ---------------------------------------------------------------------------
# Web interface.
#
# The build produces static files; nginx serves them and proxies /api to the
# server. Both behind one origin, which is what makes CORS a development-only
# concern rather than a production one.
# ---------------------------------------------------------------------------

FROM node:22-alpine AS build
WORKDIR /build

# npm ci needs both files and installs exactly what the lockfile pins, so a
# deploy cannot quietly pick up a different dependency tree than was tested.
COPY web/package.json web/package-lock.json* ./
RUN npm ci || npm install

COPY web/ .
RUN npm run build

# ---------------------------------------------------------------------------

FROM nginx:1.27-alpine

COPY --from=build /build/dist /usr/share/nginx/html

# Placed in templates/ rather than conf.d/: the nginx image runs envsubst over
# everything here at startup, which is what lets the API address be set by
# environment variable. A file dropped straight into conf.d/ is used verbatim,
# and ${NMS_SERVER_HOST} would reach nginx as a literal hostname.
COPY deploy/docker/nginx.conf.template /etc/nginx/templates/default.conf.template

# Only these two are substituted. Listing them explicitly stops envsubst
# mangling nginx's own $host, $remote_addr and friends, which share the syntax.
ENV NGINX_ENVSUBST_FILTER='NMS_SERVER_' \
    NMS_SERVER_HOST=nms-server \
    NMS_SERVER_PORT=8080

# The master process stays root so it can render the template and bind; nginx
# drops its workers to the nginx user by itself, which is the image's own model.
EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/ || exit 1
