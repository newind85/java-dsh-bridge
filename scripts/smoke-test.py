#!/usr/bin/env python3
"""Exercise the packaged Bridge with a published DSH and a local model stub."""

import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request


REPLY = 'bridge smoke reply'


class ModelHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path.split('?', 1)[0] != '/v1/messages':
            self.send_error(404)
            return
        self.rfile.read(int(self.headers['Content-Length']))
        events = [
            ('message_start', {'message': {'id': 'msg_smoke', 'type': 'message', 'role': 'assistant',
                                           'model': 'mock', 'content': [], 'usage': {'input_tokens': 1,
                                                                                     'output_tokens': 0}}}),
            ('content_block_start', {'index': 0, 'content_block': {'type': 'text', 'text': ''}}),
            ('content_block_delta', {'index': 0, 'delta': {'type': 'text_delta', 'text': REPLY}}),
            ('content_block_stop', {'index': 0}),
            ('message_delta', {'delta': {'stop_reason': 'end_turn', 'stop_sequence': None},
                               'usage': {'output_tokens': 4}}),
            ('message_stop', {}),
        ]
        body = ''.join(f'event: {name}\ndata: {json.dumps(dict(fields, type=name))}\n\n'
                       for name, fields in events).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


@contextmanager
def model_stub():
    with ThreadingHTTPServer(('127.0.0.1', 0), ModelHandler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f'http://127.0.0.1:{server.server_port}/v1'
        finally:
            server.shutdown()
            thread.join()


@contextmanager
def bridge(jar, executable, workspace, model_url):
    # Isolate both model credentials and DSH's user configuration from the runner.
    environment = {name: os.environ[name] for name in ('PATH', 'TMPDIR', 'LANG', 'SystemRoot') if name in os.environ}
    environment.update(HOME=str(workspace), USERPROFILE=str(workspace), DSH_HOME=str(workspace / 'dsh-home'),
                       DEEPSEEK_API_KEY='local-model-stub', DEEPSEEK_BASE_URL=model_url,
                       DSH_TELEMETRY_DISABLED='1', DSH_PRIMARY_RUNTIME='')
    if executable:
        environment['DSH_EXECUTABLE'] = executable
    java = shutil.which('java')
    require(java is not None, 'JDK 21 must be available on PATH')
    command = [java, '-jar', str(jar), '--server.port=0', '--server.address=127.0.0.1',
               f'--dsh.bridge.working-directory={workspace / "work"}', '--dsh.bridge.run-timeout=30s',
               '--spring.output.ansi.enabled=never']
    log_path = workspace / 'bridge.log'
    with log_path.open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=workspace, env=environment, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline and process.poll() is None:
                match = re.search(r'Tomcat started on port (\d+)', log_path.read_text(encoding='utf-8'))
                if match:
                    yield f'http://127.0.0.1:{match[1]}'
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError('Bridge did not start')
        except Exception:
            print(log_path.read_text(encoding='utf-8')[-6000:])
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                    raise


def post(address, path, text, session_id=None):
    payload = {'text': text}
    if session_id is not None:
        payload['sessionId'] = session_id
    request = urllib.request.Request(address + path, data=json.dumps(payload).encode(), headers={
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream' if path.endswith('/events') else 'application/json',
    })
    # Avoid proxy settings for both Bridge calls and the model stub.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with client.open(request, timeout=38) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        with error:
            return error.code, error.read().decode()


def stream_events(body):
    events = []
    for frame in body.replace('\r\n', '\n').split('\n\n'):
        name = None
        data = []
        for line in frame.splitlines():
            if line.startswith('event:'):
                name = line.removeprefix('event:').lstrip()
            elif line.startswith('data:'):
                data.append(line.removeprefix('data:').lstrip())
        if name and data:
            events.append((name, json.loads('\n'.join(data))))
    return events


def check_turn(result, session_id):
    require(result.get('sessionId') == session_id and re.fullmatch(r'session-[0-9a-f]{32}', session_id or ''), result)
    require(result.get('text') == REPLY and result.get('finishReason') == 'completed', result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--dsh', type=Path, help='DSH executable, defaults to PATH lookup')
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    # A venv launcher may be a symlink. Preserve its invocation path.
    executable = str(args.dsh.absolute()) if args.dsh else None
    with tempfile.TemporaryDirectory(prefix='dsh-bridge-smoke-') as temp, model_stub() as model_url:
        workspace = Path(temp)
        (workspace / 'work').mkdir()
        (workspace / 'dsh-home').mkdir()
        with bridge(jar, executable, workspace, model_url) as address:
            status, body = post(address, '/run', 'Reply with plain text.')
            require(status == 200, (status, body))
            first = json.loads(body)
            session_id = first['sessionId']
            check_turn(first, session_id)

            status, body = post(address, '/run/events', 'Continue the conversation.', session_id)
            require(status == 200, (status, body))
            events = stream_events(body)
            require(events and events[-1][0] == 'result' and any(name == 'session.event' for name, _ in events),
                    'Missing SSE session events or terminal result')
            check_turn(events[-1][1], session_id)

            status, body = post(address, '/run/events', '/goal', session_id)
            require(status == 200, (status, body))
            events = stream_events(body)
            command_events = [data['event']['type'] for name, data in events if name == 'session.event']
            require('command/run' in command_events and 'command/done' in command_events,
                    'Native command lifecycle was not observed')
            require(events[-1][0] == 'result' and events[-1][1]['finishReason'] == 'completed', events[-1])

            status, body = post(address, '/run', 'Test unknown session.', 'session-' + '0' * 32)
            require(status == 404 and json.loads(body).get('code') == 'SESSION_NOT_FOUND', (status, body))

        with bridge(jar, executable, workspace, model_url) as address:
            status, body = post(address, '/run', 'Continue after restart.', session_id)
            require(status == 200, (status, body))
            check_turn(json.loads(body), session_id)
        print('PASS: /run, SSE, native command, unknown session, and session resume after restart')


if __name__ == '__main__':
    main()
