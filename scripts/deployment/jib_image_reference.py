"""Resolve Jib's registry digest (not its local image configuration ID)."""
import argparse
import re
from pathlib import Path


def image_reference(digest: str) -> str:
    digest = digest.strip()
    if re.fullmatch(r'sha256:[0-9a-f]{64}', digest) is None:
        raise ValueError('Expected one sha256 registry digest from jib-image.digest')
    return 'chooh1010/popping-community@' + digest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('digest_file', type=Path)
    args = parser.parse_args()
    print(image_reference(args.digest_file.read_text(encoding='utf-8')))


if __name__ == '__main__':
    main()
