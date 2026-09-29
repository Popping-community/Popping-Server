"""Execute the deployment shell with fake commands; never contact Docker/SSH."""
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name('deploy_digest.sh').resolve()
BASH = shutil.which('bash')
FAKES = r'''
function docker-compose() {
    printf 'compose %s\n' "$*" >> trace
    case "$*" in
        *'config --services')
            printf 'app-1\n'
            [ "$CASE" = missing-service ] || printf 'app-2\n'
            ;;
        *'pull app-1 app-2')
            cat "$4" > override-captured.json
            [ "$CASE" != pull-failure ]
            ;;
        *'up -d --no-deps app-1 app-2') [ "$CASE" != up-failure ] ;;
        *'ps -q app-1') printf 'app-1\n' ;;
        *'ps -q app-2') printf 'app-2\n' ;;
        *) return 91 ;;
    esac
}
function docker() {
    printf 'docker %s\n' "$*" >> trace
    case "$1 $2" in
        'login --username') cat >/dev/null ;;
        'image inspect') printf 'sha256:expected\n' ;;
        'inspect app-1'|'inspect app-2')
            case "$4" in
                '{{.Image}}')
                    if [ "$CASE" = wrong-image ]; then printf 'sha256:wrong\n'; else printf 'sha256:expected\n'; fi ;;
                '{{.Config.Image}}')
                    if [ "$CASE" = wrong-reference ]; then printf 'latest\n'; else printf '%s\n' "$POPPING_IMAGE"; fi ;;
                '{{.State.Running}}')
                    if [ "$CASE" = stopped ]; then printf 'false\n'; else printf 'true\n'; fi ;;
                *) return 92 ;;
            esac ;;
        *) return 93 ;;
    esac
}
. "$1"
'''


class DeployDigestTests(unittest.TestCase):
    def execute(self, case: str, image: str | None = None) -> tuple:
        self.assertIsNotNone(BASH, 'bash is required for deployment-script verification')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            if case != 'missing-config':
                (root / 'docker-compose.yml').write_text('services: {}\n', encoding='utf-8')
            image = image if image is not None else 'chooh1010/popping-community@sha256:' + 'a' * 64
            env = dict(os.environ, CASE=case, POPPING_IMAGE=image,
                       DOCKER_USERNAME='fixture', DOCKER_PASSWORD='private-fixture-token')
            completed = subprocess.run([BASH, '-c', FAKES, 'test', SCRIPT.as_posix()],
                                       cwd=root, env=env, capture_output=True, text=True, timeout=15)
            trace = (root / 'trace').read_text() if (root / 'trace').exists() else ''
            override = json.loads((root / 'override-captured.json').read_text()) if (root / 'override-captured.json').exists() else None
            # A created override is retained for Compose's config_files labels,
            # including when pull/up fails; it contains no credentials.
            saved = list(root.glob('popping-release.*.json'))
            self.assertEqual(1 if 'docker login' in trace else 0, len(saved))
            for path in saved:
                self.assertNotIn('private-fixture-token', path.read_text())
            self.assertNotIn('private-fixture-token', trace + completed.stdout + completed.stderr)
            return completed.returncode, trace, override

    def test_exact_digest_reaches_only_the_two_app_services(self) -> None:
        code, trace, override = self.execute('success')
        self.assertEqual(0, code, trace)
        self.assertIn('up -d --no-deps app-1 app-2', trace)
        expected = {'image': 'chooh1010/popping-community@sha256:' + 'a' * 64}
        self.assertEqual({'services': {'app-1': expected, 'app-2': expected}}, override)
        self.assertNotIn('docker-compose down', trace)

    def test_invalid_image_is_rejected_before_docker(self) -> None:
        for value in ('latest', 'chooh1010/popping-community:latest',
                      'chooh1010/popping-community@sha256:' + 'g' * 64):
            with self.subTest(value=value):
                code, trace, _ = self.execute('success', value)
                self.assertNotEqual(0, code)
                self.assertEqual('', trace)

    def test_missing_config_or_service_never_pulls_or_recreates(self) -> None:
        for case in ('missing-config', 'missing-service'):
            with self.subTest(case=case):
                code, trace, _ = self.execute(case)
                self.assertNotEqual(0, code)
                self.assertNotIn('pull app-', trace)
                self.assertNotIn('up -d', trace)

    def test_pull_failure_never_recreates(self) -> None:
        code, trace, _ = self.execute('pull-failure')
        self.assertNotEqual(0, code)
        self.assertNotIn('up -d', trace)

    def test_up_failure_and_post_deploy_drift_are_not_success(self) -> None:
        for case in ('up-failure', 'wrong-image', 'wrong-reference', 'stopped'):
            with self.subTest(case=case):
                code, _, _ = self.execute(case)
                self.assertNotEqual(0, code)


if __name__ == '__main__':
    unittest.main()
