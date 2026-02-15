package com.leucine.pdfdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;

public class LocalPdfRenderer {

    private static final int DOWNLOAD_TIMEOUT_SECONDS = 30;

    /**
     * Builds the full HTML report from pre-fetched job JSON data.
     * The token and facilityId are used only for downloading inline images.
     */
    static String buildHtmlFromJson(JsonNode jobData, String token, String facilityId) {
        String jobCode = jobData.path("code").asText("?");
        String state = jobData.path("state").asText("?");
        String checklistName = jobData.path("checklist").path("name").asText("-");
        String checklistCode = jobData.path("checklist").path("code").asText("-");

        return buildFullHtml(jobData, jobCode, state, checklistName, checklistCode, token, facilityId);
    }

    static byte[] renderPdfBytes(Page page, String html) {
        page.setContent(html, new Page.SetContentOptions()
            .setWaitUntil(WaitUntilState.NETWORKIDLE));

        // Wait for images to load
        page.evaluate("() => Promise.all("
            + "Array.from(document.images)"
            + ".filter(img => !img.complete)"
            + ".map(img => new Promise((resolve) => {"
            + "  img.onload = img.onerror = resolve;"
            + "}))"
            + ")");

        return page.pdf(new Page.PdfOptions()
            .setPreferCSSPageSize(true)
            .setPrintBackground(true)
            .setDisplayHeaderFooter(true)
            .setHeaderTemplate(buildHeaderTemplate())
            .setFooterTemplate(buildFooterTemplate())
            .setMargin(new Margin()
                .setTop("60px")
                .setBottom("38px")
                .setLeft("0")
                .setRight("0")));
    }

