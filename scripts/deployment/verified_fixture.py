"""Small guards for single-caller disposable fixtures, not a deployment engine."""
from pathlib import Path


class CleanupGuard:
    """Track IDs observed after this caller's creation attempts, including failures.

    The caller must establish an empty isolated namespace before the first up.
    This is not a lock against another process creating resources concurrently.
    """

    def __init__(self, compose_path: Path):
        self.compose_path = compose_path
        self.creation_attempted = False
        self.owned_ids: set[str] = set()
        self.tracking_error: str | None = None

    def create(self, list_ids, verify_id, action):
        if not self.compose_path.is_file():
            raise ValueError('Compose file must exist before resource creation')
        present = set(list_ids())
        if not present <= self.owned_ids:
            raise ValueError('Unowned containers present before creation')
        for container_id in present:
            verify_id(container_id)
        self.creation_attempted = True
        primary_error = None
        try:
            return action()
        except BaseException as error:
            primary_error = error
            raise
        finally:
            try:
                discovered = set(list_ids())
                for container_id in discovered:
                    verify_id(container_id)
                self.owned_ids.update(discovered)
            except Exception as error:
                self.tracking_error = repr(error)
                if primary_error is None:
                    raise

    def cleanup(self, list_ids, verify_id, remove) -> dict:
        if not self.creation_attempted:
            return {'removed': False, 'reason': 'no_owned_creation_attempt', 'owned_ids': sorted(self.owned_ids)}
        if not self.compose_path.is_file():
            raise RuntimeError('Compose file unavailable after resource creation; cleanup not attempted: '
                               + str(self.compose_path))
        if self.tracking_error:
            raise RuntimeError('Creation ownership tracking incomplete: ' + self.tracking_error)
        current = set(list_ids())
        if not current <= self.owned_ids:
            raise ValueError('Unowned containers present at cleanup; no removal attempted')
        for container_id in current:
            verify_id(container_id)
        # Empty is valid after partial failure. The isolated default network may
        # still exist; the caller also owns its unique per-run project namespace.
        remove()
        if list_ids():
            raise RuntimeError('Cleanup did not remove all owned containers')
        return {'removed': True, 'reason': 'owned_cleanup_confirmed',
                'removed_ids': sorted(current), 'owned_ids': sorted(self.owned_ids)}


def run_preserving_failure(action, cleanup, result: dict):
    """Keep the primary exception while retaining a separate cleanup failure."""
    primary_error = None
    try:
        return action()
    except BaseException as error:
        primary_error = error
        result['error'] = repr(error)
        raise
    finally:
        try:
            result['cleanup'] = cleanup()
        except BaseException as error:
            result['cleanup_error'] = repr(error)
            if primary_error is None:
                raise


def admit_verified(runtime, pair, admission):
    """Validate the already-pinned RetainedPair before invoking admission.

    Reuses the image/StartedAt/address/loopback-port checks without modifying
    historical tool sources. This is a preflight observation, not an atomic
    guarantee against process changes during the later admission checks.
    """
    previous = runtime.server(pair.target)
    candidate = runtime.server(pair.survivor)
    pair.verify(previous['addr'], candidate['addr'])
    return admission()
