package com.leucine.pdfdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

public class BrowserLogin {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String login() throws Exception {
        System.out.println("  Opening browser for login...");

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setViewportSize(1280, 900));
            Page page = context.newPage();

            page.navigate(DemoConfig.FRONTEND_URL, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(30000));

            // Username
            page.locator("input[type=\"text\"]").first().fill(DemoConfig.USERNAME);
            page.locator("button:has-text(\"Continue\"), button[type=\"submit\"]").first().click();

            // Password
            page.locator("input[type=\"password\"]").first().waitFor(
                new Locator.WaitForOptions().setTimeout(10000));
            page.locator("input[type=\"password\"]").first().fill(DemoConfig.PASSWORD);
            page.locator("button:has-text(\"Login\"), button[type=\"submit\"]").first().click();

            page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(20000));
            page.waitForTimeout(3000);

            // Facility selection
            if (page.url().toLowerCase().contains("facility")) {
                page.locator("[class*=\"custom-select__control\"]").first().click();
                page.waitForTimeout(500);
                page.keyboard().type("Delhi", new Keyboard.TypeOptions().setDelay(50));
                page.waitForTimeout(1000);
                page.locator("[class*=\"select__option\"], [id*=\"option\"]").first().click();
                page.waitForTimeout(500);
                page.locator("button:has-text(\"Proceed\")").first().click();
                page.waitForURL("**/home**", new Page.WaitForURLOptions().setTimeout(15000));
            }

            // Extract token from localStorage
            String persistRoot = (String) page.evaluate("() => localStorage.getItem('persist:root')");
            JsonNode parsed = MAPPER.readTree(persistRoot);
            JsonNode authData = MAPPER.readTree(parsed.get("auth").asText());
            String token = "Bearer " + authData.get("accessToken").asText();

            context.close();
            browser.close();

            TokenManager.saveToken(token);
            return token;
        }
    }
}
