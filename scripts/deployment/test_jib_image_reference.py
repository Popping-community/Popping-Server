import unittest
from jib_image_reference import image_reference


class ImageReferenceTests(unittest.TestCase):
    def test_digest_file_newline_is_allowed(self) -> None:
        digest = 'sha256:' + 'a' * 64
        self.assertEqual('chooh1010/popping-community@' + digest, image_reference(digest + '\n'))

    def test_invalid_and_mutable_references_are_rejected(self) -> None:
        for value in ('', 'latest', 'sha256:abc', 'sha256:' + 'g' * 64,
                      'sha256:' + 'A' * 64, 'sha256:' + 'a' * 64 + '\nother',
                      '$(touch injected)', 'chooh1010/popping-community:latest'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                image_reference(value)


if __name__ == '__main__':
    unittest.main()
