import http from 'node:http';
import os from 'node:os';
import fs from 'node:fs';
import { execFile } from 'node:child_process';

const port = Number(process.env.ADB_BRIDGE_PORT || 8085);

function findAdbBinary() {
  const candidates = [
    process.env.ADB_PATH,
    `${process.env.LOCALAPPDATA}\\Android\\Sdk\\platform-tools\\adb.exe`,
    'adb',
  ].filter(Boolean);
  for (const c of candidates) {
    if (c === 'adb' || fs.existsSync(c)) return c;
  }
  return 'adb';
}

const adbBin = findAdbBinary();

function runAdb(args, { binary = false, timeout = 10000 } = {}) {
  return new Promise((resolve, reject) => {
    execFile(
      adbBin,
      args,
      {
        encoding: binary ? 'buffer' : 'utf8',
        maxBuffer: 25 * 1024 * 1024,
        timeout,
      },
      (err, stdout, stderr) => {
        if (err) {
          reject(new Error((stderr && stderr.toString()) || err.message));
        } else {
          resolve(stdout);
        }
      },
    );
  });
}

async function getDeviceSummary() {
  const devicesOut = await runAdb(['devices']);
  const line = devicesOut
    .split(/\r?\n/)
    .slice(1)
    .map((l) => l.trim())
    .find((l) => l.endsWith('\tdevice') || l.includes('\tdevice'));
  if (!line) return { ok: false, serial: '', width: 0, height: 0, foregroundPackage: '' };
  const serial = line.split(/\s+/)[0];

  const [wmOut, rotOut, fgOut] = await Promise.all([
    runAdb(['shell', 'wm', 'size']).catch(() => ''),
    runAdb(['shell', 'dumpsys input | grep SurfaceOrientation | head -n 1']).catch(() => ''),
    runAdb(['shell', "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'"]).catch(() => ''),
  ]);

  const sizeMatch = wmOut.match(/Override size:\s*(\d+)x(\d+)/) || wmOut.match(/Physical size:\s*(\d+)x(\d+)/);
  let width = sizeMatch ? Number(sizeMatch[1]) : 0;
  let height = sizeMatch ? Number(sizeMatch[2]) : 0;
  const rotMatch = rotOut.match(/SurfaceOrientation[^0-9]*([0-3])/);
  const rot = rotMatch ? Number(rotMatch[1]) : 0;
  if ((rot === 1 || rot === 3) && width < height) {
    [width, height] = [height, width];
  }

  const fgMatch =
    fgOut.match(/mCurrentFocus=Window\{[^}]*\s([A-Za-z0-9_.]+)\//) ||
    fgOut.match(/mFocusedApp=ActivityRecord\{[^}]*\s([A-Za-z0-9_.]+)\//);
  const foregroundPackage = fgMatch ? fgMatch[1] : '';

  return { ok: true, serial, width, height, foregroundPackage };
}

function readJsonBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(data || '{}'));
      } catch {
        resolve({});
      }
    });
  });
}

function getLanIps() {
  const nets = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) ips.push(net.address);
    }
  }
  return ips;
}

const server = http.createServer(async (req, res) => {
  const urlPath = (req.url || '/').split('?')[0];
  try {
    if (req.method === 'GET' && urlPath === '/health') {
      const info = await getDeviceSummary();
      const body = JSON.stringify(info);
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(body);
      return;
    }

    if (req.method === 'GET' && urlPath === '/screencap') {
      const png = await runAdb(['exec-out', 'screencap', '-p'], { binary: true, timeout: 12000 });
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length });
      res.end(png);
      return;
    }

    if (req.method === 'GET' && urlPath === '/foreground') {
      const fgOut = await runAdb([
        'shell',
        "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' ; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 3",
      ]);
      const match =
        fgOut.match(/mCurrentFocus=Window\{[^}]*\s([A-Za-z0-9_.]+)\//) ||
        fgOut.match(/(?:topResumedActivity|mResumedActivity)[^\n]*?\s([A-Za-z0-9_.]+)\//) ||
        fgOut.match(/mFocusedApp=ActivityRecord\{[^}]*\s([A-Za-z0-9_.]+)\//);
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ packageName: match ? match[1] : '' }));
      return;
    }

    if (req.method === 'POST' && urlPath === '/tap') {
      const { x = 0, y = 0, holdMs = 55 } = await readJsonBody(req);
      const ix = Math.round(Number(x));
      const iy = Math.round(Number(y));
      const hold = Math.round(Number(holdMs));
      if (hold > 75) {
        await runAdb(['shell', 'input', 'touchscreen', 'swipe', `${ix}`, `${iy}`, `${ix}`, `${iy}`, `${hold}`]);
      } else {
        await runAdb(['shell', 'input', 'tap', `${ix}`, `${iy}`]);
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
      return;
    }

    if (req.method === 'POST' && urlPath === '/swipe') {
      const { x1 = 0, y1 = 0, x2 = 0, y2 = 0, durationMs = 360 } = await readJsonBody(req);
      await runAdb([
        'shell',
        'input',
        'touchscreen',
        'swipe',
        `${Math.round(x1)}`,
        `${Math.round(y1)}`,
        `${Math.round(x2)}`,
        `${Math.round(y2)}`,
        `${Math.round(durationMs)}`,
      ]);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
      return;
    }

    if (req.method === 'POST' && urlPath === '/launch') {
      const { packageName = 'com.supercell.clashofclans' } = await readJsonBody(req);
      await runAdb(['shell', 'monkey', '-p', packageName, '-c', 'android.intent.category.LAUNCHER', '1']);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
      return;
    }

    res.writeHead(404);
    res.end('Not found');
  } catch (err) {
    res.writeHead(500, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: false, error: err.message }));
  }
});

server.listen(port, '0.0.0.0', async () => {
  console.log(`\n=== CoC Auto PC ADB Bridge Server (port ${port}) ===`);
  console.log(`ADB binary: ${adbBin}`);
  try {
    await runAdb(['reverse', `tcp:${port}`, `tcp:${port}`]);
    console.log(`\n[USB] Da tu dong bat adb reverse -> tren dien thoai nhap: http://127.0.0.1:${port}`);
  } catch {
    console.log('\n[USB] Chua thay dien thoai cam cap USB (ban van co the dung qua Wi-Fi ADB).');
  }
  console.log('[Wi-Fi] Hoac dung dia chi mang LAN:');
  for (const ip of getLanIps()) {
    console.log(`  http://${ip}:${port}`);
  }
  console.log('\nNhan Ctrl+C de dung cau noi.\n');
});
