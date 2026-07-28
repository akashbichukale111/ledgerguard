# Multi-stage build: Node.js build stage, Nginx runtime stage
FROM node:20-alpine AS build

WORKDIR /build

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm ci

# Copy source code
COPY . .

# Build production bundle
RUN npm run build

# Runtime stage: Nginx serving static SPA
FROM nginx:1.27-alpine

# Security: Remove default Nginx config
RUN rm /etc/nginx/conf.d/default.conf

# Copy custom Nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built React app from build stage
COPY --from=build /build/dist /usr/share/nginx/html

# Security: Create unprivileged user and set permissions
RUN chown -R nginx:nginx /usr/share/nginx/html && \
    chmod -R 755 /usr/share/nginx/html

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O- http://localhost:80/index.html || exit 1

# Expose port (documentation only)
EXPOSE 80

# Run as nginx user (already unprivileged)
USER nginx

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
