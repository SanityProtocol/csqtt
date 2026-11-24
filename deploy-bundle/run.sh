# shellcheck shell=dash
# SPDX-FileCopyrightText: 2026 SanityProtocol
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# deploy v3's run on the VPS (CSQTT-APP-PLAN §6.3 "the run", §6.7; P2-PLAN §1.4; P2 B7a). The engine uploads lib/marker.sh and
# this file as one script into a private directory with the run's plan (key=value, no secret) and its secrets (0600), then:
#   sh run.sh start <run-id> <upload dir>   as root: one run per box (/run/sanity-csqtt-deploy.lock), its directory under
#                                           /var/lib/sanityprotocol/csqtt-deploy/<run-id>/, the last 5 kept, then detached
#                                           as the transient unit sanity-csqtt-deploy-<run-id>, whose life is the run's
#   sh <run dir>/run.sh tail <run-id> <offset>   as the login user, no sudo: CSQTT_TAIL|<active|ended>|<size>, then the
#                                           log's bytes from <offset>
#   sh <run dir>/run.sh cancel <run-id>     as root: the run stops at its next safe point (never inside the switch)
#   sh run.sh status                        any user: one CSQTT_RUN line per run kept, which is how an interrupted run
#                                           (a log with no result and no live unit) is found
# The run (`exec`, in the unit) writes its log as marker lines: CSQTT_PHASE, CSQTT_PCT (never goes back), CSQTT_BEAT every
# 5 s while it waits (naming the wait), CSQTT_STEP, then CSQTT_RESULT|<kind>|<step>|<cause>|<next> and the end line.
# Results: ok, ok-unreachable-from-outside, failed-cleaned, failed-rolled-back, failed-not-rolled-back. Secrets never reach
# the log: every line a command prints goes through run_redact, and the secrets file is deleted when the run ends.

PATH=/usr/sbin:/usr/bin:/sbin:/bin:$PATH
export LC_ALL=C
RUNS=/var/lib/sanityprotocol/csqtt-deploy
LOCK=/run/sanity-csqtt-deploy.lock
KEEP=5

run_dir() { echo "$RUNS/$1"; }
valid_id() { case $1 in '' | *[!a-z0-9-]*) return 1 ;; *) return 0 ;; esac; }

cmd_start() {
	rs_id=$1 rs_up=$2
	# sudo passes the login's umask (027 on a hardened box) and a parent made under it would shut the login out of tail
	umask 022
	valid_id "$rs_id" || { csqtt_emit START refused "a run id is a-z, 0-9 and -"; csqtt_end; exit 2; }
	[ -f "$rs_up/plan" ] && [ -f "$rs_up/run.sh" ] || { csqtt_emit START refused "no plan in $rs_up"; csqtt_end; exit 2; }
	# one run per box: a held lock or a live run unit means another run (the app offers to watch it)
	rs_busy=''
	if [ -e $LOCK ] && ! flock -n $LOCK true 2>/dev/null; then rs_busy=lock; fi
	rs_live=$(systemctl list-units --no-legend --plain 'sanity-csqtt-deploy-*' 2>/dev/null | awk '$3 == "active" {print $1; exit}')
	if [ -n "$rs_busy" ] || [ -n "$rs_live" ]; then
		rs_live=${rs_live#sanity-csqtt-deploy-}
		csqtt_emit START busy "${rs_live%.service}"
		csqtt_end
		exit 3
	fi
	# no run lives: what a killed or rebooted run left of its secrets and raw output goes now (its own ExecStopPost covers a
	# stop; a power cut leaves them for this sweep)
	rm -f "$RUNS"/*/secrets "$RUNS"/*/out 2>/dev/null
	mkdir -p "$RUNS" && chmod 755 "${RUNS%/*}" "$RUNS"
	rs_dir=$(run_dir "$rs_id")
	if [ -e "$rs_dir/log" ]; then csqtt_emit START refused "run $rs_id exists"; csqtt_end; exit 2; fi
	# the run dir lets others through to the files they may read (run.sh, plan), and lists nothing; the log is the login
	# user's own (P2-PLAN §1.2), whom sudo names, root when root signed in
	rs_uid=${SUDO_UID:-0}
	case $rs_uid in '' | *[!0-9]*) rs_uid=0 ;; esac
	mkdir -p "$rs_dir" && chmod 711 "$rs_dir"
	install -m 644 "$rs_up/run.sh" "$rs_dir/run.sh"
	install -m 644 "$rs_up/plan" "$rs_dir/plan"
	if [ -f "$rs_up/secrets" ]; then
		install -m 600 "$rs_up/secrets" "$rs_dir/secrets"
		# the upload's copy goes here, on the box: the engine's own removal needs a link that may be gone
		rm -f "$rs_up/secrets"
	fi
	: >"$rs_dir/log" && chown "$rs_uid" "$rs_dir/log" && chmod 600 "$rs_dir/log"
	# the last KEEP runs are kept (the newest by name: run ids start with their UTC time)
	ls -1 "$RUNS" 2>/dev/null | sort | awk -v keep=$KEEP '{a[NR] = $0} END {for (i = 1; i <= NR - keep; i++) print a[i]}' |
		while IFS= read -r rs_old; do
			[ "$rs_old" = "$rs_id" ] || rm -rf "${RUNS:?}/$rs_old"
		done
	if ! systemd-run --unit "sanity-csqtt-deploy-$rs_id" --collect --quiet --property=KillMode=mixed \
		--property="ExecStopPost=/bin/rm -f $rs_dir/secrets $rs_dir/out" \
		flock -n $LOCK /bin/sh "$rs_dir/run.sh" exec "$rs_id" >/dev/null 2>&1; then
		csqtt_emit START failed "systemd-run refused the run"
		csqtt_end
		exit 4
	fi
	csqtt_emit START started "$rs_id"
	csqtt_end
}

