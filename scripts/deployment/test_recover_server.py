import unittest
from recover_server import recover


class Runtime:
    def __init__(self):
        self.target, self.failed, self.commands = 'MAINT', 'MAINT', []
        self.confirm, self.recover_maint = True, True

    def server(self, name):
        return {'status': self.target if name == 'apps/old' else self.failed,
                'check_status': '* L7OK', 'addr': '172.1.1.1:9091'}

    def state(self, name, state):
        self.commands.append(state)
        if state == 'maint' and not self.recover_maint:
            raise OSError('admin unavailable')
        self.target = ('UP' if self.confirm else 'DOWN') if state == 'ready' else 'MAINT'


class RecoveryTests(unittest.TestCase):
    def run_recovery(self, rt, smoke=None, verify=None):
        now = [0.0]
        return recover(rt, 'apps/old', 'apps/candidate', verify or (lambda _: None),
                       lambda: {'status': 200, 'health_up': True},
                       smoke or (lambda: {'status': 200, 'marker_found': True}), 2,
                       sleep=lambda s: now.__setitem__(0, now[0] + s), clock=lambda: now[0])

    def test_success_without_healthy_survivor(self):
        rt = Runtime()
        result = self.run_recovery(rt)
        self.assertTrue(result['recovered'])
        self.assertEqual(['ready'], rt.commands)
        self.assertEqual(3, len(result['attempts']))

    def test_wrong_version_never_enters_service(self):
        rt = Runtime()
        result = self.run_recovery(rt, smoke=lambda: {'status': 200, 'marker_found': False})
        self.assertFalse(result['recovered'])
        self.assertEqual([], rt.commands)

    def test_healthy_failed_server_blocks_recovery(self):
        rt = Runtime()
        rt.failed = 'UP'
        result = self.run_recovery(rt)
        self.assertFalse(result['recovered'])
        self.assertEqual([], rt.commands)

    def test_down_without_maint_fence_blocks_recovery(self):
        rt = Runtime()
        rt.failed = 'DOWN'
        result = self.run_recovery(rt)
        self.assertFalse(result['recovered'])
        self.assertEqual([], rt.commands)

    def test_identity_change_before_ready_blocks_admission(self):
        rt, count = Runtime(), [0]
        def verify(_):
            count[0] += 1
            if count[0] == 2:
                raise ValueError('restarted or wrong image')
        result = self.run_recovery(rt, verify=verify)
        self.assertFalse(result['recovered'])
        self.assertEqual([], rt.commands)

    def test_proxy_failure_reexcludes_recovery(self):
        rt = Runtime()
        rt.confirm = False
        result = self.run_recovery(rt)
        self.assertFalse(result['recovered'])
        self.assertEqual(['ready', 'maint'], rt.commands)
        self.assertEqual('MAINT', rt.target)

    def test_failed_reexclusion_is_unknown(self):
        rt = Runtime()
        rt.confirm = rt.recover_maint = False
        result = self.run_recovery(rt)
        self.assertIsNone(result['recovered'])
        self.assertEqual('recovery_state_unknown', result['reason'])


if __name__ == '__main__':
    unittest.main()
