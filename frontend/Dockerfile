# Build stage.
FROM node:20-alpine AS build

WORKDIR /build

# package-lock.json is required by `npm ci` and is committed for exactly this reason — an
# earlier revision called `npm ci` with no lockfile in the repository, which always fails.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# Runtime stage: static assets served by nginx.
FROM nginx:1.27-alpine

RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

RUN chown -R nginx:nginx /usr/share/nginx/html && chmod -R 555 /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O- http://localhost/ >/dev/null || exit 1

# The container is deliberately left on the stock nginx entrypoint: the master process needs
# root to bind :80 and write the pid file, and drops its workers to the unprivileged `nginx`
# user on its own. Forcing `USER nginx` here breaks startup. Running rootless would mean
# switching to nginxinc/nginx-unprivileged and an above-1024 port — deferred, see phase-16.
CMD ["nginx", "-g", "daemon off;"]
