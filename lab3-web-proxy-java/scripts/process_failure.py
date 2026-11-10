#!/usr/bin/env python3
"""Demonstrate independent DW process crash, recovery and persistent shared data.
Run only when ports 9020, 9011, 9012, 18080 are free and Maven package exists.
"""
import datetime as dt
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / 'target/lab3-web-proxy-1.0.0.jar'
PROXY = 'http://127.0.0.1:18080'
STORAGE = 'http://127.0.0.1:9020'
A = 'http://127.0.0.1:9011'
B = 'http://127.0.0.1:9012'


def request(url, method='GET', headers=None):
    req = urllib.request.Request(url, method=method, headers=headers or {},
                                 data=b'' if method == 'PUT' else None)
    try:
        with urllib.request.urlopen(req, timeout=3) as r:
            return r.status, r.read().decode(), dict(r.headers.items())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), dict(e.headers.items())
    except (urllib.error.URLError, TimeoutError):
        return 0, '', {}


def wait_for(url, test, seconds=8):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        code, body, headers = request(url)
        if test(code, body, headers):
            return code, body, headers
        time.sleep(.18)
    raise AssertionError(f'Timed out waiting for {url}')


def main():
    if not JAR.is_file():
        raise SystemExit('Rulează mai întâi: mvn package')
    for port in (9020,9011,9012,18080):
        with socket.socket() as s:
            s.settimeout(.2)
            if s.connect_ex(('127.0.0.1',port)) == 0:
                raise SystemExit(f'Portul {port} este deja ocupat. Oprește laboratorul înainte de test.')
    logdir = ROOT / 'target' / 'process-failure-logs'
    logdir.mkdir(parents=True,exist_ok=True)
    processes = {}
    streams = []
    def launch(key,*args):
        out = (logdir / f'{key}.log').open('w',encoding='utf8')
        streams.append(out)
        proc = subprocess.Popen(['java','-jar',str(JAR),*args],cwd=ROOT,stdout=out,stderr=subprocess.STDOUT)
        processes[key] = proc
        return proc
    def terminate(key):
        p = processes.pop(key,None)
        if p is not None and p.poll() is None:
            p.terminate()
            try: p.wait(timeout=4)
            except subprocess.TimeoutExpired: p.kill();p.wait(timeout=3)
    info = {'timestamp_utc':dt.datetime.now(dt.timezone.utc).isoformat(),'scenarios':{}}
    try:
        launch('storage','storage');wait_for(STORAGE+'/health',lambda s,b,h:s==200)
        launch('dw-a','backend','9011','dw-a')
        launch('dw-b','backend','9012','dw-b')
        wait_for(A+'/health',lambda s,b,h:s==200)
        wait_for(B+'/health',lambda s,b,h:s==200)
        launch('proxy','proxy');wait_for(PROXY+'/health',lambda s,b,h:s==200)
        identifier = 'ORD-TEST-' + str(int(time.time()))
        corr = 'process-test-'+str(int(time.time()))
        code,_,headers = request(f'{PROXY}/api/orders/{identifier}?status=INITIAL','PUT',
                                 {'X-Correlation-Id':corr})
        assert code == 200 and next((v for k,v in headers.items() if k.lower() == 'x-correlation-id'),None) == corr
        assert request(f'{A}/api/orders/{identifier}')[1] == request(f'{B}/api/orders/{identifier}')[1]
        info['scenarios']['shared_state_before_failure'] = 'PASS'

        terminate('dw-a')
        wait_for(PROXY+'/metrics',lambda s,b,h:s==200 and not json.loads(b)['backends'][0]['healthy'])
        code,body,headers=request(f'{PROXY}/api/orders/{identifier}?after-kill={time.time_ns()}')
        assert code==200 and next((v for k,v in headers.items() if k.lower() == 'x-backend'),None)=='dw-b', (code,body,headers)
        code,_,_=request(f'{PROXY}/api/orders/{identifier}?status=PERSISTED','PUT')
        assert code==200, code
        info['scenarios']['dw_a_killed_and_dw_b_continues'] = 'PASS'

        launch('dw-a-restarted','backend','9011','dw-a')
        wait_for(PROXY+'/metrics',lambda s,b,h:s==200 and json.loads(b)['backends'][0]['healthy'])
        assert 'PERSISTED' in request(f'{A}/api/orders/{identifier}')[1]
        assert 'PERSISTED' in request(f'{B}/api/orders/{identifier}')[1]
        info['scenarios']['dw_a_restart_sees_shared_state'] = 'PASS'

        terminate('storage')
        wait_for(A+'/health',lambda s,b,h:s==503)
        wait_for(B+'/health',lambda s,b,h:s==503)
        info['scenarios']['storage_is_single_point_of_failure'] = 'CONFIRMED'
        launch('storage-restarted','storage')
        wait_for(STORAGE+'/health',lambda s,b,h:s==200)
        wait_for(A+'/health',lambda s,b,h:s==200)
        wait_for(B+'/health',lambda s,b,h:s==200)
        assert 'PERSISTED' in request(f'{A}/api/orders/{identifier}')[1]
        info['scenarios']['storage_restart_preserves_data'] = 'PASS'
        info['record_id'] = identifier
        info['correlation_id'] = corr
        info['result'] = 'PASS'
        print('PASS: DW A stopped independently; DW B served reads/writes; DW A and storage recovered; persisted data survived.')
    finally:
        for key in list(processes): terminate(key)
        for stream in streams: stream.close()
    # Verify that the client-supplied correlation ID reached at least one DW log.
    combined = "\n".join(path.read_text(encoding='utf8',errors='replace')
                         for path in logdir.glob('dw-*.log'))
    assert f'correlationId={corr}' in combined, 'Correlation ID absent from DW logs'
    info['scenarios']['correlation_id_in_dw_logs'] = 'PASS'
    info['dw_log_files'] = [str(path.relative_to(ROOT)) for path in logdir.glob('dw-*.log')]
    target = ROOT / 'docs/evidence/process-failure-run.json'
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(info,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
    print('Saved:',target)


if __name__=='__main__':
    main()
