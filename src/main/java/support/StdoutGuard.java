package support;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;

public final class StdoutGuard {
    private static final Object INSTALL_LOCK = new Object();
    private static final Object ROUTE_LOCK = new Object();
    private static volatile PrintStream delegate;
    private static volatile PrintStream defaultRoute;
    private static volatile PrintStream route;

    private StdoutGuard() {
    }

    public static PrintStream originalStdout() {
        installIfNeeded();
        return delegate;
    }

    public static <T> T callSilenced(Callable<T> callable) throws Exception {
        return callRouted(NULL_PRINT_STREAM, callable);
    }

    public static Scope silenceByDefault() {
        installIfNeeded();
        synchronized (ROUTE_LOCK) {
            PrintStream previous = defaultRoute;
            defaultRoute = NULL_PRINT_STREAM;
            return () -> {
                synchronized (ROUTE_LOCK) {
                    defaultRoute = previous;
                }
            };
        }
    }

    private static <T> T callRouted(PrintStream target, Callable<T> callable) throws Exception {
        installIfNeeded();
        synchronized (ROUTE_LOCK) {
            PrintStream previous = route;
            route = target;
            try {
                return callable.call();
            } finally {
                route = previous;
            }
        }
    }

    private static void installIfNeeded() {
        if (System.out instanceof DelegatingPrintStream) {
            return;
        }
        synchronized (INSTALL_LOCK) {
            if (!(System.out instanceof DelegatingPrintStream)) {
                delegate = System.out;
                System.setOut(new DelegatingPrintStream());
            }
        }
    }

    private static PrintStream target() {
        PrintStream currentRoute = route;
        if (currentRoute != null) {
            return currentRoute;
        }
        PrintStream currentDefaultRoute = defaultRoute;
        return currentDefaultRoute == null ? delegate : currentDefaultRoute;
    }

    private static final PrintStream NULL_PRINT_STREAM = new PrintStream(OutputStream.nullOutputStream());

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

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