cmd_tail() {
	rt_dir=$(run_dir "$1")
	[ -f "$rt_dir/log" ] || { printf 'CSQTT_TAIL|missing|0\n'; return 0; }
	# another login's run (its log is that login's own): said so, and the engine reads it through root
	[ -r "$rt_dir/log" ] || { printf 'CSQTT_TAIL|unreadable|0\n'; return 0; }
	rt_state=ended
	[ "$(systemctl is-active "sanity-csqtt-deploy-$1" 2>/dev/null)" = active ] && rt_state=active
	rt_size=$(wc -c <"$rt_dir/log" | tr -d ' ')
	printf 'CSQTT_TAIL|%s|%s\n' "$rt_state" "$rt_size"
	# a fixed size, read once: what the run writes meanwhile is the next tail's
	[ "$rt_size" -gt "$2" ] 2>/dev/null && tail -c +"$(($2 + 1))" "$rt_dir/log" | head -c "$((rt_size - $2))"
	return 0
}

cmd_cancel() {
	rc_dir=$(run_dir "$1")
	[ -d "$rc_dir" ] || { csqtt_emit CANCEL missing "$1"; csqtt_end; exit 2; }
	# a run that has ended has nothing to stop: say so, rather than leave a flag nobody reads (B7b review F3)
	if grep -q '^CSQTT_RESULT|' "$rc_dir/log" 2>/dev/null || [ "$(systemctl is-active "sanity-csqtt-deploy-$1" 2>/dev/null)" != active ]; then
		csqtt_emit CANCEL ended "$1"
		csqtt_end
		exit 0
	fi
	: >"$rc_dir/cancel"
	csqtt_emit CANCEL requested "$1"
	csqtt_end
}

