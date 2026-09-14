import { expect, test } from "@playwright/test";
import { SMTPServer } from "smtp-server";

/**
 * Login → configure SMTP (against a throwaway SMTP server started here) → create template →
 * campaign wizard with CSV upload → publish → campaign completes and the mails arrive.
 */
const SMTP_PORT = 2525;
const received: string[] = [];
let smtp: SMTPServer;

test.beforeAll(async () => {
  smtp = new SMTPServer({
    authOptional: true,
    disabledCommands: ["STARTTLS"],
    onData(stream, session, callback) {
      let raw = "";
      stream.on("data", (chunk) => (raw += chunk.toString()));
      stream.on("end", () => {
        received.push(raw);
        callback();
      });
    },
  });
  await new Promise<void>((resolve) => smtp.listen(SMTP_PORT, "127.0.0.1", resolve));
});

test.afterAll(async () => {
  await new Promise<void>((resolve) => smtp.close(resolve));
});

test("operator can go from login to a completed campaign", async ({ page }) => {
  const suffix = Date.now().toString(36);

  // login
  await page.goto("/login");
  await page.getByLabel("Email").fill("admin@demo.local");
  await page.getByLabel("Password").fill("Admin123!");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();

  // SMTP channel → test email must arrive before the settings are saved
  await page.getByRole("link", { name: "Channels" }).first().click();
  await page.getByLabel("Host").fill("127.0.0.1");
  await page.getByLabel("Port", { exact: true }).fill(String(SMTP_PORT));
  await page.getByLabel("Username", { exact: true }).fill("");
  await page.getByLabel("From address").fill("noreply@e2e.local");
  await page.getByLabel("From name").fill("E2E");
  const tls = page.getByLabel(/Use TLS/);
  if (await tls.isChecked()) await tls.uncheck();
  await page.getByLabel("Send the test email to").fill("ops@e2e.local");
  await page.getByRole("button", { name: "Save and send test email" }).click();
  await expect(page.getByText("Test email delivered to ops@e2e.local")).toBeVisible();
  expect(received.length).toBe(1);

  // template
  await page.getByRole("link", { name: "Templates" }).first().click();
  await page.getByRole("link", { name: "Create template" }).first().click();
  const templateName = `e2e-welcome-${suffix}`;
  await page.getByLabel("Name", { exact: true }).fill(templateName);
  await page.getByLabel("Subject").fill("Hello {{name}}");
  await page.getByLabel("HTML body").fill("<p>Hi {{name}}, code {{code}}</p>");
  await page.getByRole("button", { name: "Create" }).click();
  await expect(page.getByText("Template created")).toBeVisible();
  await expect(page).toHaveURL(/\/templates\/\d+$/);

  // campaign wizard
  await page.getByRole("link", { name: "Campaigns" }).first().click();
  await page.getByRole("link", { name: "Create campaign" }).first().click();
  await page.getByLabel("Title", { exact: true }).fill(`E2E launch ${suffix}`);
  await page.getByLabel("Template", { exact: true }).selectOption({ label: templateName });
  await page.getByRole("button", { name: "Continue" }).click();

  const csv = "recipient,name,code\nann@e2e.local,Ann,A1\nbob@e2e.local,Bob,B2\ncara@e2e.local,Cara,C3\n";
  await page.getByLabel("Recipient CSV").setInputFiles({ name: "recipients.csv", mimeType: "text/csv", buffer: Buffer.from(csv) });
  await expect(page.getByText("3 recipients ready")).toBeVisible({ timeout: 60_000 });
  await page.getByRole("button", { name: "Continue" }).click();

  await page.getByRole("radio", { name: "Send now" }).check();
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("3 recipients")).toBeVisible();
  await page.getByRole("button", { name: "Publish" }).click();

  // detail page: sending → completed, mails delivered
  await expect(page).toHaveURL(/\/campaigns\/\d+$/);
  await expect(page.getByText("Completed").first()).toBeVisible({ timeout: 120_000 });
  await expect(page.getByText("3", { exact: true }).first()).toBeVisible();
  expect(received.length).toBe(4); // 1 test email + 3 campaign emails
  expect(received.some((m) => m.includes("Hello Ann") && m.includes("code A1"))).toBe(true);
});
