package com.leucine.pdfdemo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Quick local test: renders a small HTML page with emojis using Playwright
 * to verify whether the font-family fix produces visible emoji glyphs.
 *
 * Run:  ./gradlew run --args="--emoji-test"
 */
public class EmojiFontTest {

    private static final String HTML = """
        <html>
        <head><meta charset="UTF-8"/></head>
        <body style="font-family: Helvetica, 'Noto Color Emoji', sans-serif; font-size: 14pt; padding: 40px;">
          <h2>Emoji Font Rendering Test</h2>
          <table border="1" cellpadding="8" style="border-collapse:collapse;">
            <tr><th>Description</th><th>Emoji</th></tr>
            <tr><td>Check mark</td><td>\u2705</td></tr>
            <tr><td>Cross mark</td><td>\u274C</td></tr>
            <tr><td>Warning</td><td>\u26A0\uFE0F</td></tr>
            <tr><td>Fire</td><td>\uD83D\uDD25</td></tr>
            <tr><td>Thumbs up</td><td>\uD83D\uDC4D</td></tr>
            <tr><td>Star</td><td>\u2B50</td></tr>
            <tr><td>Clock</td><td>\u23F0</td></tr>
            <tr><td>Document</td><td>\uD83D\uDCC4</td></tr>
            <tr><td>Rocket</td><td>\uD83D\uDE80</td></tr>
          </table>
          <br/>
          <p>If you see colored emojis above, <b>Noto Color Emoji</b> is working.</p>
          <p>If you see blank boxes or tofu squares, the font is missing.</p>

          <hr style="margin-top:30px;"/>
          <h3>Font comparison</h3>
          <p style="font-family: Helvetica, 'Noto Color Emoji', sans-serif;">
            <b>Noto Color Emoji:</b> \u2705 \u274C \u26A0\uFE0F \uD83D\uDD25 \uD83D\uDC4D \u2B50
          </p>
          <p style="font-family: Helvetica, 'Noto Emoji', sans-serif;">
            <b>Noto Emoji (old/wrong):</b> \u2705 \u274C \u26A0\uFE0F \uD83D\uDD25 \uD83D\uDC4D \u2B50
          </p>
          <p style="font-family: Helvetica, sans-serif;">
            <b>No emoji font:</b> \u2705 \u274C \u26A0\uFE0F \uD83D\uDD25 \uD83D\uDC4D \u2B50
          </p>
        </body>
        </html>
        """;

    public static void run() throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  EMOJI FONT RENDERING TEST");
        System.out.println("=".repeat(60));
        System.out.println("\n  Launching Playwright Chromium...");

        Files.createDirectories(DemoConfig.OUTPUT_DIR);

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(java.util.List.of("--no-sandbox", "--disable-dev-shm-usage")));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            page.setContent(HTML, new Page.SetContentOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE));

            // Check which fonts are available
            String fontsCheck = (String) page.evaluate("""
                () => {
                    const fonts = ['Noto Color Emoji', 'Noto Emoji', 'Segoe UI Emoji', 'Apple Color Emoji'];
                    const results = [];
                    for (const f of fonts) {
                        const available = document.fonts.check('16px "' + f + '"');
                        results.push(f + ': ' + (available ? 'AVAILABLE' : 'NOT FOUND'));
                    }
                    return results.join('\\n');
                }
            """);

            System.out.println("\n  Font availability on this system:");
            for (String line : fontsCheck.split("\n")) {
                System.out.println("    " + line);
            }

            byte[] pdf = page.pdf(new Page.PdfOptions()
                .setPreferCSSPageSize(false)
                .setPrintBackground(true)
                .setFormat("A4"));

            Path output = DemoConfig.OUTPUT_DIR.resolve("emoji_font_test.pdf");
            Files.write(output, pdf);

            System.out.printf("\n  PDF generated: %,.1f KB%n", pdf.length / 1024.0);
            System.out.printf("  Saved to: %s%n", output.toAbsolutePath());

            context.close();
            browser.close();

            // Open on Windows
            try {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", output.toString()});
                System.out.println("  Opening PDF...");
            } catch (Exception ignored) {
            }
        }
    }
}
