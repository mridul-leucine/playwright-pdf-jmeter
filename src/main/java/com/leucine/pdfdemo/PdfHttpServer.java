package com.leucine.pdfdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PdfHttpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private HttpServer server;
    private Playwright playwright;
    private Browser browser;

    public PdfHttpServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        // Launch a single Playwright browser instance (reused across all requests)
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/pdf", this::handlePdf);
        server.setExecutor(null); // default single-threaded executor
        server.start();

        System.out.printf("%n  Server running on http://localhost:%d%n", port);
        System.out.println("  Endpoint: POST /pdf (JSON body + Authorization/facilityId headers)");
        System.out.println("  Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    private void handlePdf(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed — use POST");
                return;
            }

            // Read Authorization and facilityId from request headers
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            String facilityId = exchange.getRequestHeaders().getFirst("facilityId");
            if (token == null || token.isBlank()) {
                sendError(exchange, 400, "Missing required header: Authorization");
                return;
            }
            if (facilityId == null || facilityId.isBlank()) {
                sendError(exchange, 400, "Missing required header: facilityId");
                return;
            }

            // Read JSON body
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            if (bodyBytes.length == 0) {
                sendError(exchange, 400, "Empty request body — expected job JSON");
                return;
            }

            JsonNode root = MAPPER.readTree(bodyBytes);
            // Support both raw API response (with "data" wrapper) and direct job object
            JsonNode jobData = root.has("data") ? root.get("data") : root;

            String jobCode = jobData.path("code").asText("unknown");
            System.out.printf("  [%s] POST /pdf — job %s%n",
                java.time.LocalTime.now().toString().substring(0, 8), jobCode);

            long start = System.currentTimeMillis();

            // Build HTML from the provided JSON
            String html = LocalPdfRenderer.buildHtmlFromJson(jobData, token, facilityId);

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
                "inline; filename=\"report-" + jobCode + ".pdf\"");
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
}
