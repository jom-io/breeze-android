#!/usr/bin/env bash
set -euo pipefail

# 使用前，请将脚本放在 qingcos-h5 根目录或通过 BASE_DIR 指定目录
BASE_DIR="${BASE_DIR:-$(pwd)}"
cd "$BASE_DIR"

echo "PWD=$(pwd)"
mkdir -p scripts release/dev

cat <<'EOF' > scripts/package-h5.js
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const https = require('https');
const AdmZip = require('adm-zip');

const BASE = process.env.BASE_URL || 'https://qingcos-res.oss-cn-hangzhou.aliyuncs.com/h5/patch/dev';
const BUILD_DIR = process.env.BUILD_DIR || 'dist';
const OUT_DIR = process.env.OUT_DIR || 'release/dev';
const LASTVERSION_URL = `${BASE}/lastversion`;
const KEEP_PREVIOUS = process.env.KEEP_PREVIOUS !== 'false'; // 默认保留上一版本

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

(async () => {
  const latest = (await fetchLastVersion()) || 1;
  const next = latest + 1;
  const versionName = `v${next}`;
  const versionDir = path.join(OUT_DIR, versionName);
  log(`latest=${latest} -> next=${next}`);
  fs.mkdirSync(versionDir, { recursive: true });

  // 保留上一版本（如果能从 OSS 拿到）
  if (KEEP_PREVIOUS && latest > 0) {
    const prevDir = path.join(OUT_DIR, `v${latest}`);
    if (!fs.existsSync(prevDir)) {
      const mf = await fetchManifest(latest);
      if (mf && mf.url) {
        fs.mkdirSync(prevDir, { recursive: true });
        const prevZip = path.join(prevDir, 'dict.zip');
        try {
          await download(mf.url, prevZip);
          fs.writeFileSync(path.join(prevDir, 'manifest.json'), JSON.stringify(mf, null, 2));
          log(`mirrored previous v${latest}`);
        } catch (e) {
          log(`mirror previous v${latest} failed: ${e.message}`);
        }
      } else {
        log(`no manifest found for v${latest}, skip mirror`);
      }
    } else {
      log(`previous v${latest} already present locally, skip mirror`);
    }
  }

  const zipPath = path.join(versionDir, 'dict.zip');
  log(`zip from ${BUILD_DIR} -> ${zipPath}`);
  const zip = new AdmZip();
  zip.addLocalFolder(BUILD_DIR);
  zip.writeZip(zipPath);

  const hash = sha256(zipPath);
  const size = fs.statSync(zipPath).size;
  const manifest = { version: next, url: `${BASE}/${versionName}/dict.zip`, hash, size };
  fs.writeFileSync(path.join(versionDir, 'manifest.json'), JSON.stringify(manifest, null, 2));
  fs.writeFileSync(path.join(OUT_DIR, 'lastversion'), JSON.stringify({ version: next }));

  log(`DONE version=${next} hash=${hash} size=${size}`);
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
ls -l release/dev || true
ls -l release/dev/* || true