    private static String buildFullHtml(JsonNode jobData, String jobCode, String state,
                                         String checklistName, String checklistCode,
                                         String token, String facilityId) {
        StringBuilder content = new StringBuilder();

        // Process Details section
        content.append(sectionTitle("Process Details"));
        content.append("<div class=\"detail-panel\"><table class=\"detail-table\">");
        content.append(detailRow("Process ID", checklistCode));
        content.append(detailRow("Name", checklistName));

        // Checklist properties
        JsonNode properties = jobData.path("checklist").path("properties");
        if (properties.isArray()) {
            for (JsonNode prop : properties) {
                if (prop.path("archived").asBoolean(false)) continue;
                String label = prop.path("label").asText("");
                String value = prop.path("value").asText("-");
                if (!label.isEmpty()) {
                    content.append(detailRow(label, value));
                }
            }
        }
        content.append("</table></div>");

        // Job Details section
        content.append(sectionTitle("Job Details"));
        content.append("<div class=\"detail-panel\"><table class=\"detail-table\">");
        content.append(detailRow("Job ID", jobCode));
        content.append(detailRow("State", formatState(state)));

        String startedOn = formatTimestamp(jobData.path("startedAt"));
        content.append(detailRow("Job Started On", startedOn));
        content.append(detailRow("Job Started By", formatUser(jobData.path("startedBy"))));

        String completedOn = formatTimestamp(jobData.path("endedAt"));
        content.append(detailRow("Job Completed On", completedOn));
        content.append(detailRow("Job Completed By", formatUser(jobData.path("endedBy"))));

        // CJF / Job-level parameters
        JsonNode paramValues = jobData.path("parameterValues");
        if (paramValues.isArray()) {
            for (JsonNode p : paramValues) {
                String type = p.path("type").asText("");
                String label = p.path("label").asText("");
                if (label.isEmpty()) continue;

                if ("INSTRUCTION".equals(type)) {
                    String text = p.path("data").path("text").asText("");
                    if (text.startsWith("\"") && text.endsWith("\"")) {
                        text = text.substring(1, text.length() - 1);
                    }
                    content.append(detailRowRaw(label, text));
                } else {
                    JsonNode resp = p.path("response");
                    String value = "-";
                    if (resp.isArray() && resp.size() > 0) {
                        value = resp.get(0).path("value").asText("-");
                    }
                    content.append(detailRow(label, value));
                }
            }
        }

        long duration = jobData.path("totalDuration").asLong(0);
        content.append(detailRow("Job Duration", duration > 0 ? formatDuration(duration) : "-"));
        content.append("</table></div>");

        // Stage & Task sections
        JsonNode stages = jobData.path("checklist").path("stages");
        if (stages.isArray()) {
            // Stage/Task count
            int stageCount = stages.size();
            int taskCount = 0;
            for (JsonNode stage : stages) {
                JsonNode tasks = stage.path("tasks");
                if (tasks.isArray()) taskCount += tasks.size();
            }
            content.append(sectionTitle("Stage and Task Details"));
            content.append("<div class=\"detail-panel\"><table class=\"detail-table\">");
            content.append(detailRow("Total Stages", String.valueOf(stageCount)));
            content.append(detailRow("Total Tasks", String.valueOf(taskCount)));
            content.append("</table></div>");

            content.append("<div class=\"page-break\"></div>");

            // Render each stage
            for (JsonNode stage : stages) {
                int stageOrder = stage.path("orderTree").asInt(0);
                String stageName = stage.path("name").asText("Stage");

                content.append("<div class=\"stage-header\">")
                    .append("<p class=\"stage-number\">Stage-").append(stageOrder).append("</p>")
                    .append("<p class=\"stage-name\">").append(escapeHtml(stageName)).append("</p>")
                    .append("<hr class=\"stage-separator\">")
                    .append("</div>");

                // Stage instructions
                JsonNode tasks = stage.path("tasks");
                if (tasks.isArray()) {
                    StringBuilder instructions = new StringBuilder();
                    for (JsonNode task : tasks) {
                        int taskOrder = task.path("orderTree").asInt(0);
                        JsonNode params = task.path("parameters");
                        if (params.isArray()) {
                            for (JsonNode p : params) {
                                if ("INSTRUCTION".equals(p.path("type").asText())) {
                                    String text = p.path("data").path("text").asText("");
                                    if (text.startsWith("\"") && text.endsWith("\"")) {
                                        text = text.substring(1, text.length() - 1);
                                    }
                                    String taskNum = stageOrder + "." + taskOrder;
                                    instructions.append("<div class=\"instruction-item\">")
                                        .append("<div class=\"instruction-label\"><strong>")
                                        .append("<span class=\"task-number\">[TASK ").append(taskNum).append("]</span>")
                                        .append(escapeHtml(p.path("label").asText("")))
                                        .append("</strong></div>")
                                        .append("<div class=\"instruction-content\">").append(text).append("</div>")
                                        .append("</div>");
                                }
                            }
                        }
                    }
                    if (instructions.length() > 0) {
                        content.append("<div class=\"stage-instructions\"><h4>Stage Instructions</h4>")
                            .append(instructions)
                            .append("</div>");
                    }

                    // Render each task's parameter table
                    for (JsonNode task : tasks) {
                        int taskOrder = task.path("orderTree").asInt(0);
                        String taskName = task.path("name").asText("Task");
                        String taskNum = stageOrder + "." + taskOrder;

                        // Check if task has non-instruction parameters
                        JsonNode params = task.path("parameters");
                        boolean hasData = false;
                        if (params.isArray()) {
                            for (JsonNode p : params) {
                                if (!"INSTRUCTION".equals(p.path("type").asText())) {
                                    hasData = true;
                                    break;
                                }
                            }
                        }
                        if (!hasData) continue;

                        content.append("<h4>Task ").append(taskNum).append(" – ")
                            .append(escapeHtml(taskName)).append("</h4>");

                        // Task execution info
                        JsonNode executions = task.path("taskExecutions");
                        if (executions.isArray() && executions.size() > 0) {
                            JsonNode exec = executions.get(0);
                            String execState = exec.path("state").asText("");
                            if (!execState.isEmpty()) {
                                content.append("<p style=\"margin:5px 0;\">Task Execution State: <b>")
                                    .append(formatState(execState)).append("</b></p>");
                            }
                            String startInfo = formatTimestamp(exec.path("startedAt"));
                            String startBy = formatUser(exec.path("startedBy"));
                            if (!"-".equals(startInfo)) {
                                content.append("<p style=\"margin:5px 0;\">Started on ").append(startInfo)
                                    .append(" by ").append(startBy).append("</p>");
                            }
                            String endInfo = formatTimestamp(exec.path("endedAt"));
                            String endBy = formatUser(exec.path("endedBy"));
                            if (!"-".equals(endInfo)) {
                                content.append("<p style=\"margin:5px 0;\">Completed on ").append(endInfo)
                                    .append(" by ").append(endBy).append("</p>");
                            }
                        }

                        // Parameter table
                        content.append("<table class=\"parameter-table\">")
                            .append("<thead><tr>")
                            .append("<th style=\"width:30%\">Attribute</th>")
                            .append("<th style=\"width:35%\">Values</th>")
                            .append("<th style=\"width:20%\">Person</th>")
                            .append("<th style=\"width:15%\">Time</th>")
                            .append("</tr></thead><tbody>");

                        if (params.isArray()) {
                            for (JsonNode p : params) {
                                String pType = p.path("type").asText("");
                                if ("INSTRUCTION".equals(pType)) continue;

                                String label = p.path("label").asText("-");
                                JsonNode resp = p.path("response");
                                String value = "-";
                                String person = "-";
                                String time = "-";

                                if (resp.isArray() && resp.size() > 0) {
                                    // Find the best response entry (one with actual data/medias)
                                    JsonNode r = findBestResponse(p, resp);
                                    value = extractParameterValue(p, r, token, facilityId);
                                    JsonNode modBy = r.path("audit").path("modifiedBy");
                                    if (!modBy.isMissingNode() && modBy.has("firstName")) {
                                        person = formatUser(modBy);
                                    }
                                    time = formatTimestamp(r.path("audit").path("modifiedAt"));
                                }

                                content.append("<tr>")
                                    .append("<td>").append(escapeHtml(label)).append("</td>")
                                    .append("<td>").append(value).append("</td>")
                                    .append("<td>").append(escapeHtml(person)).append("</td>")
                                    .append("<td>").append(escapeHtml(time)).append("</td>")
                                    .append("</tr>");
                            }
                        }
                        content.append("</tbody></table>");
                    }
                }

                content.append("<div class=\"page-break\"></div>");
            }
        }

        // Wrap content in the full template
        return TEMPLATE_PREFIX + content.toString() + TEMPLATE_SUFFIX;
    }

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg");

