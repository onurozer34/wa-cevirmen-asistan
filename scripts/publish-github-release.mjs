import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire('C:/Users/Refika ÖZER/.gemini/antigravity/scratch/package.json');
const { getBrowser } = require('C:/Users/Refika ÖZER/.gemini/antigravity/scratch/antigravity_browser.js');

const ROOT = 'C:/Users/Refika ÖZER/.gemini/antigravity/scratch/wa-translator-android';
const APK_PATH = path.join(ROOT, 'app/build/outputs/apk/release/app-release.apk');
const GRADLE_KTS = path.join(ROOT, 'app/build.gradle.kts');
const OWNER = 'onurozer34';
const REPO = 'wa-cevirmen-asistan';

function readAppVersion() {
  const txt = fs.readFileSync(GRADLE_KTS, 'utf8');
  const code = Number(txt.match(/versionCode\s*=\s*(\d+)/)?.[1] || 0);
  const name = txt.match(/versionName\s*=\s*"([^"]+)"/)?.[1] || '1.0';
  return { versionCode: code, versionName: name };
}

async function publishViaApiToken(token, { versionCode, versionName }) {
  const headers = {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': 'WA-Cevirmen-Publisher'
  };

  // 1. Ensure repo exists
  let repoRes = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}`, { headers });
  if (repoRes.status === 404) {
    repoRes = await fetch('https://api.github.com/user/repos', {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: REPO,
        description: 'WA Cevirmen Asistan - Mobil WhatsApp Cok Dilli Musteri Asistani',
        private: false,
        auto_init: true
      })
    });
    if (!repoRes.ok) {
      throw new Error(`Repo olusturulamadi: HTTP ${repoRes.status} ${await repoRes.text()}`);
    }
  }

  const tag = `v${versionName}`;
  const notes = `versionCode: ${versionCode}\nWA Cevirmen Asistan ${tag} OTA surumu`;

  // 2. Create or fetch Release
  let relRes = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${tag}`, { headers });
  let releaseJson;
  if (relRes.status === 404) {
    relRes = await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/releases`, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        tag_name: tag,
        name: `WA Çevirmen Asistan ${tag}`,
        body: notes,
        draft: false,
        prerelease: false
      })
    });
  }
  if (!relRes.ok) {
    throw new Error(`Release olusturulamadi: HTTP ${relRes.status} ${await relRes.text()}`);
  }
  releaseJson = await relRes.json();

  // 3. Delete duplicate asset if re-uploading same tag
  for (const asset of releaseJson.assets || []) {
    if (asset.name === 'WA-Cevirmen-Asistan.apk') {
      await fetch(`https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${asset.id}`, {
        method: 'DELETE',
        headers
      });
    }
  }

  // 4. Upload APK asset
  const uploadUrl = releaseJson.upload_url.replace(/\{.*$/, '') + '?name=WA-Cevirmen-Asistan.apk';
  const apkBytes = fs.readFileSync(APK_PATH);
  const upRes = await fetch(uploadUrl, {
    method: 'POST',
    headers: {
      ...headers,
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(apkBytes.length)
    },
    body: apkBytes
  });
  if (!upRes.ok) {
    throw new Error(`APK yuklenemedi: HTTP ${upRes.status} ${await upRes.text()}`);
  }
  const uploaded = await upRes.json();
  console.log('GITHUB_RELEASE_PUBLISHED:', uploaded.browser_download_url);
}

async function publishViaChromeSession({ versionCode, versionName }) {
  const browser = await getBrowser();
  try {
    const page = await browser.newPage();
    await page.goto('https://github.com', { waitUntil: 'domcontentloaded', timeout: 20000 });
    const loggedInUser = await page.evaluate(() => document.querySelector('meta[name=user-login]')?.getAttribute('content') || '');
    if (!loggedInUser) {
      throw new Error('ChromeAutoProfile uzerinde GitHub oturumu acik degil.');
    }

    // Check or create repo
    await page.goto(`https://github.com/${OWNER}/${REPO}`, { waitUntil: 'domcontentloaded', timeout: 20000 });
    const repoNotFound = await page.evaluate(() => document.title.includes('Page not found') || Boolean(document.querySelector('img[alt*="404"]')));
    if (repoNotFound) {
      await page.goto('https://github.com/new', { waitUntil: 'networkidle2', timeout: 25000 });
      await page.type('input[aria-label="Repository"], input[name="repository[name]"]', REPO);
      await new Promise((r) => setTimeout(r, 1200));
      const submitBtn = await page.$('button[type="submit"]:has-text("Create repository"), button[type="submit"]');
      if (submitBtn) await submitBtn.click();
      await page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
    }

    const tag = `v${versionName}`;
    await page.goto(`https://github.com/${OWNER}/${REPO}/releases/new`, { waitUntil: 'networkidle2', timeout: 25000 });
    await page.evaluate((t, vc) => {
      const tagInp = document.querySelector('#release_tag_name');
      if (tagInp) { tagInp.value = t; tagInp.dispatchEvent(new Event('input', { bubbles: true })); }
      const nameInp = document.querySelector('#release_name');
      if (nameInp) { nameInp.value = `WA Çevirmen Asistan ${t}`; nameInp.dispatchEvent(new Event('input', { bubbles: true })); }
      const bodyInp = document.querySelector('#release_body');
      if (bodyInp) { bodyInp.value = `versionCode: ${vc}\nWA Cevirmen Asistan ${t} OTA guncellemesi`; bodyInp.dispatchEvent(new Event('input', { bubbles: true })); }
    }, tag, versionCode);

    const fileInput = await page.$('input[type="file"]');
    if (!fileInput) throw new Error('Release APK dosya yukleme alani bulunamadi.');
    await fileInput.uploadFile(APK_PATH);
    await new Promise((r) => setTimeout(r, 3500));

    const pubBtn = await page.$('button[type="submit"][value="publish"], button[data-action*="publish"]');
    if (!pubBtn) throw new Error('Publish release butonu bulunamadi.');
    await pubBtn.click();
    await page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
    console.log('GITHUB_RELEASE_URL:', page.url());
  } finally {
    browser.disconnect();
  }
}

async function main() {
  if (!fs.existsSync(APK_PATH)) {
    throw new Error(`APK bulunamadi: ${APK_PATH}`);
  }
  const version = readAppVersion();
  const token = process.env.GITHUB_TOKEN || '';
  if (token) {
    await publishViaApiToken(token, version);
  } else {
    await publishViaChromeSession(version);
  }
}

main().catch((err) => {
  console.error('PUBLISH_ERROR:', err.message);
  process.exit(1);
});
