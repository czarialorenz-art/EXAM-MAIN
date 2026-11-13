#!/usr/bin/env bash
# Build and run the Quiz & Examination Management System (Linux/macOS).
#   ./run.sh            compile and start the application (data in ./data)
#   ./run.sh --demo     start and create demo records if the data folder is empty
#   ./run.sh test       compile and run the automated tests
#   ./run.sh build      compile only
#   ./run.sh web        start the website at http://localhost:8080 (add --demo, --port N)
set -euo pipefail
cd "$(dirname "$0")"

build() {
  rm -rf out
  mkdir -p out
  find src -name '*.java' > out/sources.txt
  if [ "${1:-}" = "tests" ]; then find test -name '*.java' >> out/sources.txt; fi
  javac --release 17 -encoding UTF-8 -d out @out/sources.txt
}

case "${1:-}" in
  test)  build tests && java -cp out tests.TestRunner ;;
  build) build && echo "Compiled into ./out" ;;
  web)   shift; build && java -cp out web.WebMain "$@" ;;
  *)     build && java -cp out main.Main "$@" ;;
esac
