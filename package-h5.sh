#!/usr/bin/env bash
set -euo pipefail

# 使用前，请将脚本放在项目根目录或通过 BASE_DIR 指定目录
BASE_DIR="${BASE_DIR:-$(pwd)}"
cd "$BASE_DIR"

echo "PWD=$(pwd)"
OUT_DIR="release"
mkdir -p scripts "$OUT_DIR"

cat <<'EOF' > scripts/package-h5.js
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const https = require('https');
const AdmZip = require('adm-zip');

// 使用前请设置实际资源地址，例如 https://xxx/xxx
const BASE = process.env.H5_PATCH_BASE_URL || 'https://xxx/xxx';
const BUILD_DIR = process.env.BUILD_DIR || 'dist';
const OUT_DIR = 'release';
const LASTVERSION_URL = `${BASE}/lastversion`;
const KEEP_PREVIOUS = process.env.KEEP_PREVIOUS !== 'false'; // 默认保留上一版本（仅用于 patch 基线，不再镜像输出）
const PATCH_WORK_DIR = path.join(OUT_DIR, '.patch_work');

function log(msg) { console.log('[pack]', msg); }

function fetchLastVersion() {
  return new Promise(resolve => {
    log(`GET lastversion: ${LASTVERSION_URL}`);
    https.get(LASTVERSION_URL, res => {
      log(`lastversion status=${res.statusCode}`);
      if (res.statusCode !== 200) return resolve(null);
      let data = '';
      res.on('data', c => (data += c));
      res.on('end', () => {
        try {
          const txt = data.trim();
          log(`lastversion body="${txt}"`);
          if (txt.startsWith('{')) return resolve(JSON.parse(txt).version || null);
          if (/^v?\d+$/i.test(txt)) return resolve(parseInt(txt.replace(/v/i, ''), 10));
          resolve(null);
        } catch (e) {
          log(`parse lastversion failed: ${e.message}`);
          resolve(null);
        }
      });
    }).on('error', (e) => { log(`lastversion request error: ${e.message}`); resolve(null); });
  });
}

function fetchManifest(version) {
  const url = `${BASE}/v${version}/manifest.json`;
  return new Promise(resolve => {
    log(`GET manifest v${version}: ${url}`);
    https.get(url, res => {
      if (res.statusCode !== 200) return resolve(null);
      let data = '';
      res.on('data', c => (data += c));
      res.on('end', () => {
        try { resolve(JSON.parse(data)); } catch { resolve(null); }
      });
    }).on('error', () => resolve(null));
  });
}

function download(url, target) {
  return new Promise((resolve, reject) => {
    log(`download ${url} -> ${target}`);
    https.get(url, res => {
      if (res.statusCode !== 200) return reject(new Error(`http ${res.statusCode} for ${url}`));
      const ws = fs.createWriteStream(target);
      res.pipe(ws);
      ws.on('finish', () => ws.close(resolve));
      ws.on('error', reject);
    }).on('error', reject);
  });
}

function sha256(file) {
  const h = crypto.createHash('sha256');
  h.update(fs.readFileSync(file));
  return h.digest('hex');
}

function rmrf(target) {
  fs.rmSync(target, { recursive: true, force: true });
}

function listFiles(dir, base = dir) {
  if (!fs.existsSync(dir)) return [];
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...listFiles(full, base));
    } else if (entry.isFile()) {
      files.push(path.relative(base, full));
    }
  }
  return files;
}

function buildFileMap(dir) {
  const map = new Map();
  for (const rel of listFiles(dir)) {
    const posix = rel.split(path.sep).join('/');
    map.set(posix, sha256(path.join(dir, rel)));
  }
  return map;
}

function diffDirs(oldDir, newDir) {
  const oldMap = buildFileMap(oldDir);
  const newMap = buildFileMap(newDir);
  const changed = [];
  const deleted = [];
  for (const [rel, hash] of newMap.entries()) {
    const oldHash = oldMap.get(rel);
    if (oldHash !== hash) changed.push(rel);
  }
  for (const rel of oldMap.keys()) {
    if (!newMap.has(rel)) deleted.push(rel);
  }
  return { changed, deleted };
}

