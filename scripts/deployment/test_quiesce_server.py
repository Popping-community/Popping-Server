import unittest
from quiesce_server import healthy, quiesce


class FixtureRuntime:
    def __init__(self, sessions=(1, 0, 0, 0, 0), queued=0):
        self.status = 'UP'
        self.sessions = iter(sessions)
        self.last = 0
        self.queued = queued
        self.peer_up = True
        self.stot = 10

    def state(self, target, state):
        self.status = 'MAINT'

    def server(self, target):
        if target == 'apps/candidate':
            return {'status': 'UP' if self.peer_up else 'DOWN', 'check_status': 'L7OK'}
        if self.status == 'MAINT':
            self.last = next(self.sessions, self.last)
        return {'status': self.status, 'scur': str(self.last), 'qcur': str(self.queued),
                'stot': str(self.stot), 'addr': '172.1.1.2:9091', 'check_status': 'L7OK'}


class QuiesceTests(unittest.TestCase):
    def test_in_progress_check_keeps_last_success_but_not_last_failure(self):
        for status in ('L7OK', '* L7OK'):
            self.assertTrue(healthy({'status': 'UP', 'check_status': status}))
        for status in ('L7STS', '* L7STS', 'INI', '', 'L4OK'):
            self.assertFalse(healthy({'status': 'UP', 'check_status': status}))
        self.assertFalse(healthy({'status': 'DOWN', 'check_status': '* L7OK'}))

    def execute(self, rt, timeout=1, callback=None):
        now, calls = [0.0], []
        def stop(addr):
            calls.append(addr)
            return callback(addr) if callback else {'exited': True, 'reason': 'sigterm_exit_confirmed'}
        result = quiesce(rt, 'apps/old', 'apps/candidate', stop, timeout,
                         sleep=lambda s: now.__setitem__(0, now[0] + s), clock=lambda: now[0])
        return result, calls

    def test_stops_after_three_zero_samples(self):
        result, calls = self.execute(FixtureRuntime())
        self.assertTrue(result['stopped'])
        self.assertEqual(['172.1.1.2:9091'], calls)

    def test_busy_timeout_never_stops(self):
        result, calls = self.execute(FixtureRuntime(sessions=(1,)))
        self.assertEqual('quiescence_timeout_no_stop', result['reason'])
        self.assertFalse(result['stop_invoked'])
        self.assertEqual([], calls)

    def test_queue_alone_prevents_stop(self):
        result, calls = self.execute(FixtureRuntime(sessions=(0,), queued=1))
        self.assertEqual([], calls)
        self.assertFalse(result['stopped'])

    def test_survivor_loss_aborts(self):
        rt = FixtureRuntime()
        original = rt.state
        def state(target, value):
            original(target, value)
            rt.peer_up = False
        rt.state = state
        result, calls = self.execute(rt)
        self.assertEqual('control_error', result['reason'])
        self.assertEqual([], calls)

    def test_new_target_traffic_aborts(self):
        rt = FixtureRuntime(sessions=(1,))
        original = rt.server
        count = [0]
        def server(target):
            row = original(target)
            if target == 'apps/old' and rt.status == 'MAINT':
                count[0] += 1
                row['stot'] = str(count[0])
            return row
        rt.server = server
        result, calls = self.execute(rt)
        self.assertEqual('control_error', result['reason'])
        self.assertEqual([], calls)

    def test_stop_failure_is_not_reported_as_stopped(self):
        def fail(_):
            raise OSError('signal acknowledgement unavailable')
        result, calls = self.execute(FixtureRuntime(), callback=fail)
        self.assertIsNone(result['stopped'])
        self.assertTrue(result['stop_invoked'])

    def test_shutdown_timeout_does_not_force_stop(self):
        result, calls = self.execute(FixtureRuntime(), callback=lambda _: {
            'exited': False, 'reason': 'sigterm_exit_timeout_no_force_kill'})
        self.assertFalse(result['stopped'])
        self.assertEqual(1, len(calls))

    def test_slow_final_check_cannot_stop_after_drain_deadline(self):
        now, reads, calls = [0.0], [0], []
        rt = FixtureRuntime(sessions=(0,))
        original = rt.server
        def server(target):
            row = original(target)
            if target == 'apps/old' and rt.status == 'MAINT':
                reads[0] += 1
                if reads[0] == 5:
                    now[0] = 2.0
            return row
        rt.server = server
        result = quiesce(rt, 'apps/old', 'apps/candidate', lambda addr: calls.append(addr), 1,
                         sleep=lambda s: now.__setitem__(0, now[0] + s), clock=lambda: now[0])
        self.assertEqual('quiescence_timeout_no_stop', result['reason'])
        self.assertFalse(result['stop_invoked'])
        self.assertEqual([], calls)


if __name__ == '__main__':
    unittest.main()
