package com.leucine.pdfdemo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PdfHttpServer {

    private final int port;
    private HttpServer server;
    private Playwright playwright;
    private Browser browser;

    public PdfHttpServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        // Read token once at startup
        String token = TokenManager.getOrRefreshToken();
        if (token == null) {
            throw new IllegalStateException(
                "Could not obtain a valid token. Run with --login first.");
        }

        // Launch a single Playwright browser instance (reused across all requests)
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/pdf", exchange -> handlePdf(exchange, token));
        server.setExecutor(null); // default single-threaded executor
        server.start();

        System.out.printf("%n  Server running on http://localhost:%d%n", port);
        System.out.println("  Endpoint: GET /pdf?jobId={jobId}");
        System.out.println("  Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    private void handlePdf(HttpExchange exchange, String token) {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String jobId = params.get("jobId");
            if (jobId == null || jobId.isBlank()) {
                sendError(exchange, 400, "Missing required parameter: jobId");
                return;
            }

            System.out.printf("  [%s] GET /pdf?jobId=%s%n",
                java.time.LocalTime.now().toString().substring(0, 8), jobId);

            long start = System.currentTimeMillis();

            // Fetch HTML from API
            String html = LocalPdfRenderer.fetchAndBuildHtml(token, jobId);
            if (html == null) {
                sendError(exchange, 502, "Failed to fetch job data from API");
                return;
            }

            // Render PDF using a fresh page from the shared browser
            byte[] pdf;
            BrowserContext context = browser.newContext();
            try {
                Page page = context.newPage();
                try {
                    pdf = LocalPdfRenderer.renderPdfBytes(page, html);
                } finally {
                    page.close();
                }
            } finally {
                context.close();
            }

            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("  [%s] OK — %,.1f KB in %,d ms%n",
                java.time.LocalTime.now().toString().substring(0, 8),
                pdf.length / 1024.0, elapsed);

            // Return PDF
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.getResponseHeaders().set("Content-Disposition",
                "inline; filename=\"report-" + jobId + ".pdf\"");
            exchange.sendResponseHeaders(200, pdf.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(pdf);
            }

        } catch (Exception e) {
            System.err.printf("  ERROR: %s%n", e.getMessage());
            try {
                sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
            } catch (IOException ignored) {
            }
        }
    }

    private void stop() {
        System.out.println("\n  Shutting down...");
        if (server != null) server.stop(0);
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }
}
