import unittest
from admit_candidate import admit


class FakeRuntime:
    def __init__(self, confirm=True):
        self.commands = []
        self.status = 'MAINT'
        self.confirm = confirm

    def server(self, target):
        if target == 'apps/old':
            return {'status': 'UP', 'check_status': 'L7OK'}
        return {'status': self.status, 'check_status': 'L7OK' if self.status == 'UP' else 'INI'}

    def state(self, target, state):
        self.commands.append((target, state))
        self.status = ('UP' if self.confirm else 'DOWN') if state == 'ready' else 'MAINT'


class GateTests(unittest.TestCase):
    def run_gate(self, runtime, probe, smoke):
        now = [0.0]
        return admit(runtime, 'apps/candidate', 'apps/old', probe, smoke, 2,
                     sleep=lambda seconds: now.__setitem__(0, now[0] + seconds), clock=lambda: now[0])

    def test_probe_503_never_admits_even_with_healthy_body_and_business(self):
        runtime = FakeRuntime()
        result = self.run_gate(runtime, lambda: {'status': 503, 'health_up': True},
                               lambda: {'status': 200, 'marker_found': True})
        self.assertFalse(result['admitted'])
        self.assertEqual([], runtime.commands)

    def test_login_redirect_is_not_authenticated_success(self):
        runtime = FakeRuntime()
        result = self.run_gate(runtime, lambda: {'status': 200, 'health_up': True},
                               lambda: {'status': 302, 'marker_found': True})
        self.assertFalse(result['admitted'])
        self.assertEqual([], runtime.commands)

    def test_success_changes_only_candidate_after_three_checks(self):
        runtime = FakeRuntime()
        result = self.run_gate(runtime, lambda: {'status': 200, 'health_up': True},
                               lambda: {'status': 200, 'marker_found': True})
        self.assertTrue(result['admitted'])
        self.assertEqual(3, len(result['attempts']))
        self.assertEqual([('apps/candidate', 'ready')], runtime.commands)

    def test_failed_proxy_confirmation_returns_candidate_to_maint(self):
        runtime = FakeRuntime(confirm=False)
        result = self.run_gate(runtime, lambda: {'status': 200, 'health_up': True},
                               lambda: {'status': 200, 'marker_found': True})
        self.assertEqual('admission_failed_maint_confirmed', result['reason'])
        self.assertEqual('MAINT', result['final_candidate']['status'])
        self.assertEqual([('apps/candidate', 'ready'), ('apps/candidate', 'maint')], runtime.commands)

    def test_lost_admin_connection_does_not_claim_confirmed_recovery(self):
        runtime = FakeRuntime(confirm=False)
        original = runtime.state
        def fail_recovery(target, state):
            if state == 'maint':
                raise OSError('admin connection unavailable')
            original(target, state)
        runtime.state = fail_recovery
        result = self.run_gate(runtime, lambda: {'status': 200, 'health_up': True},
                               lambda: {'status': 200, 'marker_found': True})
        self.assertIsNone(result['admitted'])
        self.assertEqual('admission_unknown_maint_unconfirmed', result['reason'])
        self.assertIn('admission_error', result)
        self.assertIn('recovery_error', result)

    def test_unconfirmed_maint_is_reported_as_unknown(self):
        runtime = FakeRuntime(confirm=False)
        original = runtime.state
        runtime.state = lambda target, state: original(target, state) if state == 'ready' else None
        result = self.run_gate(runtime, lambda: {'status': 200, 'health_up': True},
                               lambda: {'status': 200, 'marker_found': True})
        self.assertIsNone(result['admitted'])
        self.assertEqual('Candidate MAINT was not confirmed', result['recovery_error'])

    def test_active_candidate_is_not_changed(self):
        runtime = FakeRuntime()
        runtime.status = 'UP'
        with self.assertRaises(ValueError):
            self.run_gate(runtime, None, None)
        self.assertEqual([], runtime.commands)


if __name__ == '__main__':
    unittest.main()
