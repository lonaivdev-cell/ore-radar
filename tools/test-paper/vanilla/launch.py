"""launch.py MCDIR VERSION GAMEDIR NAME HEAP [HOST:PORT] -- the java argv of a vanilla client, one per line.

Builds the command line the way Mojang's launcher does from versions/VERSION/VERSION.json (fetched
by fetch.py), for an offline account NAME, and with HOST:PORT joins that server at once (quick
play). Extracts the Linux natives into GAMEDIR/natives. Exits 3 listing any missing file.
Stdlib only; run with python3 -I.
"""
import hashlib
import json
import os
import sys
import uuid
import zipfile


def allowed(rules, features):
    if not rules:
        return True
    result = False
    for r in rules:
        os_rule = r.get('os', {})
        if os_rule.get('name') not in (None, 'linux') or os_rule.get('arch') not in (None, 'x86_64', 'amd64'):
            continue
        if any(features.get(k, False) != v for k, v in r.get('features', {}).items()):
            continue
        result = r['action'] == 'allow'
    return result


def main(mc, version, game, name, heap, server=None):
    with open(f'{mc}/versions/{version}/{version}.json') as f:
        v = json.load(f)
    features = {'is_quick_play_multiplayer': bool(server)}
    classpath, natives = [], []
    for lib in v['libraries']:
        a = lib.get('downloads', {}).get('artifact')
        if not a or not allowed(lib.get('rules'), features):
            continue
        path = f"{mc}/libraries/{a['path']}"
        (natives if ':natives-' in lib['name'] else classpath).append(path)
    classpath.append(f'{mc}/versions/{version}/{version}.jar')
    missing = [p for p in classpath + natives if not os.path.exists(p)]
    if missing:
        print('missing (run fetch.py):', *missing, sep='\n', file=sys.stderr)
        sys.exit(3)
    native_dir = f'{game}/natives'
    # Up to 1.21.x java.library.path is the natives directory itself; 26.x names its java/ subdirectory.
    library_dirs = [native_dir, f'{native_dir}/java']
    for d in library_dirs:
        os.makedirs(d, exist_ok=True)
    for jar in natives:
        with zipfile.ZipFile(jar) as z:
            for entry in z.infolist():
                if entry.filename.endswith('.so') and '..' not in entry.filename:
                    for d in library_dirs:
                        dest = os.path.join(d, os.path.basename(entry.filename))
                        if not os.path.exists(dest):
                            with open(dest, 'wb') as out:
                                out.write(z.read(entry))
    offline_uuid = uuid.UUID(bytes=hashlib.md5(f'OfflinePlayer:{name}'.encode()).digest(), version=3)
    values = {
        'auth_player_name': name, 'version_name': version, 'game_directory': game,
        'assets_root': f'{mc}/assets', 'assets_index_name': v['assetIndex']['id'],
        'auth_uuid': offline_uuid.hex, 'auth_access_token': '0', 'clientid': '0', 'auth_xuid': '0',
        'user_type': 'legacy', 'version_type': 'release', 'natives_directory': native_dir,
        'launcher_name': 'oreradar-test-paper', 'launcher_version': '1.0',
        'classpath': ':'.join(classpath), 'classpath_separator': ':', 'library_directory': f'{mc}/libraries',
        'quickPlayMultiplayer': server or '',
    }

    def substitute(s):
        for k, val in values.items():
            s = s.replace('${%s}' % k, val)
        return s

    def expand(args):
        out = []
        for a in args:
            if isinstance(a, str):
                out.append(substitute(a))
            elif allowed(a.get('rules'), features):
                out += [substitute(x) for x in (a['value'] if isinstance(a['value'], list) else [a['value']])]
        return out

    jvm = [f'-Xmx{heap}', '-Xms1G', '-XX:+UseG1GC', '-XX:ParallelGCThreads=4', '-XX:ConcGCThreads=1',
           '-Dlog4j2.formatMsgNoLookups=true', f'-Djava.io.tmpdir={game}/tmp']
    jvm += expand(v['arguments'].get('jvm', []))
    game_args = expand(v['arguments'].get('game', []))
    # Some versions leave quick play to the launcher's feature flags; make sure it is there exactly once.
    if server and '--quickPlayMultiplayer' not in game_args:
        game_args += ['--quickPlayMultiplayer', server]
    print('\n'.join(jvm + [v['mainClass']] + game_args))


if __name__ == '__main__':
    if len(sys.argv) not in (6, 7):
        sys.exit(__doc__)
    main(*sys.argv[1:])
