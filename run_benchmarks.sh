#!/bin/bash
set -e

echo "Compiling..."
mkdir -p out
javac -d out src/main/java/com/stripedkv/*.java src/test/java/com/stripedkv/*.java

echo "--- RUNNING GLOBAL LOCK BENCHMARK ---"
java -cp out com.stripedkv.Server global > server_global.log 2>&1 &
SERVER_PID=$!
sleep 2

java -cp out com.stripedkv.BenchmarkClient > bench_global.log
kill $SERVER_PID
sleep 1

echo "--- RUNNING STRIPED LOCK BENCHMARK ---"
java -cp out com.stripedkv.Server striped > server_striped.log 2>&1 &
SERVER_PID=$!
sleep 2

java -cp out com.stripedkv.BenchmarkClient > bench_striped.log
kill $SERVER_PID

echo "Benchmarks complete. Output in bench_global.log and bench_striped.log."
