import tempfile
import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import Mock

from retain_previous import RetainedPair
from verified_fixture import CleanupGuard, admit_verified, run_preserving_failure


class CleanupTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'compose.yml'
        self.guard = CleanupGuard(self.path)
        self.ids = []
        self.verify = Mock()
        self.remove = Mock(side_effect=self.ids.clear)

    def listing(self) -> list:
        return list(self.ids)

    def test_before_creation_cleanup_does_not_call_docker(self) -> None:
        list_ids = Mock()
        result = self.guard.cleanup(list_ids, self.verify, self.remove)
        self.assertFalse(result['removed'])
        list_ids.assert_not_called()
        self.remove.assert_not_called()

    def test_existing_namespace_prevents_create_and_cleanup(self) -> None:
        self.path.touch()
        self.ids.append('foreign')
        create = Mock()
        with self.assertRaisesRegex(ValueError, 'Unowned'):
            self.guard.create(self.listing, self.verify, create)
        self.guard.cleanup(self.listing, self.verify, self.remove)
        create.assert_not_called()
        self.remove.assert_not_called()

    def test_partial_create_records_ids_even_when_up_fails(self) -> None:
        self.path.touch()
        def partially_create() -> None:
            self.ids.append('owned')
            raise RuntimeError('original up failure')
        with self.assertRaisesRegex(RuntimeError, 'original up failure'):
            self.guard.create(self.listing, self.verify, partially_create)
        self.assertEqual({'owned'}, self.guard.owned_ids)
        self.assertTrue(self.guard.cleanup(self.listing, self.verify, self.remove)['removed'])

    def test_unknown_id_before_cleanup_prevents_down(self) -> None:
        self.path.touch()
        self.guard.create(self.listing, self.verify, lambda: self.ids.append('owned'))
        self.ids.append('foreign')
        with self.assertRaisesRegex(ValueError, 'Unowned'):
            self.guard.cleanup(self.listing, self.verify, self.remove)
        self.remove.assert_not_called()

    def test_recreation_tracks_new_id_without_losing_original_ownership(self) -> None:
        self.path.touch()
        self.guard.create(self.listing, self.verify, lambda: self.ids.append('old'))
        self.guard.create(self.listing, self.verify, lambda: self.ids.__setitem__(slice(None), ['new']))
        self.assertEqual({'old', 'new'}, self.guard.owned_ids)
        self.assertTrue(self.guard.cleanup(self.listing, self.verify, self.remove)['removed'])

    def test_tracking_failure_blocks_cleanup_and_preserves_create_error(self) -> None:
        self.path.touch()
        def fail() -> None:
            self.ids.append('owned')
            self.verify.side_effect = ValueError('label mismatch')
            raise RuntimeError('first failure')
        with self.assertRaisesRegex(RuntimeError, 'first failure'):
            self.guard.create(self.listing, self.verify, fail)
        with self.assertRaisesRegex(RuntimeError, 'tracking incomplete'):
            self.guard.cleanup(self.listing, self.verify, self.remove)
        self.remove.assert_not_called()

    def test_cleanup_error_does_not_mask_primary_exception(self) -> None:
        first = ValueError('first')
        result = {}
        with self.assertRaises(ValueError) as raised:
            run_preserving_failure(Mock(side_effect=first), Mock(side_effect=RuntimeError('cleanup')), result)
        self.assertIs(raised.exception, first)
        self.assertIn('cleanup', result['cleanup_error'])
        self.assertIn('first', result['error'])

    def test_cleanup_failure_after_success_fails_the_run(self) -> None:
        with self.assertRaisesRegex(RuntimeError, 'cleanup'):
            run_preserving_failure(Mock(), Mock(side_effect=RuntimeError('cleanup')), {})

    def test_missing_compose_after_creation_is_failure_without_docker_calls(self) -> None:
        self.path.touch()
        self.guard.create(self.listing, self.verify, lambda: self.ids.append('owned'))
        self.path.unlink()
        listing = Mock()
        self.verify.reset_mock()
        with self.assertRaisesRegex(RuntimeError, 'Compose file unavailable'):
            self.guard.cleanup(listing, self.verify, self.remove)
        listing.assert_not_called()
        self.verify.assert_not_called()
        self.remove.assert_not_called()
        self.assertTrue(self.guard.creation_attempted)
        self.assertEqual({'owned'}, self.guard.owned_ids)

    def test_partial_creation_and_missing_compose_keep_both_errors(self) -> None:
        self.path.touch()
        primary = ValueError('original creation failure')
        def create() -> None:
            self.ids.append('owned')
            self.path.unlink()
            raise primary
        result = {}
        with self.assertRaises(ValueError) as raised:
            run_preserving_failure(
                lambda: self.guard.create(self.listing, self.verify, create),
                lambda: self.guard.cleanup(self.listing, self.verify, self.remove), result)
        self.assertIs(raised.exception, primary)
        self.assertIn('original creation failure', result['error'])
        self.assertIn('Compose file unavailable', result['cleanup_error'])
        self.assertNotIn('cleanup', result)
        self.remove.assert_not_called()

    def test_success_then_missing_compose_fails_instead_of_reporting_skip(self) -> None:
        self.path.touch()
        def action() -> None:
            self.guard.create(self.listing, self.verify, lambda: self.ids.append('owned'))
            self.path.unlink()
        result = {}
        with self.assertRaisesRegex(RuntimeError, 'Compose file unavailable'):
            run_preserving_failure(action,
                lambda: self.guard.cleanup(self.listing, self.verify, self.remove), result)
        self.assertNotIn('error', result)
        self.assertNotIn('cleanup', result)
        self.assertIn('Compose file unavailable', result['cleanup_error'])
        self.remove.assert_not_called()

    def test_restored_compose_allows_verified_cleanup_retry(self) -> None:
        self.path.touch()
        self.guard.create(self.listing, self.verify, lambda: self.ids.append('owned'))
        self.path.unlink()
        with self.assertRaisesRegex(RuntimeError, 'Compose file unavailable'):
            self.guard.cleanup(self.listing, self.verify, self.remove)
        self.path.touch()
        self.verify.reset_mock()
        result = self.guard.cleanup(self.listing, self.verify, self.remove)
        self.assertTrue(result['removed'])
        self.assertEqual(['owned'], result['removed_ids'])
        self.verify.assert_called_once_with('owned')
        self.remove.assert_called_once()


class AdmissionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.image = 'sha256:' + 'a' * 64
        row = {'Image': self.image, 'State': {'Status': 'running', 'StartedAt': 'pinned'},
               'NetworkSettings': {'Networks': {'fixture': {'IPAddress': '172.1.1.2'}},
                                   'Ports': {'9091/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '19792'}]}}}
        self.old = Mock(project='popping-drain-check-test', container_id='a' * 64, service='old', started_at='pinned')
        self.new = Mock(project='popping-drain-check-test', container_id='b' * 64, service='candidate', started_at='pinned')
        self.old.inspect.return_value = deepcopy(row)
        self.new.inspect.return_value = deepcopy(row)
        self.pair = RetainedPair(self.old, self.new, self.image, self.image, 'http://127.0.0.1:19792')
        self.runtime = Mock()
        self.runtime.server.return_value = {'addr': '172.1.1.2:9091'}
        self.admission = Mock(return_value={'admitted': True})

    def test_valid_pinned_identity_calls_admission_once(self) -> None:
        self.pair.verify('172.1.1.2:9091', '172.1.1.2:9091')
        self.assertTrue(admit_verified(self.runtime, self.pair, self.admission)['admitted'])
        self.admission.assert_called_once()

    def test_image_restart_port_and_missing_id_reject_without_mutation(self) -> None:
        for change in ('image', 'restart_same_id', 'port', 'missing_container'):
            with self.subTest(change=change):
                self.setUp()
                row = self.new.inspect.return_value
                if change == 'image':
                    row['Image'] = 'sha256:' + 'c' * 64
                elif change == 'restart_same_id':
                    row['State']['StartedAt'] = 'restarted'
                elif change == 'port':
                    row['NetworkSettings']['Ports']['9091/tcp'][0]['HostPort'] = '19999'
                else:
                    self.new.inspect.side_effect = ValueError('Pinned container missing')
                with self.assertRaises(ValueError):
                    admit_verified(self.runtime, self.pair, self.admission)
                self.admission.assert_not_called()
                self.runtime.state.assert_not_called()
                self.old.stop.assert_not_called()
                self.new.stop.assert_not_called()


if __name__ == '__main__':
    unittest.main()
