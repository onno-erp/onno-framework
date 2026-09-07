#!/usr/bin/env python3
"""Expose only the signed WhatsApp webhook through a local development tunnel."""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

ROUTE = '/api/crm/whatsapp/webhook'
MAX_BODY = 1_048_576

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Query strings can contain the private verify token.

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        if urlsplit(self.path).path != ROUTE or not self.path.startswith(ROUTE):
            self.send_error(404)
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            self.send_error(400)
            return
        if length < 0 or length > MAX_BODY or self.headers.get('Transfer-Encoding'):
            self.send_error(413)
            return
        body = self.rfile.read(length)
        headers = {'Content-Type': self.headers.get('Content-Type', 'application/json')}
        if self.headers.get('X-Hub-Signature-256'):
            headers['X-Hub-Signature-256'] = self.headers['X-Hub-Signature-256']
        connection = http.client.HTTPConnection('127.0.0.1', self.server.crm_port, timeout=30)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read(MAX_BODY + 1)
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'text/plain'))
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (OSError, http.client.HTTPException):
            self.send_error(502)
        finally:
            connection.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8091)
    parser.add_argument('--crm-port', type=int, default=8090)
    args = parser.parse_args()
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.crm_port = args.crm_port
    print(f'Webhook-only proxy on 127.0.0.1:{args.port}', flush=True)
    server.serve_forever()
