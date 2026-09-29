"""Local-only quiesce and SIGTERM controller for an isolated drain experiment.

HAProxy sessions are not a universal application in-flight request counter.
This tool assumes one controller, HTTP request/response traffic exclusively via
the proxy, no persistence exceptions, and no detached or WebSocket work.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

from admit_candidate import Runtime


def snapshot(row: dict) -> dict:
    return {key: row[key] for key in ('status', 'scur', 'qcur', 'stot', 'addr')}


def healthy(row: dict) -> bool:
    # HAProxy prefixes the previous result with '* ' while a check is running.
    return row['status'] == 'UP' and row['check_status'] in ('L7OK', '* L7OK')


def quiesce(runtime, target: str, survivor: str, stop, timeout: float,
            sleep=time.sleep, clock=time.monotonic) -> dict:
    if target == survivor or timeout <= 0:
        raise ValueError('Distinct servers and a positive timeout are required')
    start = runtime.server(target)
    peer = runtime.server(survivor)
    if start['status'] != 'UP' or not healthy(peer):
        raise ValueError('Both servers must be UP and survivor HTTP check L7OK')
    result = {'stopped': False, 'stop_invoked': False, 'samples': [], 'initial': snapshot(start)}
    try:
        runtime.state(target, 'maint')
        initial = runtime.server(target)
        if not initial['status'].startswith('MAINT'):
            raise RuntimeError('MAINT was not confirmed')
        result['maint_confirmed_at'] = time.time()
        baseline = int(initial['stot'])
        deadline, zeros = clock() + timeout, 0
        while clock() < deadline:
            row, peer = runtime.server(target), runtime.server(survivor)
            sample = snapshot(row)
            sample['observed_at'] = time.time()
            sample['survivor'] = {key: peer[key] for key in ('status', 'check_status')}
            result['samples'].append(sample)
            if not row['status'].startswith('MAINT'):
                raise RuntimeError('Target left MAINT')
            if not healthy(peer):
                raise RuntimeError('Survivor is not healthy')
            if int(row['stot']) != baseline:
                raise RuntimeError('Target accepted traffic after MAINT confirmation')
            idle = int(row['scur']) == 0 and int(row['qcur']) == 0
            zeros = zeros + 1 if idle else 0
            if zeros >= 3:
                # Re-read immediately before stop; stop itself revalidates identity.
                final, peer = runtime.server(target), runtime.server(survivor)
                if (not final['status'].startswith('MAINT') or int(final['scur']) != 0
                        or int(final['qcur']) != 0 or int(final['stot']) != baseline
                        or not healthy(peer)):
                    raise RuntimeError('State changed before stop')
                if clock() >= deadline:
                    result['reason'] = 'quiescence_timeout_no_stop'
                    return result
                result['stop_invoked'] = True
                result['stop_started_at'] = time.time()
                outcome = stop(final['addr'])
                result.update(stopped=outcome['exited'], reason=outcome['reason'], stop=outcome)
                return result
            sleep(.2)
        result['reason'] = 'quiescence_timeout_no_stop'
    except Exception as error:
        # No automatic ready or force-stop fallback. State must remain visible.
        result.update(reason='control_error', error=str(error))
        if result['stop_invoked']:
            result['stopped'] = None
        try:
            result['final_target'] = snapshot(runtime.server(target))
        except Exception as state_error:
            result['final_target'] = None
            result['state_error'] = str(state_error)
    return result


class OwnedContainer:
    def __init__(self, container_id: str, project: str, service: str, timeout: float):
        if not re.fullmatch(r'[0-9a-f]{64}', container_id):
            raise ValueError('An exact 64-character container ID is required')
        if not re.fullmatch(r'popping-drain-check-[a-z0-9-]+', project):
            raise ValueError('Only the disposable drain project namespace is allowed')
        if timeout <= 0:
            raise ValueError('Positive shutdown timeout required')
        self.container_id, self.project, self.service, self.timeout = container_id, project, service, timeout
        self.started_at = self.inspect()['State']['StartedAt']

    def inspect(self) -> dict:
        output = subprocess.run(['docker', 'inspect', self.container_id], check=True, capture_output=True, text=True, timeout=3)
        row = json.loads(output.stdout)[0]
        labels = row['Config']['Labels']
        if (row['Id'] != self.container_id or labels.get('com.docker.compose.project') != self.project
                or labels.get('com.docker.compose.service') != self.service):
            raise ValueError('Container ownership mismatch')
        return row

    def stop(self, address: str) -> dict:
        row = self.inspect()
        if row['State']['Status'] != 'running' or row['State']['StartedAt'] != self.started_at:
            raise ValueError('Container is no longer the running instance that was checked')
        networks = row['NetworkSettings']['Networks'].values()
        if address not in {f"{network['IPAddress']}:9091" for network in networks}:
            raise ValueError('HAProxy address does not match owned container')
        # SIGTERM only. Unlike docker stop, this has no timed SIGKILL fallback.
        subprocess.run(['docker', 'kill', '--signal=TERM', self.container_id], check=True, capture_output=True, text=True, timeout=3)
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            row = self.inspect()
            if row['State']['Status'] == 'exited':
                state = row['State']
                return {'exited': True, 'reason': 'sigterm_exit_confirmed', 'exit_code': state['ExitCode'],
                        'oom_killed': state['OOMKilled'], 'finished_at': state['FinishedAt'],
                        'container_id': self.container_id}
            time.sleep(.2)
        return {'exited': False, 'reason': 'sigterm_exit_timeout_no_force_kill', 'container_id': self.container_id}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('target', 'survivor', 'container-id', 'project', 'service'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--admin-port', type=int, required=True)
    parser.add_argument('--timeout', type=float, default=15)
    parser.add_argument('--shutdown-timeout', type=float, default=35)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.admin_port <= 65535:
        parser.error('Invalid admin port')
    if not all(re.fullmatch(r'[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+', t) for t in (args.target, args.survivor)):
        parser.error('Expected backend/server identifiers')
    with args.output.open('x', encoding='utf-8') as stream:
        try:
            owned = OwnedContainer(args.container_id, args.project, args.service, args.shutdown_timeout)
            result = quiesce(Runtime(args.admin_port), args.target, args.survivor, owned.stop, args.timeout)
        except Exception as error:
            result = {'stopped': False, 'stop_invoked': False, 'reason': 'preflight_error', 'error': str(error)}
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps({k: result[k] for k in ('stopped', 'stop_invoked', 'reason')}))
    return 0 if result['stopped'] is True else 1


if __name__ == '__main__':
    raise SystemExit(main())
