#!/bin/bash
for i in $(seq 1 $1); do curl -s "http://127.0.0.1:3029/drop?what=ptr" >/dev/null; sleep 0.4; done
