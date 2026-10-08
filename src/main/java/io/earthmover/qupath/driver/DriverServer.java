package io.earthmover.qupath.driver;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loopback-only HTTP endpoint that evaluates Groovy and serves window screenshots. Runs arbitrary code, so it is opt-in.
 * QuPath's bundled runtime lacks jdk.httpserver, hence the minimal hand-rolled HTTP/1.1 handling.
 */
class DriverServer {

    private static final Logger logger = LoggerFactory.getLogger(DriverServer.class);

    private final GuiDriver driver;
    private final int port;

    DriverServer(GuiDriver driver, int port) {
        this.driver = driver;
        this.port = port;
    }

    private record Request(String method, String path, Map<String, String> query, String body) {}

    private record Response(int status, String type, byte[] body) {
        Response(int status, String type, String body) {
            this(status, type, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    void start() throws IOException {
        var server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        var acceptor = new Thread(() -> {
            while (true) {
                try {
                    var socket = server.accept();
                    var t = new Thread(() -> serve(socket), "qupath-gui-driver-http");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    logger.error("Accept failed", e);
                    return;
                }
            }
        }, "qupath-gui-driver-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        logger.info("Driver HTTP server listening on 127.0.0.1:{}", port);
    }

    private void serve(Socket socket) {
        try (socket) {
            var in = new BufferedInputStream(socket.getInputStream());
            var requestLine = readLine(in).split(" ");
            int length = 0;
            boolean trusted = true;
            for (String h; !(h = readLine(in)).isEmpty(); ) {
                h = h.toLowerCase();
                if (h.startsWith("content-length:"))
                    length = Integer.parseInt(h.substring(15).trim());
                // A page in the user's browser can POST to loopback (Origin set) or rebind its own hostname to
                // 127.0.0.1 (Host wrong); neither is a local client such as curl or the MCP server.
                else if (h.startsWith("origin:") || h.startsWith("host:") && !h.matches("host:\\s*(127\\.0\\.0\\.1|localhost)(:\\d+)?"))
                    trusted = false;
            }
            var body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            var uri = URI.create(requestLine[1]);
            logger.info("{} {}", requestLine[0], requestLine[1]);

            Response r;
            try {
                if (!trusted)
                    r = new Response(403, "text/plain", "Forbidden");
                else
                    r = route(new Request(requestLine[0], uri.getPath(), query(uri.getRawQuery()), body));
            } catch (Throwable t) {
                logger.error("Request failed", t);
                r = new Response(500, "text/plain", t.toString());
            }
            var head = "HTTP/1.1 %d X\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n"
                    .formatted(r.status(), r.type(), r.body().length);
            var out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(r.body());
            out.flush();
        } catch (Exception e) {
            logger.error("Connection failed", e);
        }
    }

    private static String readLine(BufferedInputStream in) throws IOException {
        var sb = new StringBuilder();
        for (int c; (c = in.read()) != -1 && c != '\n'; )
            if (c != '\r')
                sb.append((char) c);
        return sb.toString();
    }

    private Response route(Request req) throws Exception {
        return switch (req.path()) {
            case "/groovy" -> groovy(req);
            case "/windows" -> new Response(200, "application/json", driver.windowsJson());
            case "/snapshot" -> {
                var png = driver.png(req.query().get("window"));
                yield png == null ? new Response(404, "text/plain", "No such window") : new Response(200, "image/png", png);
            }
            default -> new Response(404, "text/plain", "Unknown path " + req.path());
        };
    }

    private Response groovy(Request req) throws Exception {
        var code = req.body();
        double timeout = Double.parseDouble(req.query().getOrDefault("timeout", "60"));
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
        boolean ok = true;
        try {
            value = String.valueOf(result.get((long) (timeout * 1000), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            worker.interrupt();
            ok = false;
            error = "Timed out after " + timeout + "s";
        } catch (ExecutionException e) {
            ok = false;
            var trace = new StringWriter();
            e.getCause().printStackTrace(new PrintWriter(trace));
            error = trace.toString();
        }
        out.flush();
        return new Response(200, "application/json", "{\"ok\":%b,\"result\":%s,\"output\":%s,\"error\":%s}".formatted(
                ok, quote(value), quote(output.toString()), error == null ? "null" : quote(error)));
    }

    private static Map<String, String> query(String q) {
        if (q == null || q.isEmpty())
            return Map.of();
        return Arrays.stream(q.split("&")).map(kv -> kv.split("=", 2)).collect(Collectors.toMap(
                kv -> kv[0], kv -> kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : ""));
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
