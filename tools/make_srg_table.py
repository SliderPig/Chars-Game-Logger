# Builds src/main/resources/srg_methods.txt.gz from ForgeGradle's srg_to_official tsrg: "m_5703_=tickServer" lines,
# so the report can show Minecraft's method names instead of the scrambled ones. Run again after a Forge/Minecraft update.
import glob
import gzip
import os
import re

src = glob.glob(os.path.expanduser('~/.gradle/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/*/srg_to_official_1.20.1.tsrg'))[0]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'srg_methods.txt.gz')
rx = re.compile(r'^\t(m_\d+_)\s+\S+\s+(\S+)')
n = 0
with open(src, encoding='utf-8') as f, gzip.open(out, 'wt', encoding='utf-8') as o:
    for line in f:
        m = rx.match(line)
        if m:
            o.write(f'{m.group(1)}={m.group(2)}\n')
            n += 1
print(n, 'methods ->', os.path.getsize(out) // 1024, 'KB')
