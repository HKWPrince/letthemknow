# ---- build stage ----
FROM node:20-alpine AS build
WORKDIR /build
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# Baked into the bundle at build time: Vite inlines VITE_* vars, so this cannot be set at runtime.
ARG VITE_PUBLIC_API_URL=""
ENV VITE_PUBLIC_API_URL=$VITE_PUBLIC_API_URL
RUN pnpm build

# ---- runtime stage ----
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
