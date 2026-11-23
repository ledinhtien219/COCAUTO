import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '..');
const gradleFile = path.join(rootDir, 'app', 'build.gradle.kts');
const apkPath = path.join(rootDir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const port = Number(process.env.PORT || 8080);

function readBuildVersion() {
  const gradle = fs.readFileSync(gradleFile, 'utf8');
  const codeMatch = gradle.match(/versionCode\s*=\s*(\d+)/);
  const nameMatch = gradle.match(/versionName\s*=\s*"([^"]+)"/);
  return {
    versionCode: codeMatch ? Number(codeMatch[1]) : 1,
    versionName: nameMatch ? nameMatch[1] : '1.0.0',
  };
}

function sha256File(filePath) {
  const hash = crypto.createHash('sha256');
  hash.update(fs.readFileSync(filePath));
  return hash.digest('hex');
}

function getLanIps() {
  const nets = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) {
        ips.push(net.address);
      }
    }
  }
  return ips;
}

if (!fs.existsSync(apkPath)) {
  console.error('Chua tim thay file APK:', apkPath);
  console.error('Hay build APK truoc: .\\gradlew.bat assembleDebug');
  process.exit(1);
}

const server = http.createServer((req, res) => {
  const urlPath = (req.url || '/').split('?')[0];

  if (urlPath === '/' || urlPath === '/update.json') {
    const { versionCode, versionName } = readBuildVersion();
    const hash = sha256File(apkPath);
    const manifest = {
      versionCode,
      versionName,
      apkUrl: 'app-debug.apk',
      changelog: `Ban cap nhat CoC Auto v${versionName} (Build ${versionCode}) tu may tinh qua Wi-Fi.`,
      sha256: hash,
      mandatory: false,
    };
    const body = JSON.stringify(manifest, null, 2);
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(body),
    });
    res.end(body);
    console.log(`[GET ${urlPath}] -> v${versionName} (code ${versionCode})`);
    return;
  }

  if (urlPath === '/app-debug.apk') {
    const stat = fs.statSync(apkPath);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': stat.size,
      'Content-Disposition': 'attachment; filename="coc-auto.apk"',
    });
    fs.createReadStream(apkPath).pipe(res);
    console.log(`[GET ${urlPath}] -> dang gui ${(stat.size / 1024 / 1024).toFixed(1)} MB APK...`);
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

server.listen(port, '0.0.0.0', () => {
  const { versionCode, versionName } = readBuildVersion();
  console.log(`\n=== CoC Auto Wi-Fi Update Server (v${versionName} / Build ${versionCode}) ===`);
  console.log('Nhap mot trong cac dia chi sau vao muc "Nguon cap nhat" trong app CoC Auto:\n');
  for (const ip of getLanIps()) {
    console.log(`  http://${ip}:${port}`);
  }
  console.log('\nNhan Ctrl+C de dung server.\n');
});
