package decompile;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;

final class ThreadLocalStderrSilencer {
    private static final Object INSTALL_LOCK = new Object();
    private static final ThreadLocal<Boolean> SILENCED = ThreadLocal.withInitial(() -> false);
    private static volatile PrintStream delegate;

    private ThreadLocalStderrSilencer() {
    }

    static <T> T callSilenced(Callable<T> callable) throws Exception {
        installIfNeeded();
        boolean previous = SILENCED.get();
        SILENCED.set(true);
        try {
            return callable.call();
        } finally {
            SILENCED.set(previous);
        }
    }

    private static void installIfNeeded() {
        if (System.err instanceof DelegatingPrintStream) {
            return;
        }
        synchronized (INSTALL_LOCK) {
            if (!(System.err instanceof DelegatingPrintStream)) {
                delegate = System.err;
                System.setErr(new DelegatingPrintStream());
            }
        }
    }

    private static PrintStream target() {
        return Boolean.TRUE.equals(SILENCED.get()) ? NULL_PRINT_STREAM : delegate;
    }

    private static final PrintStream NULL_PRINT_STREAM = new PrintStream(OutputStream.nullOutputStream());

    private static final class DelegatingPrintStream extends PrintStream {
        private DelegatingPrintStream() {
            super(OutputStream.nullOutputStream());
        }

        @Override
        public void write(int b) {
            target().write(b);
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            target().write(buf, off, len);
        }

        @Override
        public void flush() {
            target().flush();
        }

        @Override
        public void close() {
            flush();
        }
    }
}
