# Dirt MCP TODO

This list tracks the remaining work needed to make the v1 source implementation
consistently documented, thoroughly verified, and ready for release. Items are
ordered by priority.

## 1. Expand Paper and FAWE integration coverage

- Cover per-world locking and `world_busy` behavior.
- Cover bounded and disabled history plus multi-entry undo ordering.
- Verify live authentication failures and change-limit rejection before mutation.
- Verify edit-session and chunk-ticket cleanup on success, failure, interruption,
  and plugin shutdown.

## 2. Strengthen MCP bridge failure coverage

- Add a deterministic request-timeout test without materially slowing the
  offline suite.

## 3. Enforce contract consistency

- Add checks that keep Java endpoints and error envelopes, OpenAPI schemas, and
  TypeScript/Zod schemas synchronized.
- Fail CI when implemented behavior is added to only one layer.

## 4. Wire up releases

- Establish one non-snapshot version shared by the Paper plugin, bridge contract,
  and MCP package.
- Publish the Paper JAR through GitHub Releases with checksums.
- Make `@dirt-mcp/server` publishable and publish the matching registry package.
- Add a release workflow that builds, tests, inspects, and publishes artifacts.
- Verify the documented clean installation flow using only published artifacts.

## Ranked future capabilities (post-v1)

These capabilities require an explicit product-scope decision and are not v1
release blockers. They are ranked by their expected effect on an agent's
ability to understand the live world, plan safely, and construct efficiently.

1. Add `get_player_context` for a player's position, orientation, dimension,
   and bounded surroundings.
2. Add `render_region` for a bounded isometric or perspective image of an
   already-loaded region.
3. Add `preview_edit` to render a proposed mutation as a non-mutating overlay
   using the same operation inputs that execution would consume.
4. Add `extrude_profile` for turning a two-dimensional outline into walls,
   vaults, roofs, and other repeated cross-sections.
5. Add `repeat_region` for evenly spaced bays, columns, windows, and decorative
   modules.
6. Add `mirror_region` and `rotate_region` for symmetric construction.
7. Add bounded shape primitives for cylinders, ellipses, arches, domes, cones,
   and polygons.
8. Add `set_block_runs` for compact batches of mixed-material axis-aligned
   spans.
9. Add `hollow_region` for bounded walls, floors, and ceilings without filling
   the enclosed volume.
10. Add `inspect_surroundings` to return a compact combined description of
    nearby terrain, structures, entities, and player context.
11. Add `find_blocks` to search a bounded loaded region using block-state
    patterns.
12. Add `find_entities` to locate nearby entities by type within explicit
    bounds.
13. Add `locate_structure` to find known structures without loading or
    generating terrain.
14. Add `copy_region` for duplicating an existing bounded part of the live
    world.
15. Keep hot-reloaded MCP tool metadata synchronized with the active bridge
    contract so newly available operations and schemas are immediately
    accurate.
16. Prefer compact runs and primitives over raising request-size limits when
    both can express the same build.

Treat inspection and rendering as a separate read-only capability from world
mutation and operator-level command dispatch. Keep every inspection bounded,
restrict it to already-loaded chunks, and exclude inventories and block-entity
data unless an operator explicitly enables them.
