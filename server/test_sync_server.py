import http.client
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from sync_server import Relay, server

class RelayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.relay = Relay(Path(self.tmp.name)/'test.sqlite', 's'*32)
        self.owner = self.relay.bootstrap('s'*32, 'Owner')

    def tearDown(self):
        self.tmp.cleanup()

    def join(self, role):
        invite = self.relay.invite(role)
        return self.relay.join(invite['code'], role)

    def test_invites_one_time_expired_and_bootstrap_closed(self):
        invite = self.relay.invite('editor')
        self.relay.join(invite['code'], 'Daughter')
        with self.assertRaises(PermissionError): self.relay.join(invite['code'], 'Again')
        with self.assertRaises(ValueError): self.relay.bootstrap('s'*32, 'Again')
        invite = self.relay.invite('viewer')
        with self.relay.db() as db: db.execute('UPDATE invites SET expires=?', (time.time()-1,))
        with self.assertRaises(PermissionError): self.relay.join(invite['code'], 'Expired')

    def test_revoke_write_permissions_and_revision(self):
        editor, viewer = self.join('editor'), self.join('viewer')
        self.assertEqual(1, self.relay.write(editor['token'], 0, b'x'*32))
        with self.assertRaises(PermissionError): self.relay.write(viewer['token'], 1, b'x'*32)
        with self.assertRaises(FileExistsError): self.relay.write(editor['token'], 0, b'x'*32)
        self.relay.revoke(editor['id'])
        self.assertIsNone(self.relay.device(editor['token']))
        with self.assertRaises(PermissionError): self.relay.write(editor['token'], 1, b'x'*32)
        self.relay.revoke(self.owner['id'])
        self.assertIsNotNone(self.relay.device(self.owner['token']))

    def test_no_raw_token_saved(self):
        with self.relay.db() as db:
            tokens = [row[0] for row in db.execute('SELECT token FROM devices')]
        self.assertNotIn(self.owner['token'], tokens)

    def test_http_authorization(self):
        http_server = server(self.relay, ('127.0.0.1', 0))
        thread = threading.Thread(target=http_server.serve_forever, daemon=True); thread.start()
        try:
            def request(method, path, token='', data=None):
                conn = http.client.HTTPConnection('127.0.0.1', http_server.server_port)
                headers = {'Authorization': 'Bearer '+token}
                conn.request(method, path, json.dumps(data) if data else None, headers)
                response = conn.getresponse(); response.read(); code=response.status; conn.close(); return code
            self.assertEqual(401, request('GET', '/v1/snapshot'))
            viewer = self.join('viewer')
            self.assertEqual(403, request('POST', '/v1/invites', viewer['token'], {'role':'editor'}))
            self.assertEqual(403, request('PUT', '/v1/snapshot', viewer['token']))
            self.assertEqual(200, request('GET', '/v1/me', self.owner['token']))
        finally:
            http_server.shutdown(); http_server.server_close(); thread.join()

if __name__ == '__main__': unittest.main()
