package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

final class UndoChunkLoader {
    private final MainThread mainThread;
    private final ChunkTicketManager tickets;

    UndoChunkLoader(MainThread mainThread, ChunkTicketManager tickets) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
    }

    ChunkTicketManager.Lease prepare(UndoWorld world, List<ChunkPosition> chunks)
            throws OperationException {
        Objects.requireNonNull(world, "world");
        List<ChunkPosition> requiredChunks = List.copyOf(chunks);
        if (requiredChunks.isEmpty()) {
            throw new IllegalArgumentException("Undo must retain at least one touched chunk");
        }

        List<CompletableFuture<Boolean>> loads =
                onMainThread(
                        () -> {
                            requireAvailable(world);
                            List<CompletableFuture<Boolean>> requested =
                                    new ArrayList<>(requiredChunks.size());
                            for (ChunkPosition chunk : requiredChunks) {
                                requested.add(
                                        Objects.requireNonNull(
                                                world.loadExistingChunk(chunk),
                                                "chunk load future"));
                            }
                            return List.copyOf(requested);
                        });
        await(world, requiredChunks, loads);
        return onMainThread(
                () -> {
                    requireAvailable(world);
                    return this.tickets.acquire(world, requiredChunks, "Undo operation");
                });
    }

    private static void await(
            UndoWorld world, List<ChunkPosition> chunks, List<CompletableFuture<Boolean>> loads)
            throws OperationException {
        for (int index = 0; index < loads.size(); index++) {
            CompletableFuture<Boolean> load = loads.get(index);
            ChunkPosition chunk = chunks.get(index);
            try {
                if (!Boolean.TRUE.equals(load.get())) {
                    cancel(loads);
                    throw unavailableChunk(world, chunk, null);
                }
            } catch (InterruptedException exception) {
                cancel(loads);
                Thread.currentThread().interrupt();
                throw new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        "Undo chunk loading was interrupted in world: " + world.name(),
                        new ErrorDetails.WorldUnavailable.Interrupted(),
                        exception);
            } catch (CancellationException exception) {
                cancel(loads);
                throw unavailableChunk(world, chunk, exception);
            } catch (ExecutionException exception) {
                cancel(loads);
                throw unavailableChunk(world, chunk, exception.getCause());
            }
        }
    }

    private <T> T onMainThread(MainThread.CheckedSupplier<T> action) throws OperationException {
        try {
            return this.mainThread.call(action);
        } catch (PaperMainThreadException exception) {
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
            }
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "Could not prepare chunks for undo on Paper's main thread",
                    new ErrorDetails.WorldUnavailable.PaperUnavailable(),
                    exception);
        }
    }

    private static void requireAvailable(UndoWorld world) throws OperationException {
        if (!world.isAvailable()) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "World is no longer available: " + world.name(),
                    new ErrorDetails.WorldUnavailable.WorldUnloaded(world.name()));
        }
    }

    private static OperationException unavailableChunk(
            UndoWorld world, ChunkPosition chunk, Throwable cause) {
        String message =
                "Undo requires an existing chunk at "
                        + chunk.x()
                        + ","
                        + chunk.z()
                        + " in world: "
                        + world.name();
        return cause == null
                ? new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        message,
                        new ErrorDetails.WorldUnavailable.ChunkLoadFailed(
                                world.name(), new ErrorDetails.Chunk(chunk.x(), chunk.z())))
                : new OperationException(
                        OperationFailure.WORLD_UNAVAILABLE,
                        message,
                        new ErrorDetails.WorldUnavailable.ChunkLoadFailed(
                                world.name(), new ErrorDetails.Chunk(chunk.x(), chunk.z())),
                        cause);
    }

    private static void cancel(List<CompletableFuture<Boolean>> loads) {
        for (CompletableFuture<Boolean> load : loads) {
            load.cancel(false);
        }
    }

    interface UndoWorld extends ChunkTicketManager.TicketWorld {
        String name();

        boolean isAvailable();

        CompletableFuture<Boolean> loadExistingChunk(ChunkPosition chunk);
    }
}
