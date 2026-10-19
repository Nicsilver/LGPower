import sys, re, collections
log = sys.argv[1]
start = int(sys.argv[2]) if len(sys.argv) > 2 else 0
end = int(sys.argv[2+1]) if len(sys.argv) > 3 and sys.argv[3] != '-' else 10**9
lines = open(log, encoding='utf-8', errors='replace').read().split('\n')[start:end]

def ts(l):
    m = re.match(r'\[(\d\d):(\d\d):(\d\d)\.(\d{3})\]', l)
    return int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + int(m[4]) / 1000 if m else None

cmd_conn = [l for l in lines if re.search(r'\[cmd c\d+\] (connected|STANDBY)', l)]
ptr_conn = [l for l in lines if re.search(r'\[ptr p\d+\] (connected|STANDBY)', l)]
off_att = [l for l in lines if '[off] tcp attempt' in l]
regs = [l for l in lines if 'REGISTER' in l]
nokey = [l for l in regs if 'key=NONE' in l]
prompts = [l for l in lines if 'PAIRING PROMPT' in l]
standby = [l for l in lines if 'STANDBY' in l]
frames = [l for l in lines if re.search(r'\[ptr p\d+\] #\d+', l)]
t0 = ts(lines[0]) if lines and ts(lines[0]) else None

def kind(l):
    m = re.search(r'#\d+ (KEY (\w+)|CLICK|move|scroll)', l)
    if not m: return '?'
    return m[2] if m[2] else m[1]

seq = [(ts(l), kind(l), re.search(r'\[ptr (p\d+)\]', l)[1]) for l in frames]
ptr_ids = sorted(set(p for _, _, p in seq))
cnt = collections.Counter(k for _, k, _ in seq)
print(f"cmd conns={len(cmd_conn)} ptr conns={len(ptr_conn)} off_attempts={len(off_att)} registers={len(regs)} (nokey={len(nokey)}) prompts={len(prompts)}")
print("frames:", dict(cnt), " on pointer conns:", ptr_ids)
# compress key sequence
keys = [k for _, k, _ in seq if k not in ('move', 'scroll')]
comp = []
for k in keys:
    if comp and comp[-1][0] == k: comp[-1][1] += 1
    else: comp.append([k, 1])
print("key/click order:", ' '.join(f"{k}x{n}" if n > 1 else k for k, n in comp))
if seq:
    print(f"first frame +{seq[0][0]-t0:.2f}s last +{seq[-1][0]-t0:.2f}s")
# attempts per second (any inbound TCP/ws connection attempt)
att = [ts(l) for l in cmd_conn + ptr_conn + off_att]
if att:
    per = collections.Counter(int(a) for a in att)
    print(f"attempts: total={len(att)} over {max(att)-min(att):.1f}s, max/sec={max(per.values())}")
# last open count
st = [l for l in lines if '[open cmd=' in l]
if st:
    print("last open state:", st[-1].strip())
if len(sys.argv) > 4 and sys.argv[4] == 'v':
    for l in lines:
        if '<- type' in l and 'getVolume' not in l: continue
        print(l)