cmd_status() {
	for st_dir in "$RUNS"/*/; do
		[ -f "$st_dir/log" ] || continue
		st_id=$(basename "$st_dir")
		st_active=0
		[ "$(systemctl is-active "sanity-csqtt-deploy-$st_id" 2>/dev/null)" = active ] && st_active=1
		st_result=$(cat "$st_dir/result" 2>/dev/null)
		[ -n "$st_result" ] || st_result=$(grep '^CSQTT_RESULT|' "$st_dir/log" 2>/dev/null | tail -n 1 | cut -d'|' -f2)
		csqtt_emit RUN "id=$st_id" "active=$st_active" "result=$st_result" "action=$(sed -n 's/^action=//p' "$st_dir/plan" 2>/dev/null)"
	done
	csqtt_end
}

# ── the run itself (in its transient unit) ──────────────────────────────────────────────────────────────────────────
RUN_ID='' RUN_DIR='' RUN_LOG='' RUN_PCT=0 RUN_STEP=start RUN_BEAT=''

plan() { sed -n "s/^$1=//p" "$RUN_DIR/plan" | head -n 1; }

# every secret's value, replaced in whatever a command printed before it reaches the log
run_redact() {
	if [ -s "$RUN_DIR/secrets" ]; then
		awk 'NR == FNR {i = index($0, "="); if (i && length($0) > i) s[++n] = substr($0, i + 1); next}
			{for (k = 1; k <= n; k++) {while ((p = index($0, s[k])) > 0) $0 = substr($0, 1, p - 1) "***" substr($0, p + length(s[k]))}; print}' \
			"$RUN_DIR/secrets" -
	else
		cat
	fi
}

run_emit() { csqtt_emit "$@" >>"$RUN_LOG"; }

run_phase() {  # run_phase <name> <percent>
	RUN_STEP=$1
	run_emit PHASE "$1"
	[ "$2" -gt "$RUN_PCT" ] && RUN_PCT=$2 && run_emit PCT "$RUN_PCT"
	return 0
}

# A heartbeat while a step waits, naming what for (and, for the package lock, who holds it): written by its own process
# straight to the log, so the end line counts the log's marker lines rather than this shell's.
run_beat_start() {
	run_beat_stop
	(while :; do
		sleep 5
		rb_what=$1
		case $1 in *lock*) rb_who=$(apt_lock_holder); [ -z "$rb_who" ] || rb_what="$1: $rb_who" ;; esac
		csqtt_emit BEAT "$rb_what" >>"$RUN_LOG"
	done) &
	RUN_BEAT=$!
}

run_beat_stop() {
	[ -z "$RUN_BEAT" ] || { kill "$RUN_BEAT" 2>/dev/null; wait "$RUN_BEAT" 2>/dev/null; RUN_BEAT=''; }
	return 0
}

apt_lock_holder() {
	for al_f in /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock; do
		[ -e "$al_f" ] || continue
		al_ino=$(stat -c %i "$al_f" 2>/dev/null) || continue
		al_pid=$(awk -v ino=":$al_ino" '$2 != "->" {n = length(ino); if (substr($6, length($6) - n + 1) == ino) {print $5; exit}}' /proc/locks)
		[ -n "$al_pid" ] && { echo "$(cat "/proc/$al_pid/comm" 2>/dev/null) ($al_pid)"; return 0; }
	done
}

# runs a step's command with its output in the log, redacted, and returns its status
run_cmd() {
	run_emit STEP "$RUN_STEP" "$*"
	rc_status=0
	# made 0600 first: the raw output may hold a secret until it is redacted (the command keeps its own umask)
	(umask 077 && : >"$RUN_DIR/out")
	"$@" >"$RUN_DIR/out" 2>&1 || rc_status=$?
	run_redact <"$RUN_DIR/out" | while IFS= read -r rc_line || [ -n "$rc_line" ]; do csqtt_emit OUT "$rc_line"; done >>"$RUN_LOG"
	rm -f "$RUN_DIR/out"
	return $rc_status
}

run_result() {  # run_result <kind> <cause> <next action>
	run_beat_stop
	run_emit RESULT "$1" "$RUN_STEP" "$2" "$3"
	# the kind alone (no secret) where every login reads it: the log is its starting login's own (B7 verify N2)
	printf '%s\n' "$1" >"$RUN_DIR/result" && chmod 644 "$RUN_DIR/result"
	rm -f "$RUN_DIR/secrets"
	printf 'CSQTT_END|%s\n' "$(grep -c '^CSQTT_' "$RUN_LOG")" >>"$RUN_LOG"
	exit 0
}

# A safe point: a cancel asked for is honoured here, with what this run made so far undone by the action's own cleanup.
run_safe_point() {
	[ -e "$RUN_DIR/cancel" ] || return 0
	run_beat_stop
	command -v action_cleanup >/dev/null 2>&1 && action_cleanup
	run_result failed-cleaned "cancelled" "nothing to do: the box is as before this run"
}

# The plan's facts, checked against the box before anything is touched: something may have bound a port or taken the
# interface since the inspection. The plan's own instance counts as expected, so a re-run after an interruption goes on.
run_recheck() {
	run_phase recheck 2
	rr_own=$(plan iface)
	for rr_port in $(plan ports); do
		rr_line=$(ss -H -ltunp "( sport = :$rr_port )" 2>/dev/null | head -n 1)
		[ -n "$rr_line" ] || continue
		# a socket with no process is the kernel's (a WireGuard or AmneziaWG interface's port)
		rr_holder=$(printf '%s\n' "$rr_line" | sed -n 's/.*users:(("\([^"]*\)".*/\1/p')
		# a re-run after an interruption finds its own server on the port: ours, so it goes on
		[ "$(plan resume)" = 1 ] && [ "$rr_holder" = csqtt-server ] && continue
		run_result failed-cleaned "port $rr_port is now held by ${rr_holder:-the kernel (a VPN interface)}" "check again: the app proposes another port"
	done
	rr_net=$(plan subnet)
	if [ -n "$rr_net" ] && [ "$(plan resume)" != 1 ]; then
		# every address and route on the box against the planned /24, read as inspect.sh reads them, which is all the planner
		# saw: a typed route (a swg-panel exit table's `prohibit 10.0.0.0/8`) bends only the traffic sent to its table (B7 verify N1)
		rr_hit=$( { ip -4 -o addr show 2>/dev/null | awk '{print $4}'
			ip -4 route show table all 2>/dev/null | awk '$1 != "default" && $1 != "broadcast" && $1 != "local" && $1 ~ /^[0-9]/ {print $1}'; } |
			awk -v net="$rr_net" 'function n(a, p) {split(a, p, "."); return ((p[1] * 256 + p[2]) * 256 + p[3]) * 256 + p[4]}
				BEGIN {split(net, w, "/"); s = n(w[1])}
				{split($0, c, "/"); l = (c[2] == "") ? 32 : c[2] + 0; m = (l < 24) ? l : 24; d = 1; for (i = m; i < 32; i++) d *= 2
				 if (int(s / d) == int(n(c[1]) / d)) {print $0; exit}}')
		[ -z "$rr_hit" ] || run_result failed-cleaned "$rr_net now overlaps $rr_hit" "check again: the app proposes another subnet"
	fi
	if [ -n "$rr_own" ] && ip link show "$rr_own" >/dev/null 2>&1 && [ "$(plan resume)" != 1 ]; then
		run_result failed-cleaned "interface $rr_own appeared" "check again"
	fi
	rr_unit=$(plan expect_unit)
	if [ -n "$rr_unit" ] && ! systemctl cat "$rr_unit" >/dev/null 2>&1; then
		run_result failed-cleaned "$rr_unit is gone" "check again"
	fi
	rr_sha=$(plan expect_sha) rr_bin=$(plan expect_bin)
	if [ -n "$rr_sha" ] && [ "$(sha256sum "$rr_bin" 2>/dev/null | cut -d' ' -f1)" != "$rr_sha" ]; then
		run_result failed-cleaned "$rr_bin changed since the check" "check again"
	fi
}

# selftest: the skeleton's own action (the zoo's cells for B7a): the re-check, a marker it creates and its cleanup removes,
# then `steps` safe points of `step_seconds` each, waiting with a heartbeat. The real actions are B8's onward.
action_selftest() {
	run_phase stage 10
	mkdir -p "$RUN_DIR/selftest" && : >"$RUN_DIR/selftest/made"
	action_cleanup() { rm -rf "$RUN_DIR/selftest"; }
	st_n=$(plan steps) st_s=$(plan step_seconds)
	st_i=1
	while [ "$st_i" -le "${st_n:-3}" ]; do
		run_safe_point
		run_phase "step $st_i" $((10 + st_i * 80 / ${st_n:-3}))
		run_beat_start "step $st_i of ${st_n:-3}"
		sleep "${st_s:-2}"
		run_beat_stop
		st_i=$((st_i + 1))
	done
	run_safe_point
	run_cmd sh -c 'echo "selftest done; a secret, if any, is $(cat '"$RUN_DIR"'/secrets 2>/dev/null | head -n 1 | cut -d= -f2)"'
	run_phase "done" 100
	run_result ok "" ""
}

cmd_exec() {
	RUN_ID=$1
	RUN_DIR=$(run_dir "$RUN_ID")
	RUN_LOG=$RUN_DIR/log
	trap 'run_beat_stop; rm -f "$RUN_DIR/secrets" "$RUN_DIR/out"' EXIT
	run_emit RUN "$RUN_ID" "$(plan action)"
	run_recheck
	case $(plan action) in
	selftest) action_selftest ;;
	*) run_result failed-cleaned "unknown action $(plan action)" "update the app" ;;
	esac
}

case ${1:-} in
start) cmd_start "${2:?run id}" "${3:?upload dir}" ;;
tail) cmd_tail "${2:?run id}" "${3:-0}" ;;
cancel) cmd_cancel "${2:?run id}" ;;
status) cmd_status ;;
exec) cmd_exec "${2:?run id}" ;;
*) csqtt_emit RUN error "unknown verb: ${1:-}"; csqtt_end; exit 2 ;;
esac
