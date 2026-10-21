#!/usr/bin/env python3
"""Verify the licensed depth input; download only with explicit license acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request
from urllib.parse import urlparse


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        if urlparse(new_url).scheme != 'https':
            raise RuntimeError('Non-HTTPS redirect')
        return super().redirect_request(request, response, code, message, headers, new_url)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fetch-licensed-depth', action='store_true',
                        help='Download the pinned deployment model after reviewing its license.')
    parser.add_argument('--accept-deployment-license', action='store_true',
                        help='Confirm acceptance of the bundled Qualcomm deployment license.')
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    model = next(a for a in json.loads((here / 'dependencies.json').read_text())['artifacts']
                 if a.get('asset') == 'portrait_depth_v2_small.tflite')
    target = here.parents[1] / 'app/src/main/assets/portrait_depth_v2_small.tflite'
    license_path = target.with_name('portrait_depth_v2_small.DEPLOYMENT_MODEL_LICENSE.pdf')
    if digest(license_path) != model['deploymentLicenseSha256']:
        raise SystemExit('Deployment license checksum mismatch.')
    if target.is_symlink():
        raise SystemExit('Refusing a symbolic-link destination.')
    if target.exists():
        if target.stat().st_size != model['bytes'] or digest(target) != model['sha256']:
            raise SystemExit('Existing depth model differs; it has not been overwritten.')
        print('Pinned depth model verified; no network request made.')
        return
    if not (args.fetch_licensed_depth and args.accept_deployment_license):
        raise SystemExit('Depth model missing. Review the bundled deployment license, then run '
                         'with --fetch-licensed-depth --accept-deployment-license.')
    if urlparse(model['url']).scheme != 'https':
        raise SystemExit('Only the pinned HTTPS source is allowed.')
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix='.depth-', delete=False) as output:
            temporary = Path(output.name)
            with urllib.request.build_opener(HttpsRedirects()).open(model['url'], timeout=60) as response:
                if urlparse(response.geturl()).scheme != 'https':
                    raise RuntimeError('Non-HTTPS redirect')
                total = 0
                while True:
                    chunk = response.read(min(1024 * 1024, model['bytes'] + 1 - total))
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > model['bytes']:
                        raise RuntimeError('Oversized response')
                    output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if temporary.stat().st_size != model['bytes'] or digest(temporary) != model['sha256']:
            raise RuntimeError('Model checksum mismatch')
        # An atomic, no-clobber link prevents a concurrent run from replacing an input.
        os.link(temporary, target)
        print('Pinned depth model downloaded and verified for local application builds.')
    except Exception:
        raise SystemExit('Depth model preparation failed; no existing input was replaced.') from None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