    private static JsonNode findBestResponse(JsonNode param, JsonNode resp) {
        String type = param.path("type").asText("");
        boolean isMediaType = "MEDIA".equals(type) || "SIGNATURE".equals(type) || "FILE_UPLOAD".equals(type);

        if (isMediaType) {
            for (JsonNode r : resp) {
                JsonNode medias = r.path("medias");
                if (medias.isArray() && medias.size() > 0) {
                    return r;
                }
            }
        }

        for (JsonNode r : resp) {
            String val = r.path("value").asText("");
            if (!val.isEmpty() && !"null".equals(val)) {
                return r;
            }
            JsonNode choices = r.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return r;
            }
        }

        return resp.get(0);
    }

    private static String extractParameterValue(JsonNode param, JsonNode response,
                                                  String token, String facilityId) {
        String type = param.path("type").asText("");
        String value = response.path("value").asText("");

        if ("MEDIA".equals(type) || "SIGNATURE".equals(type) || "FILE_UPLOAD".equals(type)) {
            JsonNode medias = response.path("medias");
            if (medias.isArray() && medias.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode m : medias) {
                    String link = m.path("link").asText("");
                    String name = m.path("name").asText("file");
                    if (link.isEmpty()) continue;
                    if (sb.length() > 0) sb.append("<br>");

                    boolean isImage = "MEDIA".equals(type) || "SIGNATURE".equals(type)
                        || isImageFile(name, m.path("type").asText(""));

                    if (isImage) {
                        System.out.printf("  Embedding image: %s%n", name);
                        String dataUri = downloadAsBase64(link, token, facilityId);
                        if (dataUri != null) {
                            String cssClass = "SIGNATURE".equals(type) ? "pdf-signature" : "pdf-inline-image";
                            sb.append("<img src=\"").append(dataUri).append("\" class=\"").append(cssClass)
                                .append("\" alt=\"").append(escapeHtml(name)).append("\">");
                        } else {
                            sb.append("<a href=\"").append(escapeHtml(link)).append("\">")
                                .append(escapeHtml(name)).append("</a>");
                        }
                    } else {
                        sb.append("<a href=\"").append(escapeHtml(link)).append("\">")
                            .append(escapeHtml(name)).append("</a>");
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
        }

        if (value.isEmpty() || "null".equals(value)) {
            JsonNode choices = response.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode choice : choices) {
                    String name = choice.path("name").asText(choice.path("objectDisplayName").asText(""));
                    if (!name.isEmpty()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(escapeHtml(name));
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
            return "-";
        }

        return escapeHtml(value);
    }

    private static boolean isImageFile(String filename, String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) return true;
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            String ext = filename.substring(dot + 1).toLowerCase();
            return IMAGE_EXTENSIONS.contains(ext);
        }
        return false;
    }

    private static String downloadAsBase64(String url, String token, String facilityId) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("facilityId", facilityId)
                .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                .GET()
                .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                byte[] bytes = response.body();
                String contentType = response.headers().firstValue("content-type").orElse("image/png");
                if (contentType.contains(";")) {
                    contentType = contentType.substring(0, contentType.indexOf(';')).trim();
                }
                String encoded = Base64.getEncoder().encodeToString(bytes);
                return "data:" + contentType + ";base64," + encoded;
            } else {
                System.out.printf("  Image download HTTP %d for %s%n", response.statusCode(), url);
            }
        } catch (Exception e) {
            System.out.printf("  Image download error: %s%n", e.getMessage());
        }
        return null;
    }

    private static String formatState(String state) {
        if (state == null) return "-";
        return state.replace("_", " ")
            .substring(0, 1).toUpperCase() + state.replace("_", " ").substring(1).toLowerCase();
    }

    private static String formatUser(JsonNode user) {
        if (user == null || user.isMissingNode()) return "-";
        String first = user.path("firstName").asText("");
        String last = user.path("lastName").asText("");
        String empId = user.path("employeeId").asText("");
        String name = (first + " " + last).trim();
        if (name.isEmpty()) return "-";
        if (!empId.isEmpty()) name += " (" + empId + ")";
        return name;
    }

    private static String formatTimestamp(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "-";
        try {
            long ts = node.asLong(0);
            if (ts == 0) return "-";
            return Instant.ofEpochMilli(ts)
                .atZone(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
        } catch (Exception e) {
            return "-";
        }
    }

    private static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }

    private static String sectionTitle(String title) {
        return "<div class=\"section-title\">" + escapeHtml(title) + "</div>";
    }

    private static String detailRow(String label, String value) {
        return "<tr><th>" + escapeHtml(label) + "</th><td>" + escapeHtml(value != null ? value : "-") + "</td></tr>";
    }

    private static String detailRowRaw(String label, String valueHtml) {
        return "<tr><th>" + escapeHtml(label) + "</th><td>" + (valueHtml != null ? valueHtml : "-") + "</td></tr>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String buildHeaderTemplate() {
        return "<div style=\"width:100%; background:#eeeeee; padding:10px 20px; font-size:10px;\">"
            + "<table style=\"width:100%;\"><tr>"
            + "<td style=\"text-align:left;\">Streem - Job Report</td>"
            + "<td style=\"text-align:right;\">Locally Rendered (Emoji Fix Demo)</td>"
            + "</tr></table>"
            + "</div>";
    }

    private static String buildFooterTemplate() {
        return "<div style=\"width:100%; background:#eeeeee; font-size:9px; padding:8px 20px;\">"
            + "<table style=\"width:100%;\"><tr>"
            + "<td style=\"text-align:left;\">Generated locally with Playwright + Noto Color Emoji</td>"
            + "<td style=\"text-align:right;\">"
            + "<span style=\"display:inline-block; background:#bababa; border-radius:4px; padding:2px 8px; font-weight:bold;\">"
            + "<span class=\"pageNumber\"></span> / <span class=\"totalPages\"></span>"
            + "</span></td></tr></table></div>";
    }

    private static final String TEMPLATE_PREFIX = """
        <html lang="en">
        <head>
          <meta charset="UTF-8"/>
          <title>Job Report</title>
          <style>
            .section-wrapper { break-inside: avoid; page-break-inside: avoid; }
            @page { size: A4 portrait; }

            html, body {
              margin: 0; padding: 0;
              font-family: Helvetica, 'Noto Color Emoji', sans-serif;
              font-size: 11pt; color: #000;
            }

            p, h3, h4 { margin: 0; }
            .header, .footer { display: none; }

            .content { margin: 0.5cm; }

            .section-title {
              display: inline-block; background: #000; color: #fff;
              padding: 1.2mm 4mm; margin: 0 0 0.5mm 0;
              border-radius: 8px 8px 0 0; border-bottom: 0.5pt solid #000;
            }

            .detail-panel {
              background: #f2f2f2; padding: 4mm; margin-bottom: 6mm;
              break-inside: avoid; page-break-inside: avoid;
            }

            .detail-panel table.detail-table {
              width: 100%; border-collapse: separate; border-spacing: 0 2mm; margin-bottom: 12px;
            }
            .detail-panel table.detail-table th {
              width: 35%; font-weight: 600; border: none; color: #000; text-align: left; vertical-align: top;
            }
            .detail-panel table.detail-table td {
              box-sizing: border-box; border: 1pt solid #000; background: none; vertical-align: top;
            }

            .parameter-table {
              width: 100%; table-layout: fixed; border-collapse: collapse; margin: 4mm 0;
            }
            .parameter-table th, .parameter-table td {
              border: 0.5pt solid #000; padding: 1.6mm 2mm; vertical-align: bottom;
              white-space: normal; word-wrap: break-word; height: 8mm;
            }
            .parameter-table th { background: #fafafa; font-weight: 600; }

            .page-break { page-break-before: always; }

            .stage-header { margin: 6mm 0 2mm; }
            .stage-header .stage-number { font-size: 12pt; font-weight: 700; margin: 0; }
            .stage-header .stage-name { font-size: 11pt; font-weight: 600; margin: 2px 0; }
            .stage-header .stage-separator, .stage-header hr { width: 100%; border-top: 0.5pt solid #000; margin: 2mm 0 4mm; }

            .task-number { font-size: 0.9em; color: #666; margin-right: 5px; }

            .pdf-signature { max-width: 200px; max-height: 80px; display: block; margin: 2px 0; }
            .pdf-inline-image { max-width: 180px; max-height: 140px; display: inline-block; margin: 2px 4px 2px 0; border: 0.5pt solid #ccc; }
          </style>
        </head>
        <body>
        <div class="content">
        """;

    private static final String TEMPLATE_SUFFIX = """
        </div>
        </body>
        </html>
        """;
}
