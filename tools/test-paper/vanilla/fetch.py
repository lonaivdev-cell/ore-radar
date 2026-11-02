"""fetch.py MCDIR VERSION -- a vanilla Minecraft client in a launcher-style MCDIR, from Mojang.

Downloads the version json, the client jar, the Linux libraries (natives included) and the asset
index, all sha1-checked. Asset objects already in Loom's cache (~/.gradle/caches/fabric-loom/assets,
filled by the 1.20.1 and 1.21.1 dev clients) are symlinked instead of downloaded, so only the
objects a version adds cost bandwidth and tmpfs space. Re-running only checks what is there.
Stdlib only; run with python3 -I.
"""
import concurrent.futures
import hashlib
import json
import os
import sys
import urllib.request

MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
RESOURCES = 'https://resources.download.minecraft.net'
LOOM_OBJECTS = os.path.expanduser('~/.gradle/caches/fabric-loom/assets/objects')
UA = {'User-Agent': 'oreradar-test-paper/1.0'}


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def get(url, dest, sha1):
    """Downloads url to dest unless dest already has that sha1; retries a few times."""
    if os.path.exists(dest) and sha1_of(dest) == sha1:
        return 'cached'
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    error = None
    for _ in range(4):
        try:
            data = fetch(url)
            if hashlib.sha1(data).hexdigest() != sha1:
                raise ValueError(f'sha1 mismatch for {url}')
            tmp = dest + '.part'
            with open(tmp, 'wb') as f:
                f.write(data)
            os.replace(tmp, dest)
            return 'downloaded'
        except (OSError, ValueError) as e:
            error = e
    raise RuntimeError(f'{url}: {error!r}')


def allowed(rules):
    """The launcher's library rules, for Linux with no optional features."""
    if not rules:
        return True
    result = False
    for r in rules:
        if r.get('os', {}).get('name') not in (None, 'linux') or 'features' in r:
            continue
        result = r['action'] == 'allow'
    return result


def asset(mc, h, size):
    dest = f'{mc}/assets/objects/{h[:2]}/{h}'
    if os.path.lexists(dest):
        return 'cached'
    cached = f'{LOOM_OBJECTS}/{h[:2]}/{h}'
    if os.path.exists(cached) and os.path.getsize(cached) == size:
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        os.symlink(cached, dest)
        return 'linked'
    return get(f'{RESOURCES}/{h[:2]}/{h}', dest, h)


def main(mc, version):
    manifest = json.loads(fetch(MANIFEST))
    entry = next((v for v in manifest['versions'] if v['id'] == version), None)
    if entry is None:
        sys.exit(f'no version {version} in the Mojang manifest')
    vjson = f'{mc}/versions/{version}/{version}.json'
    get(entry['url'], vjson, entry['sha1'])
    with open(vjson) as f:
        v = json.load(f)
    client = v['downloads']['client']
    jobs = [(client['url'], f'{mc}/versions/{version}/{version}.jar', client['sha1'])]
    for lib in v['libraries']:
        if not allowed(lib.get('rules')):
            continue
        a = lib.get('downloads', {}).get('artifact')
        if a:
            jobs.append((a['url'], f"{mc}/libraries/{a['path']}", a['sha1']))
    index = v['assetIndex']
    index_file = f"{mc}/assets/indexes/{index['id']}.json"
    get(index['url'], index_file, index['sha1'])
    with open(index_file) as f:
        objects = json.load(f)['objects']
    counts = {}
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        futures = [pool.submit(get, *job) for job in jobs]
        futures += [pool.submit(asset, mc, o['hash'], o['size']) for o in objects.values()]
        for fut in concurrent.futures.as_completed(futures):
            state = fut.result()
            counts[state] = counts.get(state, 0) + 1
    print(f'{version}: java {v.get("javaVersion", {}).get("majorVersion")}, {len(jobs)} jars, '
          f'{len(objects)} asset objects; ' + ', '.join(f'{n} {k}' for k, n in sorted(counts.items())))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
