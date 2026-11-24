"""Disposable loopback-only IPTV fixture; no account/provider credentials or external requests."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from datetime import datetime, timedelta, timezone
import os, shutil, subprocess

PORT = 18124
channels = [('bbc-one', 'UK | BBC-ONE FHD', 'News'), ('bbc-two', 'BBC Two', 'News'),
            ('sports-one', 'Sports One', 'Sports'), ('sports-two', 'Sports Two', 'Sports')]
# A real, playable 5-minute test pattern (ffmpeg, generated once) so a UI test can take a channel full screen and
# close the player; without ffmpeg the stream route 404s and only the lineup/guide tests have what they need.
STREAM = '/tmp/tuvora-parity-fixture-stream.ts'
if not os.path.exists(STREAM) and shutil.which('ffmpeg'):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=10', '-f', 'lavfi',
                    '-i', 'sine=frequency=440:sample_rate=44100', '-t', '300', '-c:v', 'libx264', '-preset', 'ultrafast',
                    '-b:v', '150k', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '32k', '-f', 'mpegts', STREAM + '.part'], check=False)
    if os.path.exists(STREAM + '.part'): os.replace(STREAM + '.part', STREAM)

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith('/stream/') and os.path.exists(STREAM):
            size = os.path.getsize(STREAM); start, end = 0, size - 1
            rng = self.headers.get('Range')
            if rng and rng.startswith('bytes='):
                a, _, b = rng[6:].partition('-')
                start = int(a) if a else 0
                end = int(b) if b else size - 1
            self.send_response(206 if rng else 200); self.send_header('Content-Type', 'video/mp2t')
            self.send_header('Accept-Ranges', 'bytes'); self.send_header('Content-Length', str(end - start + 1))
            if rng: self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.end_headers()
            with open(STREAM, 'rb') as f:
                f.seek(start); left = end - start + 1
                try:
                    while left > 0:
                        chunk = f.read(min(65536, left))
                        if not chunk: break
                        self.wfile.write(chunk); left -= len(chunk)
                except (BrokenPipeError, ConnectionResetError): pass
            return
        if self.path.split('?')[0] == '/lineup.m3u':
            lines = [f'#EXTM3U x-tvg-url="http://127.0.0.1:{PORT}/guide.xml"']
            for cid, name, group in channels:
                lines += [f'#EXTINF:-1 tvg-id="{cid}" group-title="{group}",{name}', f'http://127.0.0.1:{PORT}/stream/{cid}.ts']
            body, kind = ('\n'.join(lines)+'\n').encode(), 'application/x-mpegurl'
        elif self.path == '/guide.xml':
            now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
            stamp = lambda d: d.strftime('%Y%m%d%H%M%S +0000')
            rows = ['<?xml version="1.0" encoding="UTF-8"?><tv>']
            for cid, name, _ in channels:
                rows.append(f'<channel id="{cid}"><display-name>{name}</display-name></channel>')
                for hour in range(-2,5):
                    start = now+timedelta(hours=hour)
                    rows.append(f'<programme channel="{cid}" start="{stamp(start)}" stop="{stamp(start+timedelta(hours=1))}"><title>{name} fixture programme</title></programme>')
            rows.append('</tv>'); body, kind = ''.join(rows).encode(), 'application/xml'
        else:
            self.send_error(404); return
        self.send_response(200); self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, format, *args): pass
ThreadingHTTPServer(('127.0.0.1',PORT),Handler).serve_forever()
