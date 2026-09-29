#!/usr/bin/env python3
"""Query the Sanny Builder Library (sa.json) for San Andreas opcode metadata.

Usage:  ./sbl.py 0AE1 08E5 051A ...      (hex ids, case-insensitive, with or without 0x)
        ./sbl.py -s <substr>              (search by name substring)
"""
import json, sys, os

HERE = os.path.dirname(os.path.abspath(__file__))

# The Sanny Builder Library dump (https://github.com/sannybuilder/library) is
# the authority for what the *game* implements; gta3sc's own config/*.xml is the
# authority for what the *compiler* accepts.  Clone it next to this file:
#   git clone --depth 1 https://github.com/sannybuilder/library.git tools/sbl
BASE = os.path.join(HERE, 'sbl', 'sa', 'sa.json')
DB = None


def load():
    global DB
    if DB is None:
        if not os.path.exists(BASE):
            sys.exit(
                'error: %s not found.\n'
                '  git clone --depth 1 https://github.com/sannybuilder/library.git %s'
                % (BASE, os.path.join(HERE, 'sbl')))
        with open(BASE, encoding='utf-8') as f:
            DB = json.load(f)
    return DB


def index():
    d = load()
    out = {}
    for ext in d['extensions']:
        for c in ext['commands']:
            key = c['id'].upper()
            rec = dict(c)
            rec['_ext'] = ext['name']
            out.setdefault(key, rec)
    return out


def fmt_args(c, key):
    parts = []
    for a in c.get('input', []) or []:
        t = a.get('type', '?')
        parts.append(f"{a.get('name','?')}:{t}")
    ret = [f"{a.get('name','?')}:{a.get('type','?')}" for a in (c.get('output') or [])]
    s = f"[{key}] {c.get('name')}  ext={c.get('_ext')}"
    s += f"  num_params={c.get('num_params')}"
    for attr in ('is_condition', 'is_unsupported', 'is_branch', 'is_nop', 'is_overload', 'class'):
        if attr in c:
            s += f"  {attr}={c[attr]}"
    s += "\n    in : " + ", ".join(parts)
    s += "\n    out: " + ", ".join(ret)
    if c.get('short_desc'):
        s += "\n    desc: " + c['short_desc'].replace('\n', ' ')
    if c.get('sbl_syntax') or c.get('syntax'):
        pass
    return s


def main():
    idx = index()
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    if args[0] == '-s':
        pat = args[1].upper()
        hits = [k for k, v in idx.items() if pat in (v.get('name') or '').upper()]
        hits.sort()
        for k in hits:
            print(fmt_args(idx[k], k))
        print(f"({len(hits)} hits)")
        return
    for a in args:
        k = a.upper()
        if k.startswith('0X'):
            k = k[2:]
        k = k.zfill(4)
        if k in idx:
            print(fmt_args(idx[k], k))
        else:
            print(f"[{k}] NOT FOUND")
        print()


if __name__ == '__main__':
    main()
