#!/usr/bin/env python3
"""带重试/续传的多镜像分段下载器。"""
import os, sys, time, threading
from concurrent.futures import ThreadPoolExecutor, as_completed
import urllib.request, urllib.error

RAW = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
MIRRORS = [
    "https://gh-proxy.com/" + RAW,
    "https://ghfast.top/" + RAW,
]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sherpa-onnx.aar")
PARTS_DIR = os.path.join(os.path.dirname(OUT), "dlparts")
CHUNK = 2 * 1024 * 1024          # 2MB / 片
WORKERS = 10
MAX_ATTEMPT = 12

os.makedirs(PARTS_DIR, exist_ok=True)

def head_size(url):
    req = urllib.request.Request(url, method="HEAD",
                                headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return int(r.headers["Content-Length"])

print("探测总大小 ...", flush=True)
total = None
last_err = None
for m in MIRRORS:
    try:
        total = head_size(m)
        print(f"  总大小 = {total} 字节 (来自 {m.split('/')[2]})", flush=True)
        break
    except Exception as e:
        last_err = e
if total is None:
    print("HEAD 全部失败:", last_err, flush=True)
    sys.exit(1)

nchunks = (total + CHUNK - 1) // CHUNK
print(f"分片数 = {nchunks}, 每片 {CHUNK} 字节, 并发 {WORKERS}", flush=True)

lock = threading.Lock()
done_count = [0]

def part_path(i):
    return os.path.join(PARTS_DIR, f"p{i:04d}")

def expected_size(i):
    start = i * CHUNK
    end = min(start + CHUNK, total)
    return end - start, start, end - 1

def fetch(i):
    exp, start, end = expected_size(i)
    p = part_path(i)
    if os.path.exists(p) and os.path.getsize(p) == exp:
        with lock:
            done_count[0] += 1
        return True
    for attempt in range(MAX_ATTEMPT):
        url = MIRRORS[attempt % len(MIRRORS)]
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0",
                "Range": f"bytes={start}-{end}",
            })
            with urllib.request.urlopen(req, timeout=90) as r:
                data = r.read()
            if len(data) != exp:
                raise IOError(f"长度不符 {len(data)} != {exp}")
            with open(p, "wb") as f:
                f.write(data)
            with lock:
                done_count[0] += 1
                print(f"  [{done_count[0]:>3}/{nchunks}] part {i:>3} 完成"
                      f" (尝试 {attempt+1})", flush=True)
            return True
        except Exception as e:
            time.sleep(1.0 + attempt * 0.5)
    print(f"  part {i} 彻底失败", flush=True)
    return False

t0 = time.time()
with ThreadPoolExecutor(max_workers=WORKERS) as ex:
    futs = [ex.submit(fetch, i) for i in range(nchunks)]
    ok = all(f.result() for f in as_completed(futs))

print(f"分片阶段结束，耗时 {time.time()-t0:.0f} 秒", flush=True)
if not ok:
    print("存在失败分片，退出", flush=True)
    sys.exit(2)

print("合并中 ...", flush=True)
with open(OUT, "wb") as out:
    for i in range(nchunks):
        with open(part_path(i), "rb") as f:
            out.write(f.read())
size = os.path.getsize(OUT)
print(f"合并完成: {OUT} ({size} 字节), 预期 {total}", flush=True)
if size != total:
    print("大小不符！", flush=True)
    sys.exit(3)
print("OK", flush=True)
