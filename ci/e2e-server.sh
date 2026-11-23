#!/usr/bin/env bash
# A real csqtt server for the client's end-to-end tests (rust-client/turn_integration_tests.rs, the #[ignore] ones), on the
# CI runner or the laptop. The server runs in its own network namespace, so its sysctls, NAT and TUN never touch the host;
# the tests reach it over a veth pair through their own mock TURN relay. Nothing leaves the machine.
# Usage (root):
#   ci/e2e-server.sh up   <server binary> <state dir> [<standalone binary>]
#                                                       start both; prints PEER=, WEB=, PID=, LOG= and DEV_PEER=, DEV_WEB=,
#                                                       DEV_PID=, DEV_LOG= (also in <state dir>/env)
#   ci/e2e-server.sh down <state dir>                   stop them and remove the namespace
# Then, as the normal user: CSQTT_E2E_PEER=<PEER> CSQTT_E2E_WEB=<WEB> CSQTT_E2E_SERVER_LOG=<LOG>
#   CSQTT_E2E_DEV_PEER=<DEV_PEER> CSQTT_E2E_DEV_WEB=<DEV_WEB> ci/test.sh <source tree> e2e
# The passwords are the ones the tests carry: the live one is the server's main password (as on the WSL server the
# cases were written for), the expired and the deactivated ones come through --desired (the panel-owned password file); the web login
# e2e-test serves the hot-DNS case. A UDP echo on the server's tunnel address, port 47214, answers the throughput case.
# B6: a second, standalone server (no --desired: it tells a password's devices apart) runs beside it as instance e2edev,
# with its own TUN and /24 (10.66.68.0/24), for the devices_* cases; <standalone binary> (default: the same) lets a run
# put an older core on the --desired side (the shipped 2.5.3 for B6's case 9).
set -euo pipefail
NS=csqtte2e; HOST_IP=10.231.77.1; NS_IP=10.231.77.2; PORT=46400; WEB_PORT=46402; DEV_PORT=46410; DEV_WEB_PORT=46412
CI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

up() {
  local bin st dev_bin; bin=$(readlink -f "$1"); st=$2; dev_bin=$(readlink -f "${3:-$1}")
  down "$st"   # leftovers of a run that failed half-way would make `ip netns add` refuse
  mkdir -p "$st"; st=$(cd "$st" && pwd); rm -rf "$st/cfg" "$st/cfgdev"; mkdir -m 0700 "$st/cfg" "$st/cfgdev"
  ip netns add $NS
  ip link add ${NS}h type veth peer name ${NS}n
  ip link set ${NS}n netns $NS
  ip addr add $HOST_IP/30 dev ${NS}h; ip link set ${NS}h up
  ip netns exec $NS sh -c "ip link set lo up; ip addr add $NS_IP/30 dev ${NS}n; ip link set ${NS}n up; ip route add default via $HOST_IP"
  # a laptop with src_valid_mark=1 hands it to every new namespace; a server in the namespace does not need it
  ip netns exec $NS sysctl -qw net.ipv4.conf.all.src_valid_mark=0 || true
  cat > "$st/cfg/desired.json" <<'EOF'
{"passwords": [
  {"password": "e2e-expired-password-2026", "name": "e2e expired", "expires_at": 1},
  {"password": "e2e-deactivated-password-2026", "name": "e2e deactivated", "is_deactivated": true}
]}
EOF
  ip netns exec $NS "$bin" --config-dir "$st/cfg" --listen 0.0.0.0:$PORT --web-port $WEB_PORT \
    --password e2e-local-password-20260830 --web-user e2e-test --web-pass e2e-test-password \
    --desired "$st/cfg/desired.json" > "$st/server.log" 2>&1 &
  echo $! > "$st/server.pid"
  local i; for i in $(seq 1 60); do
    ip netns exec $NS ip -4 addr show 2>/dev/null | grep -q '10\.66\.67\.1/' && break; sleep 0.5
  done
  ip netns exec $NS ip -4 addr show | grep -q '10\.66\.67\.1/' || { echo "server TUN did not come up" >&2; tail -30 "$st/server.log" >&2; exit 1; }
  ip netns exec $NS python3 -I -c '
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 22)
s.bind(("10.66.67.1", 47214))
while True:
    d, a = s.recvfrom(2048); s.sendto(d, a)' > "$st/echo.log" 2>&1 &
  echo $! > "$st/echo.pid"
  ip netns exec $NS "$dev_bin" --config-dir "$st/cfgdev" --listen 0.0.0.0:$DEV_PORT --web-port $DEV_WEB_PORT \
    --instance e2edev --iface csqtte2d --tun-addr 10.66.68.1/24 \
    --password e2e-dev-main-password-2026 --web-user e2e-test --web-pass e2e-test-password > "$st/server-dev.log" 2>&1 &
  echo $! > "$st/server-dev.pid"
  for i in $(seq 1 60); do
    ip netns exec $NS ip -4 addr show 2>/dev/null | grep -q '10\.66\.68\.1/' && break; sleep 0.5
  done
  ip netns exec $NS ip -4 addr show | grep -q '10\.66\.68\.1/' || { echo "the standalone server's TUN did not come up" >&2; tail -30 "$st/server-dev.log" >&2; exit 1; }
  printf 'PEER=%s:%s\nWEB=https://%s:%s\nPID=%s\nLOG=%s\nDEV_PEER=%s:%s\nDEV_WEB=https://%s:%s\nDEV_PID=%s\nDEV_LOG=%s\n' \
    $NS_IP $PORT $NS_IP $WEB_PORT "$(cat "$st/server.pid")" "$st/server.log" \
    $NS_IP $DEV_PORT $NS_IP $DEV_WEB_PORT "$(cat "$st/server-dev.pid")" "$st/server-dev.log" | tee "$st/env"
}

down() {
  local st=$1 f
  for f in echo server server-dev; do [ -s "$st/$f.pid" ] && kill "$(cat "$st/$f.pid")" 2>/dev/null || true; done
  sleep 1
  ip netns pids $NS 2>/dev/null | xargs -r kill -9 2>/dev/null || true
  ip link del ${NS}h 2>/dev/null || true
  ip netns del $NS 2>/dev/null || true
  rm -f "$st/echo.pid" "$st/server.pid" "$st/server-dev.pid"   # a later run with this state dir must not signal a pid reused since
}

case "${1:-}" in
  up) up "$2" "$3" "${4:-}" ;;
  down) down "$2" ;;
  *) echo "usage: $0 up <server binary> <state dir> [<standalone binary>] | down <state dir>" >&2; exit 2 ;;
esac
