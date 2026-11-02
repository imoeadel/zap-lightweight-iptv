#!/usr/bin/env python3
"""A pretend IPTV server for testing Zap without a real subscription. Login: test / test.

    dev/fake_xtream.py [--port 8099] [--channels 5000] [--movies 60000] [--series 15000]

Serves the Xtream Codes API, poster images and looping test video from dev/media (see dev/make_media.sh).
"""
import argparse
import base64
import json
import os
import random
import struct
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
MEDIA = os.path.join(HERE, "media")

EN = ["News", "Sports", "Cinema", "Drama", "Family", "Kids", "Music", "Nature", "World", "Classic", "Star", "Gold",
      "Silver", "Prime", "Comedy", "Action", "History", "Science", "Travel", "Food"]
AR = ["الجزيرة", "مصر", "القاهرة", "النيل", "الأخبار", "رياضة", "سينما", "دراما", "الحياة", "الأولى", "أبوظبي", "دبي",
      "روتانا", "قناة", "أفلام", "مسلسلات", "كوميديا", "أطفال", "موسيقى", "وثائقي"]
LIVE_CATS = ["Egypt", "Saudi Arabia", "UAE", "beIN Sports", "OSN", "MBC", "UK", "USA", "Germany", "France", "Kids", "News"]
VOD_CATS = ["Arabic Movies", "English Movies", "Action", "Comedy", "Drama", "Horror", "Family", "Documentary", "4K", "Netflix", "Egyptian Classics", "Turkish"]
SERIES_CATS = ["Ramadan 2026", "Arabic Series", "Turkish Series", "English Series", "Netflix", "Anime", "Kids", "Korean"]


def png(width, height, seed):
    """A flat, solid-color PNG that needs no imaging library."""
    r = random.Random(seed)
    color = bytes(r.randint(30, 200) for _ in range(3))
    raw = b"".join(b"\x00" + color * width for _ in range(height))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 1)) + chunk(b"IEND", b""))


def title(r, words_pool, words=2):
    return " ".join(r.choice(words_pool) for _ in range(words))


