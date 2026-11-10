#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn clean test
mvn package
bash scripts/run-lab3.sh start
trap 'bash scripts/run-lab3.sh stop' EXIT
python3 scripts/verify.py
python3 scripts/benchmark.py --requests 80
bash scripts/run-lab3.sh stop
trap - EXIT
python3 scripts/process_failure.py
echo 'ALL LAB3 CHECKS PASSED'
