# Starts the real game (the same Forge the Modrinth app uses) in a throwaway folder run-real/ with ONLY this mod, built with
# -Psmoke so SelfTest is in it. SelfTest makes a flat world, runs /clogger start, 60 s later /clogger stop, and quits.
#   bash grad.sh <project> build -Psmoke     then (through heavy.sh)     python tools/run_real_client.py
# The report ends up in run-real/clogger/.
import glob
import json
import os
import shutil
import subprocess
import sys

M = os.path.join(os.environ['APPDATA'], 'ModrinthApp')
META = os.path.join(M, 'meta')
VERSION = '1.20.1-47.4.28'
JAVA = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
RUN = os.path.abspath('run-real')
TIMEOUT = 420


def allowed(rules):
    ok = not rules
    for r in rules or []:
        name = (r.get('os') or {}).get('name')
        if name in (None, 'windows') and 'features' not in r:
            ok = r['action'] == 'allow'
    return ok


def library_path(lib):
    art = (lib.get('downloads') or {}).get('artifact')
    if art and art.get('path'):
        return art['path']
    group, name, ver = lib['name'].split(':')[:3]
    return f"{group.replace('.', '/')}/{name}/{ver}/{name}-{ver}.jar"


def main():
    d = json.load(open(os.path.join(META, 'versions', VERSION, VERSION + '.json'), encoding='utf-8'))
    cp, seen = [], set()
    for lib in d['libraries']:
        if lib.get('include_in_classpath') is False or not allowed(lib.get('rules')) or 'natives' in lib['name'].split(':')[-1] and 'windows' not in lib['name']:
            continue
        path = os.path.join(META, 'libraries', library_path(lib))
        if os.path.exists(path) and path not in seen:
            seen.add(path)
            cp.append(path)
    cp.append(os.path.join(META, 'versions', VERSION, VERSION + '.jar'))

    os.makedirs(os.path.join(RUN, 'mods'), exist_ok=True)
    for old in glob.glob(os.path.join(RUN, 'mods', '*.jar')):
        os.remove(old)
    shutil.rmtree(os.path.join(RUN, 'saves'), ignore_errors=True)
    shutil.rmtree(os.path.join(RUN, 'clogger'), ignore_errors=True)
    options = os.path.join(RUN, 'options.txt')
    if not os.path.exists(options):
        with open(options, 'w', encoding='utf-8') as f:
            f.write(chr(10).join(['onboardAccessibility:false', 'tutorialStep:none', 'pauseOnLostFocus:false', 'lang:en_us', 'maxFps:260', 'enableVsync:false']) + chr(10))
    built = sorted(glob.glob('build/libs/clogger-*.jar'), key=os.path.getmtime)[-1]
    shutil.copy(built, os.path.join(RUN, 'mods', 'clogger-smoke.jar'))

    natives = os.path.join(META, 'natives', VERSION)
    values = {'natives_directory': natives, 'launcher_name': 'clogger-test', 'launcher_version': '1', 'classpath': os.pathsep.join(cp), 'classpath_separator': os.pathsep,
              'library_directory': os.path.join(META, 'libraries'), 'version_name': VERSION, 'auth_player_name': 'Dev', 'game_directory': RUN, 'assets_root': os.path.join(META, 'assets'),
              'assets_index_name': d['assets'], 'auth_uuid': '00000000000000000000000000000abc', 'auth_access_token': '0', 'clientid': '0', 'auth_xuid': '0', 'user_type': 'legacy', 'version_type': 'release'}

    def fill(text):
        for key, value in values.items():
            text = text.replace('${' + key + '}', value)
        return text

    def flat(args):
        out = []
        for a in args:
            if isinstance(a, str):
                out.append(fill(a))
            elif allowed(a.get('rules')):
                v = a['value']
                out.extend(fill(x) for x in ([v] if isinstance(v, str) else v))
        return out

    cmd = [JAVA, '-Xmx2500M', '-Dclogger.selftest=true'] + flat(d['arguments']['jvm']) + [d['mainClass']] + flat(d['arguments']['game']) + ['--width', '1280', '--height', '720']
    with open(os.path.join(RUN, 'launch.log'), 'w', encoding='utf-8', errors='replace') as log:
        try:
            result = subprocess.run(cmd, cwd=RUN, stdout=log, stderr=subprocess.STDOUT, timeout=TIMEOUT)
            print('exit', result.returncode)
        except subprocess.TimeoutExpired:
            print('timed out after', TIMEOUT, 's')
    print('reports:', glob.glob(os.path.join(RUN, 'clogger', '*')))
    return 0


if __name__ == '__main__':
    sys.exit(main())
