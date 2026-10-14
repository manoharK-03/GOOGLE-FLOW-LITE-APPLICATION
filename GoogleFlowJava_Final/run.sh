#!/bin/bash
# usage: ./run.sh build | web | terminal | backend [external] | workers
cd "$(dirname "$0")/server" || exit 1

build() {
  mkdir -p src/out && javac -d src/out src/*.java && echo "Build OK"
}

# build automatically the first time
if [ "$1" != "build" ] && [ ! -f src/out/FlowBackend.class ]; then build || exit 1; fi

case "$1" in
  build)    build ;;
  web)      java -cp src/out GoogleFlowWebServer public storage ;;
  terminal) java -cp src/out GoogleFlowTerminal storage ;;
  backend)  java -cp src/out FlowBackend storage $2 ;;
  workers)  java -cp src/out FlowWorkerNode 9101 9102 9103 ;;
  *)        echo "usage: ./run.sh build | web | terminal | backend [external] | workers" ;;
esac