package decompile;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

final class DecompilerAttemptRunner {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, new DaemonThreadFactory());

    private DecompilerAttemptRunner() {
    }

    static <T> T run(Callable<T> callable, long timeoutMillis) throws Exception {
        if (timeoutMillis == 0) {
            return callable.call();
        }
        Future<T> future = EXECUTOR.submit(callable);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AttemptTimeoutException(timeoutMillis);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    static final class AttemptTimeoutException extends IOException {
        AttemptTimeoutException(long timeoutMillis) {
            super("Decompiler attempt timed out after " + timeoutMillis + " ms");
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jd-mcp-duo-decompiler-attempt-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