function copyChangedFiles(srcDir, destDir, relPaths) {
  for (const rel of relPaths) {
    const src = path.join(srcDir, rel);
    const dest = path.join(destDir, rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(src, dest);
  }
}

async function generatePatch(prevVersion, versionName, versionDir) {
  if (!prevVersion || prevVersion <= 0) {
    log('skip patch: no previous version');
    return {};
  }
  const prevManifest = await fetchManifest(prevVersion);
  if (!prevManifest || !prevManifest.url) {
    log('skip patch: previous manifest missing');
    return {};
  }
  try {
    rmrf(PATCH_WORK_DIR);
    fs.mkdirSync(PATCH_WORK_DIR, { recursive: true });
    const prevZipPath = path.join(PATCH_WORK_DIR, 'prev.zip');
    const prevDir = path.join(PATCH_WORK_DIR, 'prev');
    const patchDir = path.join(PATCH_WORK_DIR, 'patch');

    await download(prevManifest.url, prevZipPath);
    new AdmZip(prevZipPath).extractAllTo(prevDir, true);

    const { changed, deleted } = diffDirs(prevDir, BUILD_DIR);
    if (changed.length === 0 && deleted.length === 0) {
      log('no diff with previous version, abort release');
      throw new Error('NO_DIFF');
    }

    copyChangedFiles(BUILD_DIR, patchDir, changed);

    const patchZipPath = path.join(versionDir, 'patch.zip');
    const patchZip = new AdmZip();
    patchZip.addLocalFolder(patchDir);
    patchZip.writeZip(patchZipPath);

    const patchMeta = {
      patchFrom: prevVersion,
      patchUrl: `${BASE}/${versionName}/patch.zip`,
      patchHash: sha256(patchZipPath),
      patchSize: fs.statSync(patchZipPath).size,
      deleted,
    };
    log(`patch generated from v${prevVersion} -> ${versionName}, changed=${changed.length}, deleted=${deleted.length}`);
    return patchMeta;
  } catch (e) {
    log(`patch generation failed: ${e.message}`);
    if (e.message === 'NO_DIFF') throw e;
    return {};
  } finally {
    rmrf(PATCH_WORK_DIR);
  }
}

(async () => {
  const latest = (await fetchLastVersion()) || 1;
  const next = latest + 1;
  const versionName = `v${next}`;
  const versionDir = path.join(OUT_DIR, versionName);
  log(`latest=${latest} -> next=${next}`);
  fs.mkdirSync(versionDir, { recursive: true });

  try {
    const patchMeta = await generatePatch(latest, versionName, versionDir);

    const zipPath = path.join(versionDir, 'dist.zip');
    log(`zip from ${BUILD_DIR} -> ${zipPath}`);
    const zip = new AdmZip();
    zip.addLocalFolder(BUILD_DIR);
    zip.writeZip(zipPath);

    const hash = sha256(zipPath);
    const size = fs.statSync(zipPath).size;

    const manifest = {
      version: next,
      url: `${BASE}/${versionName}/dist.zip`,
      hash,
      size,
    };
    if (patchMeta.patchFrom) {
      manifest.patchFrom = patchMeta.patchFrom;
      manifest.patchUrl = patchMeta.patchUrl;
      manifest.patchHash = patchMeta.patchHash;
      manifest.patchSize = patchMeta.patchSize;
      manifest.deleted = patchMeta.deleted || [];
    }
    fs.writeFileSync(path.join(versionDir, 'manifest.json'), JSON.stringify(manifest, null, 2));
    fs.writeFileSync(path.join(OUT_DIR, 'lastversion'), JSON.stringify({ version: next }));

    log(`DONE version=${next} hash=${hash} size=${size} patch=${patchMeta.patchUrl ? 'yes' : 'no'}`);
  } catch (e) {
    if (e.message === 'NO_DIFF') {
      log('NO_DIFF detected, abort release without updating lastversion');
    } else {
      log(`package failed: ${e.message}`);
    }
    rmrf(versionDir);
    process.exit(1);
  }
})();
EOF

echo "=== yarn install ==="
yarn install --frozen-lockfile

echo "=== yarn build ==="
if yarn run -s build-dev:h5; then
  echo "use build-dev:h5"
elif yarn run -s build:h5; then
  echo "fallback to build:h5"
else
  echo "找不到 build-dev:h5/build:h5 脚本" && exit 1
fi

echo "=== package ==="
node scripts/package-h5.js

echo "=== outputs ==="
ls -l "$OUT_DIR" || true
ls -l "$OUT_DIR"/* || true
