"""Synchronous, local-only retention expiry; no daemon or automatic recovery.

The caller has already parked the previous server in MAINT and verified the new
release. Only one controller may touch this project. A healthy observation and
SIGTERM cannot be atomic: the survivor can fail after the final check.
"""
import math
import re
import time
from urllib.parse import urlsplit

from admit_candidate import local_url
from quiesce_server import OwnedContainer, healthy, snapshot


class RetainedPair:
    """Pin both owned running processes before starting the retention window."""

    def __init__(self, old: OwnedContainer, new: OwnedContainer,
                 old_image: str, new_image: str, new_url: str):
        if old.project != new.project or old.container_id == new.container_id or old.service == new.service:
            raise ValueError('Distinct services in one owned project required')
        if not all(re.fullmatch(r'sha256:[0-9a-f]{64}', v) for v in (old_image, new_image)):
            raise ValueError('Exact image IDs required')
        self.old, self.new = old, new
        self.old_image, self.new_image = old_image, new_image
        self.new_url = local_url(new_url)
        self.target, self.survivor = 'apps/' + old.service, 'apps/' + new.service

    def verify(self, old_addr: str, new_addr: str) -> None:
        for owned, image, address in ((self.old, self.old_image, old_addr), (self.new, self.new_image, new_addr)):
            row = owned.inspect()
            if (row['Image'] != image or row['State']['Status'] != 'running'
                    or row['State']['StartedAt'] != owned.started_at):
                raise ValueError('Pinned process/image changed or no longer running')
            if address not in {f"{n['IPAddress']}:9091" for n in row['NetworkSettings']['Networks'].values()}:
                raise ValueError('Proxy address does not match pinned process')
            if owned is self.new:
                port = str(urlsplit(self.new_url).port or 80)
                bindings = row['NetworkSettings']['Ports'].get('9091/tcp') or []
                if not any(p['HostIp'] == '127.0.0.1' and p['HostPort'] == port for p in bindings):
                    raise ValueError('Business probe port is not bound to pinned survivor')

    def stop(self, address: str) -> dict:
        return self.old.stop(address)


def retain(runtime, pair, smoke, hold_seconds: float, drain_timeout: float,
           sleep=time.sleep, clock=time.monotonic) -> dict:
    """Keep an excluded previous process, then stop only after expiry checks.

smoke must issue an authenticated GET against pair.new_url and validate the
expected new release marker. It must never retry writes. Both pair processes
must have been pinned before calling; a rejected result never admits either
server. The caller decides whether separate recovery is appropriate.
"""
    if not all(math.isfinite(v) and v > 0 for v in (hold_seconds, drain_timeout)):
        raise ValueError('Finite positive hold and drain timeout required')
    if pair.target == pair.survivor:
        raise ValueError('Distinct logical servers required')
    result = {'stopped': False, 'stop_invoked': False, 'samples': [], 'business_checks': []}
    baseline = None

    def check() -> dict:
        row, peer = runtime.server(pair.target), runtime.server(pair.survivor)
        result['samples'].append({'at': clock(), 'target': snapshot(row),
                                  'survivor': snapshot(peer), 'survivor_check': peer['check_status']})
        if not row['status'].startswith('MAINT') or not healthy(peer):
            raise RuntimeError('Previous must stay MAINT and survivor must remain healthy')
        if baseline is not None and int(row['stot']) != baseline:
            raise RuntimeError('Previous accepted new traffic during retention')
        pair.verify(row['addr'], peer['addr'])
        return row

    try:
        initial = check()
        baseline = int(initial['stot'])
        start = clock()
        expiry, deadline = start + hold_seconds, start + hold_seconds + drain_timeout
        result.update(retention_started_at=start, expires_at=expiry, drain_deadline=deadline)
        zeros = 0
        while clock() < deadline:
            row = check()
            if clock() < expiry:
                sleep(max(0.0, min(.2, expiry - clock())))
                continue
            idle = int(row['scur']) == 0 and int(row['qcur']) == 0
            zeros = zeros + 1 if idle else 0
            if zeros >= 3:
                business = smoke()
                result['business_checks'].append(business)
                if business['status'] != 200 or not business['marker_found']:
                    raise RuntimeError('Survivor authenticated release check failed')
                final = check()
                if int(final['scur']) != 0 or int(final['qcur']) != 0:
                    raise RuntimeError('Previous became busy before expiry stop')
                if clock() >= deadline:
                    break
                result.update(stop_invoked=True, stop_started_at=clock())
                outcome = pair.stop(final['addr'])
                result.update(stopped=outcome['exited'], reason=outcome['reason'], stop=outcome)
                return result
            sleep(.2)
        result['reason'] = 'expiry_checks_timeout_no_stop'
    except (Exception, KeyboardInterrupt) as error:
        result.update(reason='retention_aborted_no_automatic_recovery', error=repr(error))
        if result['stop_invoked']:
            result.update(stopped=None, reason='stop_outcome_unknown')
    return result
