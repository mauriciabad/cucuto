import { defineConfig, devices } from '@playwright/test';

// PORT lets several checkouts run the tests at once without reusing each other's preview server.
const port = Number(process.env.PORT ?? 4173);

export default defineConfig({
	testDir: 'tests',
	testMatch: '**/*.e2e.ts',
	forbidOnly: !!process.env.CI,
	retries: process.env.CI ? 1 : 0,
	reporter: process.env.CI ? [['github'], ['list']] : 'list',
	use: { baseURL: `http://localhost:${port}`, trace: 'retain-on-failure' },
	webServer: {
		command: `bun run build && bun run preview --port ${port} --strictPort`,
		port,
		reuseExistingServer: !process.env.CI
	},
	projects: [
		{ name: 'desktop', use: { ...devices['Desktop Chrome'] } },
		{ name: 'mobile', use: { ...devices['Pixel 7'] } }
	]
});
