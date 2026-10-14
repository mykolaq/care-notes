"""Single-family encrypted snapshot relay. Medical data is opaque ciphertext."""
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BLOB = 105 * 1024 * 1024

def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()

class Relay:
    def __init__(self, path, setup_secret):
        self.path, self.setup_secret = str(path), setup_secret
        self.guard = threading.RLock()
        with self.db() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL, token TEXT UNIQUE NOT NULL);
                CREATE TABLE IF NOT EXISTS invites (token TEXT PRIMARY KEY, role TEXT NOT NULL, expires REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS snapshot (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, body BLOB NOT NULL);
            ''')

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def device(self, token):
        with self.db() as db:
            row = db.execute('SELECT id,name,role FROM devices WHERE token=?', (digest(token),)).fetchone()
            return dict(row) if row else None

    def add_device(self, db, name, role):
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80:
            raise ValueError('Invalid device name')
        if db.execute('SELECT count(*) FROM devices').fetchone()[0] >= 10:
            raise ValueError('Device limit reached')
        token, id_ = secrets.token_urlsafe(32), secrets.token_hex(16)
        db.execute('INSERT INTO devices VALUES (?,?,?,?)', (id_, name.strip(), role, digest(token)))
        return dict(id=id_, token=token, role=role)

    def bootstrap(self, secret, name):
        if not hmac.compare_digest(str(secret), self.setup_secret):
            raise PermissionError('Access denied')
        with self.guard, self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT count(*) FROM devices').fetchone()[0]:
                raise ValueError('Already initialized')
            return self.add_device(db, name, 'owner')

    def invite(self, role):
        if role not in ('viewer', 'editor'):
            raise ValueError('Invalid role')
        with self.guard, self.db() as db:
            db.execute('DELETE FROM invites WHERE expires < ?', (time.time(),))
            if db.execute('SELECT count(*) FROM invites').fetchone()[0] >= 10:
                raise ValueError('Invitation limit reached')
            token = secrets.token_urlsafe(32)
            db.execute('INSERT INTO invites VALUES (?,?,?)', (digest(token), role, time.time() + 600))
            return dict(code=token, expiresIn=600)

    def join(self, code, name):
        with self.guard, self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            invite = db.execute('SELECT role,expires FROM invites WHERE token=?', (digest(str(code)),)).fetchone()
            if not invite or invite['expires'] < time.time():
                raise PermissionError('Invitation expired or already used')
            result = self.add_device(db, name, invite['role'])
            db.execute('DELETE FROM invites WHERE token=?', (digest(str(code)),))
            return result

    def devices(self):
        with self.db() as db:
            return [dict(row) for row in db.execute('SELECT id,name,role FROM devices')]

    def revoke(self, id_):
        with self.guard, self.db() as db:
            db.execute('DELETE FROM devices WHERE id=? AND role<>?', (id_, 'owner'))

    def read(self):
        with self.db() as db:
            row = db.execute('SELECT revision,body FROM snapshot WHERE id=1').fetchone()
            return (row['revision'], row['body']) if row else (0, b'')

    def write(self, token, revision, body):
        if not 32 <= len(body) <= MAX_BLOB:
            raise ValueError('Invalid snapshot size')
        with self.guard, self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            device = db.execute('SELECT role FROM devices WHERE token=?', (digest(token),)).fetchone()
            if not device or device['role'] not in ('owner', 'editor'):
                raise PermissionError('Read-only or revoked device')
            row = db.execute('SELECT revision FROM snapshot WHERE id=1').fetchone()
            current = row[0] if row else 0
            if revision != current:
                raise FileExistsError('Revision conflict')
            db.execute('INSERT OR REPLACE INTO snapshot VALUES (1,?,?)', (current + 1, body))
            return current + 1

def server(relay, address=('0.0.0.0', 8080)):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # No URLs, tokens, or payloads in logs.

        def setup(self):
            super().setup()
            self.connection.settimeout(60)

        def reply(self, code, payload, revision=None):
            body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/octet-stream' if isinstance(payload, bytes) else 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            if revision is not None:
                self.send_header('X-Revision', str(revision))
            self.end_headers()
            self.wfile.write(body)

        def body(self, limit):
            length = int(self.headers.get('Content-Length', '-1'))
            if length < 0 or length > limit:
                raise ValueError('Invalid request size')
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError('Incomplete request')
            return body

        def do_GET(self):
            self.handle_request('GET')

        def do_POST(self):
            self.handle_request('POST')

        def do_PUT(self):
            self.handle_request('PUT')

        def handle_request(self, method):
            try:
                path = self.path
                token = self.headers.get('Authorization', '').removeprefix('Bearer ')
                if method == 'POST' and path in ('/v1/bootstrap', '/v1/join'):
                    # Bound anonymous requests globally (relay runs behind one proxy).
                    with relay.guard:
                        now = time.monotonic()
                        recent = [t for t in getattr(relay, 'attempts', []) if now-t < 60]
                        if len(recent) >= 20:
                            self.reply(429, dict(error='Try later')); return
                        relay.attempts = recent + [now]
                    data = json.loads(self.body(4096))
                    result = relay.bootstrap(data.get('secret', ''), data.get('name')) if path.endswith('bootstrap') else relay.join(data.get('code', ''), data.get('name'))
                    self.reply(200, result); return
                device = relay.device(token)
                if not device:
                    self.reply(401, dict(error='Access denied')); return
                if method == 'GET' and path == '/v1/me':
                    self.reply(200, device)
                elif method == 'GET' and path == '/v1/snapshot':
                    revision, body = relay.read()
                    self.reply(200, body, revision)
                elif method == 'PUT' and path == '/v1/snapshot':
                    if device['role'] == 'viewer':
                        self.reply(403, dict(error='Read only')); return
                    revision = int(self.headers.get('X-Revision', '-1'))
                    self.reply(200, dict(revision=relay.write(token, revision, self.body(MAX_BLOB))))
                elif device['role'] != 'owner':
                    self.reply(403, dict(error='Owner required'))
                elif method == 'GET' and path == '/v1/devices':
                    self.reply(200, dict(devices=relay.devices()))
                elif method == 'POST' and path == '/v1/invites':
                    self.reply(200, relay.invite(json.loads(self.body(4096)).get('role')))
                elif method == 'POST' and path == '/v1/revoke':
                    relay.revoke(json.loads(self.body(4096)).get('id'))
                    self.reply(200, dict(ok=True))
                else:
                    self.reply(404, dict(error='Not found'))
            except PermissionError:
                self.reply(403, dict(error='Access denied'))
            except FileExistsError:
                self.reply(409, dict(error='Revision conflict'))
            except (ValueError, TypeError, KeyError):
                self.reply(400, dict(error='Invalid request'))
            except (BrokenPipeError, TimeoutError, ConnectionError):
                self.close_connection = True
            except Exception:
                self.reply(500, dict(error='Server error'))
            finally:
                self.close_connection = True
    return ThreadingHTTPServer(address, Handler)

if __name__ == '__main__':
    setup = os.environ.get('SETUP_SECRET', '')
    if len(setup) < 32:
        raise SystemExit('SETUP_SECRET must contain at least 32 characters')
    data = Path('/data')
    data.mkdir(exist_ok=True)
    server(Relay(data/'care.sqlite', setup)).serve_forever()
