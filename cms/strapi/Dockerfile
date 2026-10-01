# Strapi 5 – Build-Kontext ist cms/ (enthält strapi/ und content/)
FROM node:22-alpine AS build
WORKDIR /app/strapi
COPY strapi/package.json strapi/package-lock.json ./
RUN npm ci
COPY strapi/ ./
ENV NODE_ENV=production
RUN npm run build

FROM node:22-alpine
WORKDIR /app/strapi
ENV NODE_ENV=production CONTENT_DIR=/app/content STRAPI_TELEMETRY_DISABLED=true
COPY --from=build /app/strapi ./
COPY content/ /app/content/
EXPOSE 1337
CMD ["npm", "run", "start"]
