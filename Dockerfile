# syntax=docker/dockerfile:1

FROM node:26.8.1-trixie AS build

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

FROM node:26.8.1-trixie-slim AS runtime

ENV NODE_ENV=production

WORKDIR /app

COPY --from=build --chown=node:node /opt/dirt/ ./

RUN mkdir --parents /var/lib/dirt-mcp && chown node:node /var/lib/dirt-mcp

USER node

STOPSIGNAL SIGTERM

ENTRYPOINT ["node"]
CMD ["dist/index.js"]
