package ca.deliyannides.dirtmcp.paper.platform;

public interface MainThread extends AutoCloseable {
    <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException;

    default void run(CheckedRunnable action) throws PaperMainThreadException {
        call(
                () -> {
                    action.run();
                    return null;
                });
    }

    @Override
    void close();

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface CheckedRunnable {
        void run() throws Exception;
    }
}
