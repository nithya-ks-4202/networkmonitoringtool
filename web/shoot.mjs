import { chromium } from 'playwright'
const out = process.argv[2]
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome' })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
const errors = []
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', e => errors.push(String(e)))

await page.goto('http://localhost:3000/', { waitUntil: 'networkidle' })
await page.screenshot({ path: `${out}/01-login.png` })

await page.fill('#username', 'admin')
await page.fill('#password', 'test-admin-pw')
await page.click('button[type=submit]')
await page.waitForSelector('.page-title', { timeout: 15000 })
await page.waitForTimeout(1200)
await page.screenshot({ path: `${out}/02-dashboard.png`, fullPage: true })

await page.click('a[href="/problems"]'); await page.waitForTimeout(1000)
await page.screenshot({ path: `${out}/03-problems.png`, fullPage: true })

await page.click('a[href="/cameras"]'); await page.waitForTimeout(1000)
await page.screenshot({ path: `${out}/04-cameras.png`, fullPage: true })

await page.click('a[href="/hosts"]'); await page.waitForTimeout(1000)
await page.screenshot({ path: `${out}/05-hosts.png`, fullPage: true })

await page.click('table tbody tr:has-text("Web server 01") a')
await page.waitForTimeout(2200)
await page.screenshot({ path: `${out}/06-host-detail.png`, fullPage: true })

console.log(errors.length ? 'CONSOLE ERRORS:\n' + errors.join('\n') : 'no console errors')
await browser.close()
