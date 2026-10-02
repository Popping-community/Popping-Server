"""Admit a MAINT candidate after same-port readiness and authenticated smoke checks.

This is a local validation tool, not a rolling-deployment orchestrator. It never
drains or recreates the old server. All control and HTTP endpoints must be loopback.
"""
import argparse
import csv
import http.cookiejar
import io
import json
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def local_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or parsed.username or parsed.password:
        raise ValueError('Only explicit http://127.0.0.1 endpoints are allowed')
    return value.rstrip('/')


def request(opener, url: str, marker: str | None = None) -> dict:
    started = time.monotonic()
    try:
        response = opener.open(url, timeout=2)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read().decode('utf-8', errors='replace')
        try:
            health_up = json.loads(body).get('status') == 'UP'
        except (ValueError, AttributeError):
            health_up = False
        return {'status': response.status, 'health_up': health_up,
                'marker_found': marker in body if marker else None,
                'elapsed_ms': (time.monotonic() - started) * 1000}


class Runtime:
    def __init__(self, port: int):
        self.port = port

    def command(self, command: str) -> str:
        with socket.create_connection(('127.0.0.1', self.port), timeout=2) as connection:
            connection.sendall((command + '\n').encode('ascii'))
            connection.shutdown(socket.SHUT_WR)
            chunks = []
            while chunk := connection.recv(65536):
                chunks.append(chunk)
        return b''.join(chunks).decode()

    def server(self, target: str) -> dict:
        backend, name = target.split('/')
        raw = self.command('show stat').lstrip('# ')
        return next(row for row in csv.DictReader(io.StringIO(raw))
                    if row['pxname'] == backend and row['svname'] == name)

    def state(self, target: str, state: str) -> None:
        output = self.command(f'set server {target} state {state}')
        if output.strip():
            raise RuntimeError(f'HAProxy rejected state change: {output.strip()}')


def admit(runtime, target: str, old: str, probe, smoke, timeout: float,
          sleep=time.sleep, clock=time.monotonic) -> dict:
    if target == old:
        raise ValueError('Candidate and old server must differ')
    before = runtime.server(target)
    if not before['status'].startswith('MAINT'):
        raise ValueError('Candidate must already be in MAINT; active servers are never modified')
    if runtime.server(old)['status'] != 'UP':
        raise ValueError('Old server must be UP')
    evidence = {'admitted': False, 'attempts': [], 'initial_candidate': before}
    deadline = clock() + timeout
    streak = 0
    while clock() < deadline:
        try:
            readiness, business = probe(), smoke()
            good = readiness['status'] == 200 and readiness['health_up'] and business['status'] == 200 and business['marker_found']
            evidence['attempts'].append({'readiness': readiness, 'business': business})
            streak = streak + 1 if good else 0
        except (OSError, ValueError) as error:
            evidence['attempts'].append({'error_type': type(error).__name__})
            streak = 0
        if streak >= 3:
            break
        sleep(.5)
    if streak < 3:
        evidence['reason'] = 'readiness_or_authenticated_smoke_timeout'
        evidence['final_candidate'] = runtime.server(target)
        evidence['old_status'] = runtime.server(old)['status']
        return evidence
    if runtime.server(old)['status'] != 'UP' or not runtime.server(target)['status'].startswith('MAINT'):
        raise RuntimeError('Server state changed before admission')
    try:
        runtime.state(target, 'ready')
        deadline = clock() + timeout
        while clock() < deadline:
            current = runtime.server(target)
            if current['status'] == 'UP' and current['check_status'] == 'L7OK':
                evidence.update(admitted=True, reason='readiness_smoke_and_proxy_checks_passed', final_candidate=current)
                return evidence
            sleep(.5)
        raise RuntimeError('Proxy did not confirm UP/L7OK')
    except Exception as error:
        evidence['admission_error'] = str(error)
        try:
            runtime.state(target, 'maint')
            evidence['final_candidate'] = runtime.server(target)
            if not evidence['final_candidate']['status'].startswith('MAINT'):
                raise RuntimeError('Candidate MAINT was not confirmed')
            evidence.update(admitted=False, reason='admission_failed_maint_confirmed')
        except Exception as recovery_error:
            evidence.update(admitted=None, reason='admission_unknown_maint_unconfirmed',
                            recovery_error=str(recovery_error))
        return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--admin-port', type=int, required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--old', required=True)
    parser.add_argument('--candidate-url', type=local_url, required=True)
    parser.add_argument('--cookie-file', type=Path, required=True)
    parser.add_argument('--marker', required=True)
    parser.add_argument('--timeout', type=float, default=10)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for target in (args.target, args.old):
        if not re.fullmatch(r'[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+', target):
            parser.error('Expected backend/server identifiers')
    if args.timeout <= 0 or not 1 <= args.admin_port <= 65535:
        parser.error('Timeout and admin port must be positive and valid')
    # Reserve the output before any server mutation; no evidence overwrite.
    with args.output.open('x', encoding='utf-8') as output:
        jar = http.cookiejar.MozillaCookieJar(str(args.cookie_file))
        jar.load(ignore_discard=True)
        anonymous = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        authenticated = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                                    urllib.request.HTTPCookieProcessor(jar))
        try:
            result = admit(Runtime(args.admin_port), args.target, args.old,
                           lambda: request(anonymous, args.candidate_url + '/readyz'),
                           lambda: request(authenticated, args.candidate_url + '/boards/new', args.marker), args.timeout)
        except Exception as error:
            result = {'admitted': False, 'reason': type(error).__name__, 'detail': str(error)}
        json.dump(result, output, ensure_ascii=False, indent=2)
    print(json.dumps({'admitted': result['admitted'], 'reason': result['reason']}))
    return 0 if result['admitted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
