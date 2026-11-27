import fs from 'node:fs';
import path from 'node:path';
import { execSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..');
const buildGradlePath = path.join(rootDir, 'app', 'build.gradle.kts');
const apkSourcePath = path.join(rootDir, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
const otaWorkerDir = path.join(rootDir, 'ota-worker');
const otaPublicDir = path.join(otaWorkerDir, 'public');
const otaApkPath = path.join(otaPublicDir, 'WA-Cevirmen-Asistan.apk');
const otaManifestPath = path.join(otaPublicDir, 'version.json');
const rootManifestPath = path.join(rootDir, 'version.json');
const downloadsApkPath = path.join(process.env.USERPROFILE || 'C:\\Users\\Public', 'Downloads', 'WA-Cevirmen-Asistan.apk');
const githubRepo = 'onurozer34/wa-cevirmen-asistan';

const gradleContent = fs.readFileSync(buildGradlePath, 'utf8');
const codeMatch = gradleContent.match(/versionCode\s*=\s*(\d+)/);
const nameMatch = gradleContent.match(/versionName\s*=\s*"([^"]+)"/);

if (!codeMatch || !nameMatch) {
  throw new Error('Could not parse versionCode/versionName from app/build.gradle.kts');
}
if (!fs.existsSync(apkSourcePath)) {
  throw new Error(`Release APK not found at ${apkSourcePath}. Run assembleRelease first.`);
}

const versionCode = Number(codeMatch[1]);
const versionName = nameMatch[1];
const notes = process.argv.slice(2).join(' ') || `WA Çevirmen Asistan v${versionName} (build ${versionCode})`;

fs.mkdirSync(otaPublicDir, { recursive: true });
fs.copyFileSync(apkSourcePath, otaApkPath);
fs.copyFileSync(apkSourcePath, downloadsApkPath);

const sizeBytes = fs.statSync(otaApkPath).size;
const updatedAt = new Date().toISOString();

const manifest = {
  versionCode,
  versionName,
  downloadUrl: 'https://wa-cevirmen-ota.mor-media-drive-sync-bridge.workers.dev/WA-Cevirmen-Asistan.apk',
  notes,
  updatedAt,
  sizeBytes
};

const githubManifest = {
  versionCode,
  versionName,
  downloadUrl: `https://github.com/${githubRepo}/releases/download/v${versionName}/WA-Cevirmen-Asistan.apk`,
  notes,
  updatedAt,
  sizeBytes
};

fs.writeFileSync(otaManifestPath, JSON.stringify(manifest, null, 2) + '\n', 'utf8');
fs.writeFileSync(rootManifestPath, JSON.stringify(githubManifest, null, 2) + '\n', 'utf8');
console.log('OTA manifest written:', manifest);

execSync('npx --yes wrangler deploy', { cwd: otaWorkerDir, stdio: 'inherit' });

const ghAuth = spawnSync('gh', ['auth', 'status'], { stdio: 'ignore' });
if (ghAuth.status === 0) {
  const tag = `v${versionName}`;
  const releaseNotes = `versionCode: ${versionCode}\n${notes}`;
  const hasRelease = spawnSync('gh', ['release', 'view', tag, '-R', githubRepo], { stdio: 'ignore' });
  if (hasRelease.status === 0) {
    execSync(
      `gh release upload ${tag} "${otaApkPath}" --clobber -R ${githubRepo}`,
      { cwd: rootDir, stdio: 'inherit' }
    );
  } else {
    execSync(
      `gh release create ${tag} "${otaApkPath}" -R ${githubRepo} --title "WA Çevirmen Asistan ${tag}" --notes "${releaseNotes.replace(/"/g, '\\"')}"`,
      { cwd: rootDir, stdio: 'inherit' }
    );
  }
} else {
  console.warn('gh CLI is not authenticated; skipped GitHub Release upload.');
}
