import unittest
from copy import deepcopy
from unittest.mock import Mock

from retain_previous import retain, RetainedPair


class RetentionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.now = 0.0
        self.old = {'status': 'MAINT', 'scur': '0', 'qcur': '0', 'stot': '4', 'addr': 'old:9091', 'check_status': 'L7OK'}
        self.new = dict(self.old, status='UP', addr='new:9091')
        self.runtime = Mock()
        self.runtime.server.side_effect = lambda target: dict(self.old if target == 'apps/old' else self.new)
        self.pair = Mock(target='apps/old', survivor='apps/new')
        self.pair.stop.return_value = {'exited': True, 'reason': 'sigterm_exit_confirmed'}
        self.smoke = Mock(return_value={'status': 200, 'marker_found': True})

    def sleep(self, seconds: float) -> None:
        self.now += seconds

    def run_case(self, sleep=None) -> dict:
        return retain(self.runtime, self.pair, self.smoke, 1, 2,
                      sleep=sleep or self.sleep, clock=lambda: self.now)

    def test_normal_expiry_waits_then_checks_business_and_stops(self) -> None:
        result = self.run_case()
        self.assertTrue(result['stopped'])
        self.assertGreaterEqual(result['stop_started_at'], result['expires_at'] + .39)
        self.smoke.assert_called_once()
        self.pair.stop.assert_called_once_with('old:9091')

    def test_survivor_failure_during_hold_preserves_previous(self) -> None:
        def fail(seconds: float) -> None:
            self.sleep(seconds)
            self.new['status'] = 'DOWN'
        result = self.run_case(sleep=fail)
        self.assertFalse(result['stopped'])
        self.pair.stop.assert_not_called()

    def test_failure_during_final_business_check_prevents_stop(self) -> None:
        def fail() -> dict:
            self.new['status'] = 'DOWN'
            return {'status': 200, 'marker_found': True}
        self.smoke.side_effect = fail
        self.assertFalse(self.run_case()['stopped'])
        self.pair.stop.assert_not_called()

    def test_identity_drift_after_business_check_prevents_stop(self) -> None:
        def drift() -> dict:
            self.pair.verify.side_effect = ValueError('identity drift')
            return {'status': 200, 'marker_found': True}
        self.smoke.side_effect = drift
        self.assertFalse(self.run_case()['stopped'])
        self.pair.stop.assert_not_called()

    def test_wrong_marker_prevents_stop(self) -> None:
        self.smoke.return_value = {'status': 200, 'marker_found': False}
        self.assertFalse(self.run_case()['stopped'])
        self.pair.stop.assert_not_called()

    def test_busy_or_queued_previous_times_out(self) -> None:
        for field in ('scur', 'qcur'):
            with self.subTest(field=field):
                self.setUp()
                self.old[field] = '1'
                self.assertEqual('expiry_checks_timeout_no_stop', self.run_case()['reason'])
                self.pair.stop.assert_not_called()

    def test_traffic_drift_aborts(self) -> None:
        def drift(seconds: float) -> None:
            self.sleep(seconds)
            self.old['stot'] = '5'
        self.assertFalse(self.run_case(sleep=drift)['stopped'])
        self.pair.stop.assert_not_called()

    def test_slow_business_check_cannot_stop_after_deadline(self) -> None:
        def slow() -> dict:
            self.now = 10
            return {'status': 200, 'marker_found': True}
        self.smoke.side_effect = slow
        self.assertEqual('expiry_checks_timeout_no_stop', self.run_case()['reason'])
        self.pair.stop.assert_not_called()

    def test_unknown_stop_outcome_is_not_false(self) -> None:
        self.pair.stop.side_effect = TimeoutError('signal result unknown')
        result = self.run_case()
        self.assertIsNone(result['stopped'])
        self.assertTrue(result['stop_invoked'])

    def test_nonfinite_windows_are_rejected(self) -> None:
        for seconds in (0, -1, float('nan'), float('inf')):
            with self.assertRaises(ValueError):
                retain(self.runtime, self.pair, self.smoke, seconds, 2)

    def test_confirmed_stop_timeout_is_false_with_attempt_recorded(self) -> None:
        self.pair.stop.return_value = {'exited': False, 'reason': 'sigterm_exit_timeout_no_force_kill'}
        result = self.run_case()
        self.assertFalse(result['stopped'])
        self.assertTrue(result['stop_invoked'])
        self.pair.stop.assert_called_once()


class RetainedPairTests(unittest.TestCase):
    def setUp(self) -> None:
        self.image = 'sha256:' + 'a' * 64
        self.row = {'Image': self.image, 'State': {'Status': 'running', 'StartedAt': 'pinned'},
                    'NetworkSettings': {'Networks': {'fixture': {'IPAddress': '172.1.1.2'}},
                                        'Ports': {'9091/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '19792'}]}}}
        self.old = Mock(project='popping-drain-check-test', container_id='a' * 64, service='old', started_at='pinned')
        self.new = Mock(project='popping-drain-check-test', container_id='b' * 64, service='candidate', started_at='pinned')
        self.old.inspect.return_value = deepcopy(self.row)
        self.new.inspect.return_value = deepcopy(self.row)
        self.pair = RetainedPair(self.old, self.new, self.image, self.image, 'http://127.0.0.1:19792')

    def test_matching_processes_and_binding_are_accepted(self) -> None:
        self.pair.verify('172.1.1.2:9091', '172.1.1.2:9091')

    def test_image_start_status_address_and_binding_drift_are_rejected(self) -> None:
        for target in ('old', 'new'):
            for change in ('image', 'started', 'status', 'address', 'binding'):
                if target == 'old' and change == 'binding':
                    continue
                with self.subTest(target=target, change=change):
                    self.setUp()
                    row = getattr(self, target).inspect.return_value
                    if change == 'image':
                        row['Image'] = 'sha256:' + 'c' * 64
                    elif change == 'started':
                        row['State']['StartedAt'] = 'restarted'
                    elif change == 'status':
                        row['State']['Status'] = 'exited'
                    elif change == 'address':
                        row['NetworkSettings']['Networks']['fixture']['IPAddress'] = '172.1.1.3'
                    else:
                        row['NetworkSettings']['Ports']['9091/tcp'][0]['HostIp'] = '0.0.0.0'
                    with self.assertRaises(ValueError):
                        self.pair.verify('172.1.1.2:9091', '172.1.1.2:9091')


if __name__ == '__main__':
    unittest.main()