def build(args, base):
    r = random.Random(7)
    data = {"get_live_categories": [{"category_id": str(i + 1), "category_name": n, "parent_id": 0} for i, n in enumerate(LIVE_CATS)],
            "get_vod_categories": [{"category_id": str(i + 100), "category_name": n, "parent_id": 0} for i, n in enumerate(VOD_CATS)],
            "get_series_categories": [{"category_id": str(i + 200), "category_name": n, "parent_id": 0} for i, n in enumerate(SERIES_CATS)]}
    live = []
    named = ["MBC 1", "MBC 2", "beIN Sports 1 HD", "beIN Sports 2 HD", "Al Jazeera", "الجزيرة مباشر", "قناة النيل", "OSN Movies"]
    for i in range(args.channels):
        pool = AR if i % 3 == 0 else EN
        name = named[i] if i < len(named) else f"{title(r, pool)} {i}"
        live.append({"num": str(i + 1), "name": name, "stream_type": "live", "stream_id": str(1000 + i),
                     "stream_icon": f"{base}/img/{i % 50}.png?w=88&h=60", "epg_channel_id": f"c{i}", "added": "1700000000",
                     "category_id": str(i % len(LIVE_CATS) + 1), "tv_archive": 1 if i % 2 == 0 else 0, "tv_archive_duration": "3", "direct_source": ""})
    data["get_live_streams"] = live
    vod = []
    for i in range(args.movies):
        pool = AR if i % 4 == 0 else EN
        vod.append({"num": i + 1, "name": f"{title(r, pool, 3)} {i}", "stream_type": "movie", "stream_id": 100000 + i,
                    "stream_icon": f"{base}/img/{i % 50}.png?w=232&h=340", "rating": str(round(r.uniform(4, 9), 1)),
                    "added": str(1700000000 + i * 60), "category_id": str(i % len(VOD_CATS) + 100),
                    "container_extension": "mkv", "direct_source": ""})
    data["get_vod_streams"] = vod
    series = []
    for i in range(args.series):
        pool = AR if i % 2 == 0 else EN
        series.append({"num": i + 1, "name": f"{title(r, pool)} {i}", "series_id": 500000 + i,
                       "cover": f"{base}/img/{i % 50}.png?w=232&h=340", "rating": str(round(r.uniform(4, 9), 1)),
                       "last_modified": str(1700000000 + i), "category_id": str(i % len(SERIES_CATS) + 200), "backdrop_path": []})
    data["get_series"] = series
    return {k: json.dumps(v, ensure_ascii=False).encode() for k, v in data.items()}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    catalog = {}
    base = ""

    def log_message(self, *a):
        pass

    def send(self, body, ctype="application/json", code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        try:
            if u.path == "/player_api.php":
                return self.api(q)
            if u.path.startswith("/img/"):
                seed = u.path.split("/")[-1].split(".")[0]
                return self.send(png(int(q.get("w", 88)), int(q.get("h", 60)), seed), "image/png")
            parts = u.path.strip("/").split("/")
            if len(parts) == 4 and parts[0] == "live" and parts[1:3] == ["test", "test"]:
                return self.live()
            if len(parts) == 6 and parts[0] == "timeshift" and parts[1:3] == ["test", "test"]:
                return self.file(os.path.join(MEDIA, "live.ts"), "video/mp2t")
            if len(parts) == 4 and parts[0] in ("movie", "series") and parts[1:3] == ["test", "test"]:
                name = "vod.mkv" if parts[3].endswith(".mkv") else "vod.mp4"
                return self.file(os.path.join(MEDIA, name), "video/x-matroska" if name.endswith("mkv") else "video/mp4")
            self.send(b"not found", "text/plain", 404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    recent = []

    def api(self, q):
        # LIMIT_PER_10S imitates a provider's rate limit, including Retry-After.
        limit = int(os.environ.get("LIMIT_PER_10S", "0"))
        if limit:
            now = time.time()
            Handler.recent = [t for t in Handler.recent if now - t < 10] + [now]
            print(f"{now:.1f} {q.get('action')} recent={len(Handler.recent)}", flush=True)
            if len(Handler.recent) > limit:
                self.send_response(429)
                self.send_header("Retry-After", "20")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
        if q.get("username") != "test" or q.get("password") != "test":
            return self.send(json.dumps({"user_info": {"auth": 0}}).encode())
        action = q.get("action")
        if action is None:
            return self.send(json.dumps({"user_info": {
                "username": "test", "auth": 1, "status": "Active", "exp_date": str(int(time.time()) + 86400 * 200),
                "max_connections": "1", "allowed_output_formats": ["m3u8", "ts"]}, "server_info": {"url": "fake", "timezone": "Africa/Cairo"}}).encode())
        if action in self.catalog:
            return self.send(self.catalog[action])
        if action in ("get_short_epg", "get_simple_data_table"):
            now = int(time.time())
            sid = int(q.get("stream_id", 0))
            b64 = lambda s: base64.b64encode(s.encode()).decode()
            length = 1800 + (sid % 4) * 900
            first = now - now % length
            names = ["نشرة الأخبار", "Evening Film", "Sports Tonight", "وثائقي", "Morning Show", "مسلسل"]
            span = range(-24, 0) if action == "get_simple_data_table" else range(0, 16)
            return self.send(json.dumps({"epg_listings": [
                {"title": b64(names[(sid + n) % len(names)]), "description": b64("Top stories and analysis from around the region."),
                 "start_timestamp": str(first + n * length), "stop_timestamp": str(first + (n + 1) * length)}
                for n in span]}).encode())
        if action == "get_vod_info":
            return self.send(json.dumps({"info": {
                "plot": "A test title with enough plot text to see how a long description wraps and truncates on the details screen when it runs past a few lines of text at television viewing distance.",
                "genre": "Drama, Family", "releasedate": "2024-03-01", "rating": "7.8", "duration": "00:01:00",
                "cast": "First Actor, Second Actor", "backdrop_path": [f"{self.base}/img/9.png?w=960&h=540"],
                "movie_image": f"{self.base}/img/9.png?w=352&h=520"},
                "movie_data": {"container_extension": "mkv"}}).encode())
        if action == "get_series_info":
            sid = q.get("series_id", "1")
            eps = {str(s): [{"id": str(int(sid) * 100 % 90000000 + s * 100 + e), "episode_num": e, "season": s, "title": f"Episode title {e}",
                             "container_extension": "mp4", "info": {"duration": "00:01:00", "plot": "An episode."}}
                            for e in range(1, 13)] for s in range(1, 4)}
            return self.send(json.dumps({"seasons": [], "info": {
                "plot": "A test series with several seasons.", "genre": "Drama", "releaseDate": "2023-01-01", "rating": "8.1",
                "cast": "Series Cast", "backdrop_path": [f"{self.base}/img/3.png?w=960&h=540"], "cover": f"{self.base}/img/3.png?w=352&h=520"},
                "episodes": eps}).encode())
        self.send(b"[]")

    def live(self):
        """An endless, real-time MPEG-TS stream, with the first second sent at once like a real panel's burst."""
        if os.environ.get("LIVE_429"):
            # Imitates a provider refusing streams, for testing that the player backs off.
            print(f"{time.time():.1f} live-429", flush=True)
            self.send_response(429)
            self.send_header("Retry-After", "20")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        path = os.path.join(MEDIA, os.environ.get("LIVE_FILE", "live.ts"))
        with open(path, "rb") as f:
            blob = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        rate = int(os.environ.get("LIVE_BYTES_PER_SEC", "250000"))
        chunk = 188 * 133
        self.wfile.write(blob[:rate])
        pos = rate
        while True:
            self.wfile.write(blob[pos:pos + chunk])
            pos = (pos + chunk) % (len(blob) // 188 * 188)
            time.sleep(chunk / rate)

    def file(self, path, ctype):
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            a, b = rng[6:].split("-")
            start = int(a or 0)
            end = int(b) if b else end
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                b = f.read(min(65536, left))
                if not b:
                    break
                self.wfile.write(b)
                left -= len(b)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8099)
    p.add_argument("--host", default="10.0.2.2", help="address the app reaches this server on; 10.0.2.2 is the emulator's host")
    p.add_argument("--channels", type=int, default=5000)
    p.add_argument("--movies", type=int, default=60000)
    p.add_argument("--series", type=int, default=15000)
    a = p.parse_args()
    Handler.base = f"http://{a.host}:{a.port}"
    Handler.catalog = build(a, Handler.base)
    print({k: f"{len(v) // 1024} KB" for k, v in Handler.catalog.items() if "categories" not in k}, flush=True)
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
