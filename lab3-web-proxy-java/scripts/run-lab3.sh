#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-start}"
if [[ "$mode" == "start" ]]; then
  mkdir -p target
  javac --release 17 -d target/classes $(find src/main/java -name '*.java')
  java -cp target/classes md.lab3.Main storage & echo $! > target/lab3-storage.pid
  sleep .4
  java -cp target/classes md.lab3.Main backend 9011 dw-a & echo $! > target/lab3-a.pid
  java -cp target/classes md.lab3.Main backend 9012 dw-b & echo $! > target/lab3-b.pid
  sleep .4
  java -cp target/classes md.lab3.Main proxy & echo $! > target/lab3-proxy.pid
  echo 'Services started. Run: bash scripts/run-lab3.sh stop to stop all.'
elif [[ "$mode" == "stop" ]]; then
  for f in target/lab3-{proxy,a,b,storage}.pid; do
    if [[ -e "$f" ]]; then kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; fi
  done
elif [[ "$mode" == "verify" ]]; then python3 scripts/verify.py
elif [[ "$mode" == "benchmark" ]]; then python3 scripts/benchmark.py --requests 80
else echo 'Use: start|stop|verify|benchmark' >&2; exit 1; fi
