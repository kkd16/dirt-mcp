# syntax=docker/dockerfile:1.26.0@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM node:24.20.0-trixie@sha256:f7d34e58713740f9eef9092c0bd6ff10369d132f7238399a4b270f16d47fa608 AS build

ENV PNPM_HOME=/pnpm
ENV PATH=${PNPM_HOME}:${PATH}

RUN npm install --global pnpm@11.24.0

WORKDIR /workspace

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY mcp-server/package.json mcp-server/package.json

RUN --mount=type=cache,id=dirt-pnpm,target=/pnpm/store \
    pnpm config set store-dir /pnpm/store && \
    pnpm install --frozen-lockfile

COPY mcp-server/ mcp-server/

RUN pnpm --filter @dirt-mcp/server run build && \
    pnpm --filter @dirt-mcp/server deploy --prod /opt/dirt

FROM node:24.20.0-trixie-slim@sha256:50c3b2f6988dfc307b86e5301d69611af31f4789bdf232863b07d3b02fe55ae0 AS runtime

ENV NODE_ENV=production

WORKDIR /app

COPY --from=build --chown=node:node /opt/dirt/ ./
COPY --chown=node:node LICENSE ./LICENSE

RUN mkdir --parents /var/lib/dirt-mcp && chown node:node /var/lib/dirt-mcp

USER node

STOPSIGNAL SIGTERM

ENTRYPOINT ["node"]
CMD ["dist/index.js"]
