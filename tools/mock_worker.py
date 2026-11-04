#!/usr/bin/env python3
"""Protocol-only queue consumer. NEVER renders or marks a request as successfully rendered."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import tempfile
import time

ID=re.compile(r'[a-zA-Z0-9_-]{8,80}')


def atomic_json(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile('w',dir=path.parent,delete=False) as stream:
        temp=Path(stream.name);json.dump(data,stream);stream.flush();os.fsync(stream.fileno())
    try:os.replace(temp,path)
    finally:temp.unlink(missing_ok=True)


def once(root):
    root=Path(root);inbox=root/'inbox';recent=[]
    if inbox.is_dir():
        for path in sorted(inbox.glob('*.json')):
            if not ID.fullmatch(path.stem) or path.is_symlink() or path.stat().st_size>32768:continue
            result=root/'results'/path.name
            if result.exists():continue
            try:
                request=json.loads(path.read_text())
                if request.get('kind') not in ('area','camera','video'):raise ValueError('unknown kind')
                cancelled=(root/'cancel'/path.stem).exists()
                item={'id':path.stem,'stage':'cancelled' if cancelled else 'error',
                      'error':'Mock worker: protocol acknowledged; no rendering performed.'}
            except (ValueError,AttributeError):
                item={'id':path.stem,'stage':'error','error':'Mock worker: invalid request JSON/kind.'}
            atomic_json(result,item);recent.append(item)
    atomic_json(root/'status.json',{'updated':dt.datetime.now(dt.timezone.utc).isoformat(),'recent':recent,'mock':True})
    return recent


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--queue',required=True,type=Path);parser.add_argument('--once',action='store_true')
    args=parser.parse_args()
    while True:
        print(json.dumps(once(args.queue)),flush=True)
        if args.once:break
        time.sleep(2)
