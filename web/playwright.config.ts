import { defineConfig, devices } from "@playwright/test";

/**
 * Smoke test against a running stack:
 *   docker compose --profile infra up -d
 *   (cd api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)   # API on 8081, workers on
 *   pnpm dev                                                             # Vite on 5173
 *   pnpm test:e2e
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 180_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
