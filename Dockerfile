# syntax=docker/dockerfile:1.26.0@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM ghcr.io/pnpm/pnpm:11.24.0@sha256:f18a4dfbfd23931624a2396829ca921c7c262bf63fd4fa55af07654a7d41834e AS build

RUN pnpm runtime set node 24.20.0 --global

WORKDIR /workspace

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY mcp-server/package.json mcp-server/package.json

# Keep the dependency cache separate from pnpm's managed Node runtime.
RUN --mount=type=cache,id=dirt-pnpm,target=/pnpm/project-store \
    pnpm install --frozen-lockfile --store-dir /pnpm/project-store

COPY mcp-server/ mcp-server/

RUN pnpm build && \
    pnpm --filter @dirt-mcp/server deploy --prod /opt/dirt

FROM node:24.20.0-trixie-slim@sha256:50c3b2f6988dfc307b86e5301d69611af31f4789bdf232863b07d3b02fe55ae0 AS runtime

ENV NODE_ENV=production

WORKDIR /app

COPY --from=build --chown=node:node /opt/dirt/ ./
COPY --chown=node:node LICENSE ./LICENSE

RUN mkdir --parents /var/lib/dirt-mcp && chown node:node /var/lib/dirt-mcp

USER node

ENTRYPOINT ["node"]
CMD ["dist/index.js"]
