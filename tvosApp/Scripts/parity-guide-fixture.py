"""Disposable loopback-only IPTV fixture; no account/provider credentials or external requests."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from datetime import datetime, timedelta, timezone

PORT = 18124
channels = [('bbc-one', 'UK | BBC-ONE FHD', 'News'), ('bbc-two', 'BBC Two', 'News'),
            ('sports-one', 'Sports One', 'Sports'), ('sports-two', 'Sports Two', 'Sports')]
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split('?')[0] == '/lineup.m3u':
            lines = [f'#EXTM3U x-tvg-url="http://127.0.0.1:{PORT}/guide.xml"']
            for cid, name, group in channels:
                lines += [f'#EXTINF:-1 tvg-id="{cid}" group-title="{group}",{name}', f'http://127.0.0.1:{PORT}/unused/{cid}.ts']
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
