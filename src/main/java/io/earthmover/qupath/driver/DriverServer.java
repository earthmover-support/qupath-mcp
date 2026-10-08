package io.earthmover.qupath.driver;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Loopback-only HTTP server: the MCP endpoint at {@code /mcp}, plus {@code /groovy}, {@code /windows} and
 * {@code /snapshot} for scripts that are not MCP clients. Runs arbitrary code, so every request is checked against the
 * loopback Origin and Host first.
 */
class DriverServer {

    private static final Logger logger = LoggerFactory.getLogger(DriverServer.class);

    private final GuiDriver driver;
    private final int port;
    private Server server;

    DriverServer(GuiDriver driver, int port) {
        this.driver = driver;
        this.port = port;
    }

    record Evaluation(boolean ok, String result, String output, String error) {}

    void start() throws Exception {
        // The SDK finds its JSON mapper with ServiceLoader on the context class loader, which is not the extension's.
        var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(DriverServer.class.getClassLoader());
        try {
            listen();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void listen() throws Exception {
        // A page in the user's browser can POST to loopback (foreign Origin) or rebind its own hostname to 127.0.0.1
        // (foreign Host); neither is a local client such as curl or an MCP client.
        var security = DefaultServerTransportSecurityValidator.builder()
                .allowedOrigins(List.of("http://127.0.0.1:" + port, "http://localhost:" + port))
                .allowedHosts(List.of("127.0.0.1:" + port, "localhost:" + port))
                .build();
        Filter guard = (req, res, chain) -> {
            var http = (HttpServletRequest) req;
            var headers = Collections.list(http.getHeaderNames()).stream()
                    .collect(Collectors.toMap(n -> n, n -> List.copyOf(Collections.list(http.getHeaders(n)))));
            try {
                security.validateHeaders(headers);
            } catch (ServerTransportSecurityException e) {
                ((HttpServletResponse) res).sendError(e.getStatusCode(), e.getMessage());
                return;
            }
            chain.doFilter(req, res);
        };
        // The SDK's error replies embed the exception text and stack, which a client has no use for.
        Filter shortErrors = (req, res, chain) -> {
            var wrapped = new ShortErrors((HttpServletResponse) res);
            chain.doFilter(req, wrapped);
            if (wrapped.status >= 400) {
                logger.debug("HTTP {} reply replaced; original body: {}", wrapped.status, wrapped.dropped);
                res.setContentType("application/json");
                res.getWriter().write("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":%s}}".formatted(
                        quote(HttpStatus.getMessage(wrapped.status))));
            }
        };
        var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp")
                .securityValidator(security).build();
        new Mcp(driver, this).build(transport);

        var context = new ServletContextHandler();
        context.setClassLoader(DriverServer.class.getClassLoader());
        context.addFilter(new FilterHolder(guard), "/*", null);
        context.addFilter(new FilterHolder(shortErrors), "/mcp", null);
        context.addServlet(new ServletHolder(transport), "/mcp");
        var errors = new ErrorHandler();
        errors.setShowStacks(false);
        errors.setShowServlet(false);
        context.setErrorHandler(errors);
        context.addServlet(new ServletHolder(new Endpoints()), "/*");

        // Daemon threads so a running server never keeps QuPath from exiting.
        var threads = new QueuedThreadPool();
        threads.setDaemon(true);
        threads.setName("qupath-mcp-http");
        server = new Server(threads);
        var connector = new ServerConnector(server);
        connector.setHost(InetAddress.getLoopbackAddress().getHostAddress());
        connector.setPort(port);
        server.addConnector(connector);
        // Lets a request in flight, such as the one that switches the server off, finish its reply before connections close.
        var stats = new StatisticsHandler();
        stats.setHandler(context);
        server.setHandler(stats);
        server.setStopTimeout(5000);
        server.start();
        logger.info("MCP server on http://127.0.0.1:{}/mcp", port);
    }

    void stop() throws Exception {
        server.stop();
        logger.info("MCP server stopped");
    }

    /** Keeps the status of an error reply but sends its body to {@code dropped} instead of the client. */
    private static class ShortErrors extends HttpServletResponseWrapper {
        int status = 200;
        final StringWriter dropped = new StringWriter();

        ShortErrors(HttpServletResponse res) {
            super(res);
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) {
            setStatus(sc);
        }

        @Override
        public void sendError(int sc, String msg) {
            setStatus(sc);
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            return status >= 400 ? new PrintWriter(dropped) : super.getWriter();
        }
    }

    private class Endpoints extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
            logger.info("{} {}", req.getMethod(), req.getRequestURI());
            try {
                switch (req.getRequestURI()) {
                    case "/groovy" -> {
                        var code = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        var timeout = req.getParameter("timeout");
                        var r = evaluate(code, timeout == null ? 60 : Double.parseDouble(timeout));
                        send(res, 200, "application/json", "{\"ok\":%b,\"result\":%s,\"output\":%s,\"error\":%s}".formatted(
                                r.ok(), quote(r.result()), quote(r.output()), r.error() == null ? "null" : quote(r.error())));
                    }
                    case "/windows" -> send(res, 200, "application/json", driver.windowsJson());
                    case "/snapshot" -> {
                        var png = driver.png(req.getParameter("window"), 0);
                        if (png == null)
                            send(res, 404, "text/plain", "No such window");
                        else
                            send(res, 200, "image/png", png);
                    }
                    default -> send(res, 404, "text/plain", "Unknown path " + req.getRequestURI());
                }
            } catch (Exception e) {
                logger.error("Request failed", e);
                send(res, 500, "text/plain", e.toString());
            }
        }

        private void send(HttpServletResponse res, int status, String type, String body) throws IOException {
            send(res, status, type, body.getBytes(StandardCharsets.UTF_8));
        }

        private void send(HttpServletResponse res, int status, String type, byte[] body) throws IOException {
            res.setStatus(status);
            res.setContentType(type);
            res.getOutputStream().write(body);
        }
    }

    /** Evaluates Groovy on its own thread so a hung script times out instead of holding the connection. */
    Evaluation evaluate(String code, double timeout) {
        var output = new StringWriter();
        var out = new PrintWriter(output, true);
        var result = new CompletableFuture<Object>();
        var worker = new Thread(() -> {
            try {
                result.complete(driver.evaluate(code, out));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, "qupath-gui-driver-eval");
        worker.setDaemon(true);
        worker.start();

        String value = "null", error = null;
        try {
            value = String.valueOf(result.get((long) (timeout * 1000), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            worker.interrupt();
            error = "Timed out after " + timeout + "s";
        } catch (InterruptedException | ExecutionException e) {
            var trace = new StringWriter();
            (e instanceof ExecutionException ? e.getCause() : e).printStackTrace(new PrintWriter(trace));
            error = trace.toString();
        }
        out.flush();
        return new Evaluation(error == null, value, output.toString(), error);
    }

    static String quote(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 ? "\\u%04x".formatted((int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }
}
