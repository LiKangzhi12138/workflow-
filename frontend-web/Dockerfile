FROM node:22-alpine AS builder

WORKDIR /build/frontend-web

COPY frontend-web/package.json frontend-web/package-lock.json ./
RUN npm ci

COPY frontend-web/ ./

ARG VITE_API_BASE_URL=/api
ARG VITE_API_TIMEOUT_MS=600000

ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_API_TIMEOUT_MS=${VITE_API_TIMEOUT_MS}

RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=builder /build/frontend-web/dist /usr/share/nginx/html

EXPOSE 80
