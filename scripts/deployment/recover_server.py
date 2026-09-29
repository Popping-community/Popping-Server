"""Recover an isolated failed release without assuming a healthy survivor."""
import argparse
import http.cookiejar
import json
import re
import time
import urllib.request
import urllib.parse
from pathlib import Path

from admit_candidate import NoRedirect, Runtime, local_url, request
from quiesce_server import OwnedContainer, healthy


def excluded(row: dict) -> bool:
    return row['status'].startswith('MAINT')


def recover(runtime, target: str, failed: str, verify, probe, smoke, timeout: float,
            sleep=time.sleep, clock=time.monotonic) -> dict:
    if target == failed or timeout <= 0:
        raise ValueError('Distinct servers and a positive timeout required')
    result = {'recovered': False, 'attempts': [], 'ready_attempted': False}
    try:
        if not runtime.server(target)['status'].startswith('MAINT') or not excluded(runtime.server(failed)):
            raise ValueError('Recovery must be MAINT and failed server excluded')
        verify(runtime.server(target)['addr'])
        deadline, streak = clock() + timeout, 0
        while clock() < deadline:
            readiness, business = probe(), smoke()
            result['attempts'].append({'readiness': readiness, 'business': business})
            good = readiness['status'] == 200 and readiness['health_up'] and business['status'] == 200 and business['marker_found']
            streak = streak + 1 if good else 0
            if streak >= 3:
                break
            sleep(.5)
        if streak < 3 or clock() >= deadline:
            result['reason'] = 'recovery_checks_timeout_no_admission'
            return result
        if not runtime.server(target)['status'].startswith('MAINT') or not excluded(runtime.server(failed)):
            raise RuntimeError('Proxy state changed before recovery')
        verify(runtime.server(target)['addr'])
        if clock() >= deadline:
            result['reason'] = 'recovery_checks_timeout_no_admission'
            return result
        result['ready_attempted'] = True
        runtime.state(target, 'ready')
        deadline = clock() + timeout
        while clock() < deadline:
            current = runtime.server(target)
            if healthy(current):
                verify(current['addr'])
                if not excluded(runtime.server(failed)):
                    raise RuntimeError('Failed server no longer excluded')
                result.update(recovered=True, reason='verified_previous_release_admitted', final_target=current)
                return result
            sleep(.2)
        raise RuntimeError('Proxy did not confirm recovered server')
    except Exception as error:
        result.update(reason='recovery_control_error', error=str(error))
        if result['ready_attempted']:
            try:
                runtime.state(target, 'maint')
                result['final_target'] = runtime.server(target)
                if not result['final_target']['status'].startswith('MAINT'):
                    raise RuntimeError('Recovery MAINT unconfirmed')
            except Exception as recovery_error:
                result.update(recovered=None, reason='recovery_state_unknown', recovery_error=str(recovery_error))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('target', 'failed', 'container-id', 'service', 'image-id', 'failed-container-id', 'failed-service', 'failed-image-id', 'project'):
        parser.add_argument('--' + key, required=True)
    parser.add_argument('--admin-port', type=int, required=True)
    parser.add_argument('--candidate-url', type=local_url, required=True)
    parser.add_argument('--cookie-file', type=Path, required=True)
    parser.add_argument('--marker', required=True)
    parser.add_argument('--timeout', type=float, default=10)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.admin_port <= 65535 or args.timeout <= 0:
        parser.error('Invalid port or timeout')
    if not all(re.fullmatch(r'[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+', v) for v in (args.target, args.failed)):
        parser.error('Invalid backend/server')
    if not all(re.fullmatch(r'sha256:[0-9a-f]{64}', v) for v in (args.image_id, args.failed_image_id)):
        parser.error('Exact expected image ID required')
    if args.target.split('/')[1] != args.service or args.failed.split('/')[1] != args.failed_service:
        parser.error('Logical server names must match owned Compose services')
    with args.output.open('x', encoding='utf-8') as output:
        try:
            target = OwnedContainer(args.container_id, args.project, args.service, 1)
            failed = OwnedContainer(args.failed_container_id, args.project, args.failed_service, 1)
            if args.container_id == args.failed_container_id:
                raise ValueError('Distinct containers required')
            def verify(address: str) -> None:
                row, dead = target.inspect(), failed.inspect()
                if row['Image'] != args.image_id or row['State']['Status'] != 'running' or row['State']['StartedAt'] != target.started_at:
                    raise ValueError('Recovery artifact or container state changed')
                if dead['Image'] != args.failed_image_id or dead['State']['Status'] != 'exited' or dead['State']['StartedAt'] != failed.started_at:
                    raise ValueError('Failed container is not the confirmed exited instance')
                if address not in {f"{n['IPAddress']}:9091" for n in row['NetworkSettings']['Networks'].values()}:
                    raise ValueError('Recovery address mismatch')
                port = str(urllib.parse.urlsplit(args.candidate_url).port or 80)
                bindings = row['NetworkSettings']['Ports'].get('9091/tcp') or []
                if not any(p['HostIp'] == '127.0.0.1' and p['HostPort'] == port for p in bindings):
                    raise ValueError('Direct probe port is not bound to recovery container')
            jar = http.cookiejar.MozillaCookieJar(str(args.cookie_file))
            jar.load(ignore_discard=True)
            anonymous = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            authenticated = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPCookieProcessor(jar))
            result = recover(Runtime(args.admin_port), args.target, args.failed, verify,
                             lambda: request(anonymous, args.candidate_url + '/readyz'),
                             lambda: request(authenticated, args.candidate_url + '/boards/new', args.marker), args.timeout)
        except Exception as error:
            result = {'recovered': False, 'ready_attempted': False, 'reason': 'preflight_error', 'error': str(error)}
        json.dump(result, output, ensure_ascii=False, indent=2)
    print(json.dumps({k: result[k] for k in ('recovered', 'ready_attempted', 'reason')}))
    return 0 if result['recovered'] is True else 1


if __name__ == '__main__':
    raise SystemExit(main())
