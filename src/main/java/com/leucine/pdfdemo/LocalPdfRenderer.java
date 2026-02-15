package com.leucine.pdfdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Fetches job data from the QA API and renders a PDF locally using Playwright.
 * This uses the same template CSS as the backend but renders on the local machine,
 * so the fixed 'Noto Color Emoji' font takes effect without needing a backend deploy.
 */
public class LocalPdfRenderer {

    public static void run(String token, String jobId) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  LOCAL PDF RENDERING (Playwright + Fixed Emoji Font)");
        System.out.println("=".repeat(60));

        // 1. Fetch job data and build HTML
        String html = fetchAndBuildHtml(token, jobId);
        if (html == null) return;

        // 2. Render PDF locally with Playwright
        System.out.println("  Rendering PDF with local Playwright...");
        Files.createDirectories(DemoConfig.OUTPUT_DIR);

        long start = System.currentTimeMillis();

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            byte[] pdf = renderPdfBytes(page, html);

            long elapsed = System.currentTimeMillis() - start;

            String ts = Instant.now().atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path output = DemoConfig.OUTPUT_DIR.resolve("report_" + ts + ".pdf");
            Files.write(output, pdf);

            context.close();
            browser.close();

            System.out.printf("\n  Result:%n");
            System.out.printf("    Status:    OK%n");
            System.out.printf("    Size:      %,.1f KB (%,d bytes)%n", pdf.length / 1024.0, pdf.length);
            System.out.printf("    Time:      %,d ms%n", elapsed);
            System.out.printf("    Saved:     %s%n", output.toAbsolutePath());
            System.out.printf("    Emojis:    Rendered with 'Noto Color Emoji' (fixed font)%n");
        }
    }

    /**
     * Fetches job data from the API and builds the full HTML string (including sample appendices).
     * Returns null if the API call fails.
     */
    static String fetchAndBuildHtml(String token, String jobId) throws Exception {
        System.out.printf("\n  Fetching job %s from QA API...%n", jobId);
        JsonNode response = ApiClient.apiGet(DemoConfig.STREEM_API + "/jobs/" + jobId, token);
        if (response == null) {
            System.out.println("  ERROR: Could not fetch job data.");
            return null;
        }

        JsonNode jobData = response.get("data");
        String jobCode = jobData.path("code").asText(jobId);
        String state = jobData.path("state").asText("?");
        String checklistName = jobData.path("checklist").path("name").asText("-");
        String checklistCode = jobData.path("checklist").path("code").asText("-");

        System.out.printf("  Job: %s | State: %s | Process: %s%n", jobCode, state, checklistName);
        System.out.println("  Building HTML from job data...");

        return buildFullHtml(jobData, jobCode, state, checklistName, checklistCode, token);
    }

    /**
     * Renders the given HTML to PDF bytes using an already-open Playwright Page.
     * Reusable by LocalBenchmark for tight-loop rendering.
     */
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
                                         String checklistName, String checklistCode, String token) {
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
                                    value = extractParameterValue(p, r, token);
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

        // Add sample appendices to pad PDF to ~10 pages
        content.append(buildSampleAppendices());

        // Wrap content in the full template
        return TEMPLATE_PREFIX + content.toString() + TEMPLATE_SUFFIX;
    }

    private static String buildSampleAppendices() {
        StringBuilder sb = new StringBuilder();

        // ── Appendix A – Embedded Images Gallery (~2 pages) ──
        sb.append("<div class=\"page-break\"></div>");
        sb.append(sectionTitle("Appendix A \u2013 Embedded Images Gallery"));
        sb.append("<p style=\"margin:4mm 0;\">The following sample images demonstrate inline embedding support for media-rich job reports.</p>");

        String[][] imageSpecs = {
            {"Sample Photo 1",    "#2196F3", "#BBDEFB"},
            {"Equipment Check",   "#4CAF50", "#C8E6C9"},
            {"Site Inspection",   "#FF9800", "#FFE0B2"},
            {"Material Receipt",  "#9C27B0", "#E1BEE7"},
            {"Safety Signage",    "#F44336", "#FFCDD2"},
            {"Final Verification","#607D8B", "#CFD8DC"},
        };

        sb.append("<div style=\"display:flex; flex-wrap:wrap; gap:6mm; margin:4mm 0;\">");
        for (String[] spec : imageSpecs) {
            appendImageCard(sb, buildThumbnailSvg(spec[0], spec[1], spec[2]),
                spec[0], "max-width:220px;max-height:160px;");
        }
        sb.append("</div>");

        sb.append("<div class=\"page-break\"></div>");
        sb.append("<p style=\"margin:4mm 0; font-weight:600;\">Gallery \u2013 Enlarged Views</p>");
        sb.append("<div style=\"display:flex; flex-wrap:wrap; gap:6mm; margin:4mm 0;\">");
        for (int i = 0; i < 4; i++) {
            String label = imageSpecs[i][0] + " (Enlarged)";
            appendImageCard(sb, buildEnlargedSvg(imageSpecs[i][0], imageSpecs[i][1], imageSpecs[i][2]),
                label, "max-width:320px;max-height:220px;");
        }
        sb.append("</div>");

        // ── Appendix B – Signature Verification Log (~1 page) ──
        sb.append("<div class=\"page-break\"></div>");
        sb.append(sectionTitle("Appendix B \u2013 Signature Verification Log"));
        sb.append("<p style=\"margin:4mm 0;\">Digital signature records for job completion verification.</p>");

        String[][] signatories = {
            {"Operator",   "Rajesh Kumar", "EMP-1042", "15 Feb 2026, 09:30 AM"},
            {"Supervisor", "Priya Sharma", "EMP-0871", "15 Feb 2026, 10:15 AM"},
            {"QA Manager", "Ankit Verma",  "EMP-0234", "15 Feb 2026, 11:00 AM"},
            {"Shift Lead", "Meera Patel",  "EMP-0567", "15 Feb 2026, 11:45 AM"},
        };

        sb.append("<table class=\"parameter-table\">");
        sb.append("<thead><tr><th style=\"width:18%\">Role</th><th style=\"width:22%\">Name</th>")
            .append("<th style=\"width:12%\">Employee ID</th><th style=\"width:25%\">Signature</th>")
            .append("<th style=\"width:23%\">Signed At</th></tr></thead><tbody>");
        for (String[] sig : signatories) {
            String sigUri = svgToDataUri(buildSignatureSvg(sig[1]));
            sb.append("<tr>")
                .append("<td>").append(escapeHtml(sig[0])).append("</td>")
                .append("<td>").append(escapeHtml(sig[1])).append("</td>")
                .append("<td>").append(escapeHtml(sig[2])).append("</td>")
                .append("<td><img src=\"").append(sigUri).append("\" class=\"pdf-signature\" alt=\"Signature\"></td>")
                .append("<td>").append(escapeHtml(sig[3])).append("</td>")
                .append("</tr>");
        }
        sb.append("</tbody></table>");

        // ── Appendix C – Detailed Observations (~2 pages) ──
        sb.append("<div class=\"page-break\"></div>");
        sb.append(sectionTitle("Appendix C \u2013 Detailed Observations"));
        sb.append("<p style=\"margin:4mm 0;\">Sample inspection observations recorded during the job execution.</p>");

        String[][] observations = {
            {"OBS-001", "Temperature Check",    "36.5 \u00B0C",  "Within Range", "15 Feb 2026, 08:00 AM"},
            {"OBS-002", "Humidity Level",        "45%",           "Normal",       "15 Feb 2026, 08:05 AM"},
            {"OBS-003", "Equipment Calibration", "Pass",          "Verified",     "15 Feb 2026, 08:10 AM"},
            {"OBS-004", "Raw Material Lot#",     "RM-2026-0451",  "Approved",     "15 Feb 2026, 08:15 AM"},
            {"OBS-005", "pH Level",              "7.2",           "Within Spec",  "15 Feb 2026, 08:20 AM"},
            {"OBS-006", "Pressure Reading",      "2.1 bar",       "Normal",       "15 Feb 2026, 08:25 AM"},
            {"OBS-007", "Viscosity",             "340 cP",        "Within Range", "15 Feb 2026, 08:30 AM"},
            {"OBS-008", "Particle Count",        "12 ppm",        "Below Limit",  "15 Feb 2026, 08:35 AM"},
            {"OBS-009", "Weight Verification",   "500.3 g",       "Pass",         "15 Feb 2026, 08:40 AM"},
            {"OBS-010", "Visual Inspection",     "No defects",    "Approved",     "15 Feb 2026, 08:45 AM"},
            {"OBS-011", "Seal Integrity",        "Intact",        "Pass",         "15 Feb 2026, 08:50 AM"},
            {"OBS-012", "Label Verification",    "Correct",       "Verified",     "15 Feb 2026, 08:55 AM"},
            {"OBS-013", "Batch Yield",           "98.7%",         "Above Target", "15 Feb 2026, 09:00 AM"},
            {"OBS-014", "Dissolution Rate",      "92% @ 30min",   "Within Spec",  "15 Feb 2026, 09:10 AM"},
            {"OBS-015", "Moisture Content",      "2.1%",          "Within Limit", "15 Feb 2026, 09:15 AM"},
            {"OBS-016", "Hardness Test",         "8.5 kP",        "Pass",         "15 Feb 2026, 09:20 AM"},
            {"OBS-017", "Friability",            "0.3%",          "Below 1%",     "15 Feb 2026, 09:25 AM"},
            {"OBS-018", "Disintegration Time",   "4 min 20 sec",  "Within Spec",  "15 Feb 2026, 09:30 AM"},
            {"OBS-019", "Color Uniformity",      "Uniform",       "Approved",     "15 Feb 2026, 09:35 AM"},
            {"OBS-020", "Odor Check",            "No off-odor",   "Pass",         "15 Feb 2026, 09:40 AM"},
            {"OBS-021", "Microbial Limit",       "<10 CFU/g",     "Within Spec",  "15 Feb 2026, 09:45 AM"},
            {"OBS-022", "Endotoxin Level",       "0.12 EU/mL",    "Below Limit",  "15 Feb 2026, 09:50 AM"},
        };

        sb.append("<div class=\"detail-panel\"><table class=\"detail-table\">");
        sb.append("<tr><th style=\"width:12%\"><b>Obs. ID</b></th><th style=\"width:25%\"><b>Parameter</b></th>")
            .append("<th style=\"width:18%\"><b>Value</b></th><th style=\"width:18%\"><b>Status</b></th>")
            .append("<th style=\"width:27%\"><b>Recorded At</b></th></tr>");
        for (String[] obs : observations) {
            appendTableRow(sb, obs);
        }
        sb.append("</table></div>");

        // ── Appendix D – Audit Trail (~2 pages) ──
        sb.append("<div class=\"page-break\"></div>");
        sb.append(sectionTitle("Appendix D \u2013 Audit Trail"));
        sb.append("<p style=\"margin:4mm 0;\">Comprehensive audit log of all actions performed during this job.</p>");

        String[][] auditEntries = {
            {"15 Feb 2026, 07:45:00 AM", "Job Created",        "System",       "Job initialized from process template CL-2026-0089"},
            {"15 Feb 2026, 07:45:01 AM", "Job Assigned",       "System",       "Auto-assigned to Shift A production team"},
            {"15 Feb 2026, 07:50:12 AM", "Job Started",        "Rajesh Kumar", "Operator initiated job execution"},
            {"15 Feb 2026, 07:55:30 AM", "Task Started",       "Rajesh Kumar", "Task 1.1 \u2013 Pre-production checks initiated"},
            {"15 Feb 2026, 08:00:00 AM", "Parameter Updated",  "Rajesh Kumar", "Temperature reading recorded: 36.5 \u00B0C"},
            {"15 Feb 2026, 08:05:00 AM", "Parameter Updated",  "Rajesh Kumar", "Humidity level recorded: 45%"},
            {"15 Feb 2026, 08:10:00 AM", "Parameter Updated",  "Rajesh Kumar", "Equipment calibration verified: PASS"},
            {"15 Feb 2026, 08:15:00 AM", "Parameter Updated",  "Rajesh Kumar", "Raw material lot RM-2026-0451 approved"},
            {"15 Feb 2026, 08:20:15 AM", "Media Uploaded",     "Rajesh Kumar", "Equipment photo attached to Task 1.1"},
            {"15 Feb 2026, 08:25:00 AM", "Parameter Updated",  "Rajesh Kumar", "pH level recorded: 7.2"},
            {"15 Feb 2026, 08:30:00 AM", "Task Completed",     "Rajesh Kumar", "Task 1.1 completed with all parameters filled"},
            {"15 Feb 2026, 08:35:00 AM", "Task Started",       "Rajesh Kumar", "Task 1.2 \u2013 In-process checks initiated"},
            {"15 Feb 2026, 08:40:00 AM", "Parameter Updated",  "Rajesh Kumar", "Pressure reading: 2.1 bar"},
            {"15 Feb 2026, 08:45:00 AM", "Parameter Updated",  "Rajesh Kumar", "Viscosity measurement: 340 cP"},
            {"15 Feb 2026, 08:50:00 AM", "Parameter Updated",  "Rajesh Kumar", "Particle count: 12 ppm"},
            {"15 Feb 2026, 08:55:00 AM", "Signature Captured", "Rajesh Kumar", "Operator signature recorded for Task 1.2"},
            {"15 Feb 2026, 09:00:00 AM", "Task Completed",     "Rajesh Kumar", "Task 1.2 completed successfully"},
            {"15 Feb 2026, 09:05:00 AM", "Review Requested",   "Rajesh Kumar", "Job submitted for supervisor review"},
            {"15 Feb 2026, 09:15:00 AM", "Task Reviewed",      "Priya Sharma", "Supervisor approved Task 1.1 parameters"},
            {"15 Feb 2026, 09:20:00 AM", "Task Reviewed",      "Priya Sharma", "Supervisor approved Task 1.2 parameters"},
            {"15 Feb 2026, 09:30:00 AM", "Signature Captured", "Priya Sharma", "Supervisor sign-off recorded"},
            {"15 Feb 2026, 10:00:00 AM", "QA Review Started",  "Ankit Verma",  "QA Manager began final quality review"},
            {"15 Feb 2026, 10:15:00 AM", "Parameter Verified", "Ankit Verma",  "All critical parameters verified against specifications"},
            {"15 Feb 2026, 10:30:00 AM", "Deviation Check",    "Ankit Verma",  "No deviations detected \u2013 all values within limits"},
            {"15 Feb 2026, 10:45:00 AM", "Signature Captured", "Ankit Verma",  "QA Manager approval signature recorded"},
            {"15 Feb 2026, 11:00:00 AM", "Shift Handover",     "Meera Patel",  "Shift Lead verified completion across all stages"},
            {"15 Feb 2026, 11:15:00 AM", "Signature Captured", "Meera Patel",  "Shift Lead sign-off signature recorded"},
            {"15 Feb 2026, 11:30:00 AM", "Job Completed",      "System",       "All tasks and reviews completed \u2013 job marked DONE"},
            {"15 Feb 2026, 11:30:01 AM", "Report Generated",   "System",       "PDF report auto-generated and archived"},
            {"15 Feb 2026, 11:30:02 AM", "Notification Sent",  "System",       "Completion notification sent to stakeholders"},
        };

        sb.append("<table class=\"parameter-table\">");
        sb.append("<thead><tr><th style=\"width:22%\">Timestamp</th><th style=\"width:18%\">Action</th>")
            .append("<th style=\"width:16%\">User</th><th style=\"width:44%\">Details</th></tr></thead><tbody>");
        for (String[] entry : auditEntries) {
            appendTableRow(sb, entry);
        }
        sb.append("</tbody></table>");

        return sb.toString();
    }

    // ── SVG helpers for sample appendices ──

    private static String buildThumbnailSvg(String label, String color, String bg) {
        return "<svg xmlns='http://www.w3.org/2000/svg' width='300' height='200'>"
            + "<rect width='300' height='200' fill='" + bg + "' rx='8'/>"
            + "<rect x='10' y='10' width='280' height='150' fill='" + color + "' rx='6' opacity='0.3'/>"
            + "<text x='150' y='105' text-anchor='middle' font-family='Helvetica' font-size='16' fill='" + color + "'>" + label + "</text>"
            + "<text x='150' y='185' text-anchor='middle' font-family='Helvetica' font-size='10' fill='#666'>300 x 200 px</text>"
            + "</svg>";
    }

    private static String buildEnlargedSvg(String label, String color, String bg) {
        return "<svg xmlns='http://www.w3.org/2000/svg' width='400' height='280'>"
            + "<rect width='400' height='280' fill='" + bg + "' rx='10'/>"
            + "<rect x='15' y='15' width='370' height='220' fill='" + color + "' rx='8' opacity='0.25'/>"
            + "<circle cx='200' cy='120' r='50' fill='" + color + "' opacity='0.4'/>"
            + "<text x='200' y='128' text-anchor='middle' font-family='Helvetica' font-size='18' fill='" + color + "'>" + label + "</text>"
            + "<text x='200' y='265' text-anchor='middle' font-family='Helvetica' font-size='10' fill='#666'>400 x 280 px \u2014 enlarged view</text>"
            + "</svg>";
    }

    private static String buildSignatureSvg(String name) {
        return "<svg xmlns='http://www.w3.org/2000/svg' width='200' height='60'>"
            + "<rect width='200' height='60' fill='#fafafa' rx='4'/>"
            + "<path d='M 15 40 Q 40 10, 70 35 T 130 30 T 185 38' fill='none' stroke='#1a237e' stroke-width='2' stroke-linecap='round'/>"
            + "<text x='100' y='55' text-anchor='middle' font-family='Georgia,serif' font-size='8' fill='#999'>" + escapeHtml(name) + "</text>"
            + "</svg>";
    }

    private static String svgToDataUri(String svg) {
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    private static void appendImageCard(StringBuilder sb, String svg, String label, String style) {
        sb.append("<div style=\"text-align:center;\">")
            .append("<img src=\"").append(svgToDataUri(svg)).append("\" class=\"pdf-inline-image\" style=\"").append(style)
            .append("\" alt=\"").append(escapeHtml(label)).append("\">")
            .append("<div style=\"font-size:9pt;color:#555;margin-top:1mm;\">").append(escapeHtml(label)).append("</div>")
            .append("</div>");
    }

    private static void appendTableRow(StringBuilder sb, String[] cells) {
        sb.append("<tr>");
        for (String cell : cells) {
            sb.append("<td>").append(cell).append("</td>");
        }
        sb.append("</tr>");
    }

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg");

    /**
     * Finds the best response entry from a multi-entry response array.
     * For media/signature/file types, picks the entry that has medias.
     * Otherwise picks the first entry with a non-empty value.
     * Falls back to resp.get(0).
     */
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

        // For non-media types, pick first entry with a non-empty, non-null value
        for (JsonNode r : resp) {
            String val = r.path("value").asText("");
            if (!val.isEmpty() && !"null".equals(val)) {
                return r;
            }
            // Also check choices
            JsonNode choices = r.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return r;
            }
        }

        return resp.get(0);
    }

    private static String extractParameterValue(JsonNode param, JsonNode response, String token) {
        String type = param.path("type").asText("");
        String value = response.path("value").asText("");

        // For media/signature/file types, embed images inline (check before null/empty guard since value is often "null" for these)
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
                        String dataUri = downloadAsBase64(link, token);
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
            // Check choices for select/checklist types
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

    private static String downloadAsBase64(String url, String token) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(DemoConfig.API_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("facilityId", DemoConfig.FACILITY_ID)
                .timeout(Duration.ofSeconds(DemoConfig.API_TIMEOUT_SECONDS))
                .GET()
                .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                byte[] bytes = response.body();
                String contentType = response.headers().firstValue("content-type").orElse("image/png");
                // Strip any charset or params from content-type
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

    // The same CSS from job-pdf-report.html template, with the FIXED emoji font
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
