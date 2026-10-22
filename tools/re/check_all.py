"""對全部 EEX 跑解析器，統計成功/失敗。"""
import collections, glob, os, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.dirname(__file__))
from eexemu import EexEmu
from eex import EexParser, ParseError

game = sys.argv[1]
p = EexParser(EexEmu(os.path.join(game, 'Ekd5.exe')))
ok = 0; errs = collections.Counter(); samples = {}
ops = collections.Counter()
t = time.time()
files = sorted(glob.glob(os.path.join(game, 'RS', '*.eex')))
for f in files:
    try:
        scenes = p.parse(open(f, 'rb').read())
        ok += 1
    except (ParseError, Exception) as e:
        key = str(e).split(':', 1)[-1].strip()[:60]
        k2 = type(e).__name__ + ' ' + (key.split('，')[0] if 'op' in key else key[:30])
        errs[k2] += 1; samples.setdefault(k2, (os.path.basename(f), str(e)))
print(f'{ok}/{len(files)} 成功，{time.time()-t:.0f}s')
for k, c in errs.most_common(30):
    print(c, samples[k])
