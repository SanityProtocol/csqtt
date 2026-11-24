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
	rm -f "$RUNS"/*/secrets "$RUNS"/*/out "$RUNS"/*/core "$RUNS"/*/rootfs 2>/dev/null
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
	# a release the run installs (create, update): root's only, its sha checked again before use
	[ ! -f "$rs_up/core" ] || install -m 700 "$rs_up/core" "$rs_dir/core"
	# Docker mode's root filesystem, when the client brought it (P2 B13a): its sha checked against the plan before use
	[ ! -f "$rs_up/rootfs" ] || install -m 600 "$rs_up/rootfs" "$rs_dir/rootfs"
	: >"$rs_dir/log" && chown "$rs_uid" "$rs_dir/log" && chmod 600 "$rs_dir/log"
	# the last KEEP runs are kept (the newest by name: run ids start with their UTC time)
	ls -1 "$RUNS" 2>/dev/null | sort | awk -v keep=$KEEP '{a[NR] = $0} END {for (i = 1; i <= NR - keep; i++) print a[i]}' |
		while IFS= read -r rs_old; do
			[ "$rs_old" = "$rs_id" ] || rm -rf "${RUNS:?}/$rs_old"
		done
	if ! systemd-run --unit "sanity-csqtt-deploy-$rs_id" --collect --quiet --property=KillMode=mixed \
		--property="ExecStopPost=/bin/rm -f $rs_dir/secrets $rs_dir/out $rs_dir/core $rs_dir/rootfs" \
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
	if [ -e "$rc_dir/switching" ]; then
		csqtt_emit CANCEL refused "the switch has begun: the run finishes or undoes itself"
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
	run_emit STEP "$RUN_STEP" "$(printf '%s\n' "$*" | run_redact)"
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
	run_emit RESULT "$1" "$RUN_STEP" "$(printf '%s\n' "$2" | run_redact)" "$(printf '%s\n' "$3" | run_redact)"
	# the kind alone (no secret) where every login reads it: the log is its starting login's own (B7 verify N2)
	printf '%s\n' "$1" >"$RUN_DIR/result" && chmod 644 "$RUN_DIR/result"
	rm -f "$RUN_DIR/secrets" "$RUN_DIR/core" "$RUN_DIR/rootfs"
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
		# a move takes over the stock install's ports: its stock server holds them until the switch (P2 B12)
		case ",$(plan own_ports)," in *",$rr_port,"*) [ "$rr_holder" = csqtt ] || [ "$rr_holder" = csqtt25 ] && continue ;; esac
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
	rr_unit=$(plan expect_unit) rr_sha=$(plan expect_sha) rr_bin=$(plan expect_bin)
	# a move cut off after its switch holds the box through its marker, and its finish may have taken the stock unit and binary
	# already: the run settles it (mv_settle) before anything of the stock install counts (B12 review F2)
	if [ -n "$(plan move_role)" ] && [ "$(plan resume)" = 1 ] && [ -f "/var/lib/sanityprotocol/csqtt/$(plan id)/moved" ]; then
		rr_unit='' rr_sha=''
	fi
	if [ -n "$rr_unit" ] && ! systemctl cat "$rr_unit" >/dev/null 2>&1; then
		run_result failed-cleaned "$rr_unit is gone" "check again"
	fi
	# an update cut off before holds the binary until this run resolves it (action_update's up_recover, B10 review F2)
	[ "$(plan action)" != update ] || [ ! -f "/var/lib/sanityprotocol/csqtt/$(plan id)/update.pending" ] || rr_sha=''
	if [ -n "$rr_sha" ]; then
		rr_now=$(sha256sum "$rr_bin" 2>/dev/null | cut -d' ' -f1)
		# an update that landed before a cut (a boot cleared its record) finds its own target there: «Продолжить» goes on, as the
		# same build again (B10 verify of round 2, L1)
		[ "$rr_now" = "$rr_sha" ] || { [ "$(plan action)" = update ] && [ "$rr_now" = "$(plan core_sha)" ]; } ||
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

# ── create: a new instance of ours (P2-PLAN §1.5; P2 B8) ─────────────────────────────────────────────────────────────
# manifest.json is written first and names each object as it is made, so a failure or a cancel removes exactly those. Its
# four fields: schema, the instance id, the objects (kind and path, in the order made), the host changes (B9's).
mf_write() {
	{
		# run: the run that made the instance; a continuation may undo only what that run, cut off, left (B8 verify F8)
		printf '{"schema":1,"id":"%s","run":"%s","objects":[' "$MF_ID" "$RUN_ID"
		mf_sep=
		while read -r mf_kind mf_path; do
			[ -n "$mf_kind" ] || continue
			printf '%s{"kind":"%s","path":"%s"}' "$mf_sep" "$mf_kind" "$mf_path"
			mf_sep=,
		done <<EOF
$MF_OBJECTS
EOF
		printf '],"host":['
		mf_sep=
		while read -r mf_kind mf_rest; do
			[ -n "$mf_kind" ] || continue
			printf '%s{"kind":"%s"' "$mf_sep" "$mf_kind"
			for mf_kv in $mf_rest; do printf ',"%s":"%s"' "${mf_kv%%=*}" "${mf_kv#*=}"; done
			printf '}'
			mf_sep=,
		done <<EOF
$MF_HOST
EOF
		printf ']}\n'
	} >"$MF.new" && chmod 600 "$MF.new" && mv -f "$MF.new" "$MF"
}

mf_add() {  # mf_add <kind> <path>: named before it is made
	MF_OBJECTS="$MF_OBJECTS
$1 $2"
	[ ! -d "${MF%/*}" ] || mf_write
}

# ── host changes (APP-PLAN §6.9, P2-PLAN §1.6; P2 B9): each check-then-apply, named in the manifest's host list as made
# (kind key=value…, values without spaces), and undone, newest first, by a run that fails: only what this run added ─────
HOST_DROPIN=/etc/sysctl.d/99-sanity-csqtt.conf
# the floors 2.5.3's deploy set, raise-only (D8; for tcp_rmem/tcp_wmem the maximum), and forwarding, which the server also
# re-asserts at every start
HOST_FLOORS='net.ipv4.ip_forward 1
net.core.rmem_max 67108864
net.core.wmem_max 67108864
net.ipv4.tcp_rmem 67108864
net.ipv4.tcp_wmem 67108864
net.core.netdev_max_backlog 10000
net.core.somaxconn 65535
net.ipv4.tcp_mtu_probing 1'
# the server's own tuning (rust-server/tokio_io.rs KERNEL_PARAMETERS, --host-tuning auto), set by the run for an instance in
# Docker, whose server cannot write /proc/sys there: rp_filter loose (its TPROXY path needs it), fq and BBR, the TCP knobs
# (P2 B13a review F5)
HOST_TUNING='net.ipv4.conf.all.rp_filter 2
net.ipv4.conf.default.rp_filter 2
net.core.default_qdisc fq
net.ipv4.tcp_congestion_control bbr
net.ipv4.tcp_sack 1
net.ipv4.tcp_dsack 1
net.ipv4.tcp_reordering 15
net.ipv4.tcp_window_scaling 1
net.ipv4.tcp_fastopen 3
net.ipv4.tcp_slow_start_after_idle 0'

host_add() {  # host_add <kind> [key=value…]
	MF_HOST="$MF_HOST
$*"
	[ ! -d "${MF%/*}" ] || mf_write
}

# the firewall the openings go through: ufw or firewalld when active, else iptables when INPUT drops (its policy, or a
# last rule that drops everything), else none. A native nft table that drops input is not edited: the outside check names it
host_fw_mode() {
	if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | head -n 1 | grep -q 'Status: active'; then
		echo ufw
	elif [ "$(systemctl is-active firewalld 2>/dev/null)" = active ]; then
		echo firewalld
	elif iptables -w 5 -S INPUT 2>/dev/null | awk '$1 == "-P" && ($3 == "DROP" || $3 == "REJECT") {d = 1}
		$1 == "-A" && $3 == "-j" && ($4 == "DROP" || $4 == "REJECT") {d = 1} END {exit !d}'; then
		echo iptables
	else
		echo none
	fi
}

# waits for the package lock up to 20 minutes, the heartbeat naming its holder; false when it is still held
host_lock_wait() {
	[ -n "$(apt_lock_holder)" ] || return 0
	run_beat_start "the package lock"
	hl_i=0
	while [ -n "$(apt_lock_holder)" ]; do
		hl_i=$((hl_i + 1))
		[ $hl_i -le 240 ] || { run_beat_stop; return 1; }
		# a cancel is honoured while waiting: nothing is made yet (B9 review F2)
		run_safe_point
		sleep 5
	done
	run_beat_stop
}

# iptables, which the server's NAT needs (Alma ships without it): installed, never removed by an undo
host_iptables() {
	command -v iptables >/dev/null 2>&1 && return 0
	run_safe_point
	hi_step=$RUN_STEP
	run_phase packages 12
	if command -v apt-get >/dev/null 2>&1; then
		host_lock_wait || cr_fail "the package lock is held by $(apt_lock_holder)" "wait for it to finish, then try again"
		# a dpkg run cut off before leaves packages half-configured, and apt refuses until they are: once (DE-O4)
		[ -z "$(dpkg --audit 2>/dev/null)" ] || run_cmd env DEBIAN_FRONTEND=noninteractive dpkg --configure -a --force-confold </dev/null
		hi_apt="env DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends -o Dpkg::Options::=--force-confold iptables"
		run_beat_start "the package manager"
		# shellcheck disable=SC2086 # the words of ours
		run_cmd $hi_apt </dev/null || { run_cmd env DEBIAN_FRONTEND=noninteractive apt-get update -q </dev/null && run_cmd $hi_apt </dev/null; } ||
			{ run_beat_stop; cr_fail "iptables could not be installed" "check the box's package sources, then try again"; }
		run_beat_stop
	elif command -v dnf >/dev/null 2>&1; then
		# EL 9 and Fedora name it iptables-nft, EL 8 iptables (B9 review F3; 2.5.3's deploy tried both)
		run_beat_start "the package manager"
		run_cmd dnf install -y -q iptables-nft </dev/null || run_cmd dnf install -y -q iptables </dev/null ||
			{ run_beat_stop; cr_fail "iptables could not be installed" "check the box's package sources, then try again"; }
		run_beat_stop
	else
		cr_fail "iptables is not installed (the server's NAT needs it)" "install iptables and try again"
	fi
	host_add package name=iptables
	RUN_STEP=$hi_step
}

# the floors, each raised only when lower and not in our drop-in already (another instance of ours set it), with what it
# was kept beside it; sysctl.d takes no comment after a value, so each comment has a line of its own
host_sysctl() {  # host_sysctl [tuning]: the floors; with `tuning`, the server's own values too (an instance in Docker)
	hs_list=$HOST_FLOORS
	[ "${1:-}" != tuning ] || hs_list="$HOST_FLOORS
$HOST_TUNING"
	while read -r hs_key hs_floor; do
		[ -n "$hs_key" ] || continue
		grep -q "^$hs_key = " "$HOST_DROPIN" 2>/dev/null && continue
		hs_now=$(sysctl -n "$hs_key" 2>/dev/null | tr -s '\t ' '  ')
		[ -n "$hs_now" ] || continue
		case $hs_key in
		*.tcp_rmem | *.tcp_wmem)
			# shellcheck disable=SC2086 # three numbers
			set -- $hs_now
			[ $# = 3 ] && [ "$3" -lt "$hs_floor" ] 2>/dev/null || continue
			hs_set="$1 $2 $hs_floor"
			;;
		net.ipv4.ip_forward) hs_set=1 ;;
		# the server's own tuning (in Docker its container cannot write /proc/sys): set as the server would, not raised
		*.rp_filter | *.default_qdisc | *.tcp_congestion_control | *.tcp_sack | *.tcp_dsack | *.tcp_reordering | \
			*.tcp_window_scaling | *.tcp_fastopen | *.tcp_slow_start_after_idle)
			# (kept even where the box has it already: a running systemd instance's server may have set it, and may go before
			# a reboot: P2 B13a verify)
			hs_set=$hs_floor
			;;
		*)
			[ "$hs_now" -lt "$hs_floor" ] 2>/dev/null || continue
			hs_set=$hs_floor
			;;
		esac
		# writing ip_forward resets accept_redirects to !forwarding (the kernel's devinet): kept to put back beside it
		hs_redir=''
		[ "$hs_key" != net.ipv4.ip_forward ] || hs_redir="redirects=$(sysctl -n net.ipv4.conf.all.accept_redirects 2>/dev/null)"
		if [ "$hs_now" != "$hs_set" ]; then
			run_cmd sysctl -w "$hs_key=$hs_set"
			# procps answers 0 for a write the kernel refused (a container's read-only /proc/sys): read back (B9 review F6)
			if [ "$(sysctl -n "$hs_key" 2>/dev/null | tr -s '\t ' '  ')" != "$hs_set" ]; then
				[ "$hs_key" != net.ipv4.ip_forward ] || cr_fail "this box refuses forwarding (net.ipv4.ip_forward)" "ask the hoster to allow it"
				run_emit OUT "$hs_key stays $hs_now: the box refuses it; the server works with what it has"
				continue
			fi
		fi
		host_add sysctl "key=$hs_key" "was=$(printf '%s' "$hs_now" | tr ' ' ,)" "set=$(printf '%s' "$hs_set" | tr ' ' ,)" $hs_redir
		[ -f "$HOST_DROPIN" ] || printf '# SanityProtocol csqtt (APP-PLAN §6.9): forwarding and raise-only floors (and, for an instance in Docker, the server'"'"'s own tuning); each set from the value above it\n' >"$HOST_DROPIN"
		printf '# %s was %s\n%s = %s\n' "$hs_key" "$hs_now" "$hs_key" "$hs_set" >>"$HOST_DROPIN"
	done <<EOF
$hs_list
EOF
}

# the openings: UDP always, the panel's TCP unless it is local only; firewalld's interface in a zone of our own whose target
# stays default (no host port opens to VPN clients) with a forwarding policy out of it (§1.6). iptables' are the unit's own
host_firewall() {
	case $cr_fw in
	ufw)
		for hf_r in $cr_open; do
			ufw status 2>/dev/null | grep -qE "^${hf_r}[[:space:]]+ALLOW" && continue
			host_add ufw "rule=$hf_r"
			run_cmd ufw allow "$hf_r" comment "sqc-$cr_id" || cr_fail "ufw would not open $hf_r"
		done
		;;
	firewalld)
		hf_zone=$(firewall-cmd --get-default-zone 2>/dev/null)
		for hf_r in $cr_open; do
			firewall-cmd -q --permanent --zone="$hf_zone" --query-port="$hf_r" 2>/dev/null && continue
			host_add firewalld-port "zone=$hf_zone" "port=$hf_r"
			run_cmd firewall-cmd -q --permanent --zone="$hf_zone" --add-port="$hf_r" || cr_fail "firewalld would not open $hf_r"
		done
		host_add firewalld-zone "zone=sqc$cr_id"
		run_cmd firewall-cmd -q --permanent --new-zone="sqc$cr_id" &&
			run_cmd firewall-cmd -q --permanent --zone="sqc$cr_id" --add-interface="$cr_iface" ||
			cr_fail "firewalld would not take $cr_iface into a zone of its own"
		# its clients reach the instance's own ports through the tunnel too (its panel); every other host port stays closed
		# to them (M-122), which also closes the panel's «Локальный прокси» route on this box: named in the plan (B9 review F1)
		for hf_r in $cr_open; do
			run_cmd firewall-cmd -q --permanent --zone="sqc$cr_id" --add-port="$hf_r" || cr_fail "firewalld would not open $hf_r to $cr_iface"
		done
		host_add firewalld-policy "policy=sqc$cr_id-fwd"
		run_cmd firewall-cmd -q --permanent --new-policy="sqc$cr_id-fwd" ||
			cr_fail "this firewalld has no policies (0.9 or newer is needed)" "update firewalld, then try again"
		run_cmd firewall-cmd -q --permanent --policy="sqc$cr_id-fwd" --add-ingress-zone="sqc$cr_id" &&
			run_cmd firewall-cmd -q --permanent --policy="sqc$cr_id-fwd" --add-egress-zone=ANY &&
			run_cmd firewall-cmd -q --permanent --policy="sqc$cr_id-fwd" --set-target=ACCEPT ||
			cr_fail "firewalld would not let $cr_iface forward"
		# the reload drops firewalld's runtime-only rules (fail2ban's bans): named in the plan; an undo reloads only after it
		host_add firewalld-reload
		run_cmd firewall-cmd -q --reload || cr_fail "firewalld would not reload"
		;;
	esac
}

# this run's host changes undone, newest first; a package installed stays
host_undo() {
	hu_list=$(printf '%s\n' "$MF_HOST" | awk 'NF {l[++n] = $0} END {for (i = n; i > 0; i--) print l[i]}')
	hu_reload=0
	# Docker started or enabled by this run stays so while another instance of ours relies on it (this run's own container is
	# gone by now): decided once, before anything is stopped, by our containers alone (old ones of others' revived by our start
	# do not count; a docker call after the stop would start it again through its socket: P2 B13a verify N1)
	hu_docker_kept=0
	if printf '%s\n' "$hu_list" | grep -q '^service-'; then
		[ -z "$(docker ps -aq --filter name=^sanity-csqtt- 2>/dev/null)" ] || hu_docker_kept=1
	fi
	while read -r hu_kind hu_rest; do
		hu_key='' hu_was='' hu_rule='' hu_zone='' hu_port='' hu_policy='' hu_redir='' hu_unit=''
		for hu_kv in $hu_rest; do
			case $hu_kv in
			key=*) hu_key=${hu_kv#key=} ;; was=*) hu_was=${hu_kv#was=} ;; rule=*) hu_rule=${hu_kv#rule=} ;; redirects=*) hu_redir=${hu_kv#redirects=} ;;
			unit=*) hu_unit=${hu_kv#unit=} ;;
			zone=*) hu_zone=${hu_kv#zone=} ;; port=*) hu_port=${hu_kv#port=} ;; policy=*) hu_policy=${hu_kv#policy=} ;;
			esac
		done
		case $hu_kind in
		sysctl)
			sysctl -q -w "$hu_key=$(printf '%s' "$hu_was" | tr , ' ')" 2>/dev/null
			[ -z "$hu_redir" ] || sysctl -q -w "net.ipv4.conf.all.accept_redirects=$hu_redir" 2>/dev/null
			[ ! -f "$HOST_DROPIN" ] || sed -i "/^# $hu_key was /d; /^$hu_key = /d" "$HOST_DROPIN"
			grep -q '^[a-z]' "$HOST_DROPIN" 2>/dev/null || rm -f "$HOST_DROPIN"
			;;
		ufw) ufw delete allow "$hu_rule" >/dev/null 2>&1 ;;
		firewalld-port) firewall-cmd -q --permanent --zone="$hu_zone" --remove-port="$hu_port" 2>/dev/null ;;
		firewalld-zone) firewall-cmd -q --permanent --delete-zone="$hu_zone" 2>/dev/null ;;
		firewalld-policy) firewall-cmd -q --permanent --delete-policy="$hu_policy" 2>/dev/null ;;
		# this run reloaded: its permanent changes went live, so taking them back needs a reload too (B9 review F5)
		firewalld-reload) hu_reload=1 ;;
		# Docker enabled at boot or started by this run (P2 B13a): back as it was, unless another instance of ours made since
		# relies on it (review F3, verify N1)
		service-enabled) [ $hu_docker_kept = 1 ] || systemctl disable "$hu_unit" >/dev/null 2>&1 ;;
		service-started) [ $hu_docker_kept = 1 ] || systemctl stop "$hu_unit" >/dev/null 2>&1 ;;
		esac
	done <<EOF
$hu_list
EOF
	[ $hu_reload = 0 ] || firewall-cmd -q --reload 2>/dev/null
	return 0
}

# mf_objects_ours <id> (stdin: a manifest's objects, `kind path` lines): those that are the instance's own by their names: its two
# directories, its unit, its docker file, a move's drop-in on the stock unit, its container, our images. A cut-off run's undo
# («Продолжить», a move's settle) removes what the manifest it reads from the box names: a corrupt one must not reach a path
# outside our roots (P2-PLAN §2.4 «a corrupt manifest»; the operator's word of 2026-11-24, the guard)
mf_objects_ours() {
	while read -r mo_kind mo_path; do
		[ -n "$mo_kind" ] || continue
		case $mo_path in *..*) continue ;; esac
		case "$mo_kind $mo_path" in
		"dir /opt/sanityprotocol/csqtt/$1" | "dir /var/lib/sanityprotocol/csqtt/$1" | "unit /etc/systemd/system/sanity-csqtt-$1.service" | \
			"file /var/lib/sanityprotocol/csqtt/$1/docker" | "container sanity-csqtt-$1") ;;
		"file /etc/systemd/system/"*".service.d/sanity-move.conf" | "image sanity-csqtt:"* | "image sanity-csqtt-rootfs:"*) ;;
		*) continue ;;
		esac
		printf '%s %s\n' "$mo_kind" "$mo_path"
	done
	return 0
}

# removes what this run made, newest first: the unit before the files it runs, then any parent left empty
mf_undo() {
	mu_list=$(printf '%s\n' "$MF_OBJECTS" | awk 'NF {l[++n] = $0} END {for (i = n; i > 0; i--) print l[i]}')
	while read -r mu_kind mu_path; do
		case $mu_kind in
		unit)
			systemctl disable --now "${mu_path##*/}" >/dev/null 2>&1
			rm -f "$mu_path"
			systemctl daemon-reload
			systemctl reset-failed "${mu_path##*/}" >/dev/null 2>&1
			;;
		dir) rm -rf "$mu_path" ;;
		# Docker mode's (P2 B13a): the container, and an image Docker removes only while no container of anyone's uses it
		container) docker rm -f "$mu_path" >/dev/null 2>&1 ;;
		image) dk_image_rm "$mu_path" ;;
		# a single file this run wrote (the move's drop-in on the stock unit)
		file)
			rm -f "$mu_path"
			case $mu_path in /etc/systemd/system/*) rmdir "${mu_path%/*}" 2>/dev/null; systemctl daemon-reload ;; esac
			;;
		esac
	done <<EOF
$mu_list
EOF
	return 0
}

# the host changes a manifest names, as `kind key=value…` lines (our own format)
mf_host_of() {
	sed -n 's/.*"host":\[\(.*\)\]}$/\1/p' "$1" | sed 's/},{/}\n{/g' |
		sed -n 's/^{"kind":"\([^"]*\)"\(.*\)}$/\1\2/p' | sed 's/,"\([^"]*\)":"\([^"]*\)"/ \1=\2/g'
}

# The last lines a unit's start that failed printed: its own invocation's, read right after it (Restart=always begins the next
# one 3 s on), never what the build before printed as it stopped (B10 review F4). By the invocation id, not a cursor: before
# systemd 253, --after-cursor with a match skips the first entry that matches (Debian 12's 252 showed nothing). systemctl
# returns as the server exits, often before journald has read its last line from the stream: up to ~2 s are waited for one
inst_start_lines() {  # inst_start_lines <unit>
	isl_id=$(systemctl show -p InvocationID --value "$1" 2>/dev/null)
	[ -n "$isl_id" ] || return 0
	isl_n=0
	while :; do
		isl_l=$(journalctl _SYSTEMD_INVOCATION_ID="$isl_id" -n 3 -o cat --no-pager 2>/dev/null)
		[ -z "$isl_l" ] && [ "$isl_n" -lt 10 ] || break
		isl_n=$((isl_n + 1))
		sleep 0.2
	done
	printf '%s\n' "$isl_l" | run_redact | tr '\n' ' '
}

# An instance of ours serves (create's and update's verify): its unit active, its UDP and web ports held by its own pid alone
# (SO_REUSEADDR makes a bind prove nothing), its interface up, its panel answering on 127.0.0.1 where curl is. Prints why
# not, or nothing.
inst_verify() {  # inst_verify <unit, or docker:<container>> <udp> <web> <iface> <secret path>
	iv_unit=$1 iv_udp=$2 iv_web=$3 iv_iface=$4 iv_secret=$5
	# (a container's server binds its ports after `docker run` returned: it is given 20 s; a unit's start returned on its
	# READY=1, sent once they are bound)
	iv_patience=0
	case $iv_unit in
	docker:*)
		iv_c=${iv_unit#docker:} iv_patience=20
		[ "$(docker inspect -f '{{.State.Running}}' "$iv_c" 2>/dev/null)" = true ] || { echo "its container $iv_c is not running"; return 0; }
		iv_pid=$(docker inspect -f '{{.State.Pid}}' "$iv_c" 2>/dev/null)
		;;
	*)
		[ "$(systemctl is-active "$iv_unit")" = active ] || { echo "$iv_unit is not active"; return 0; }
		iv_pid=$(systemctl show -p MainPID --value "$iv_unit")
		;;
	esac
	for iv_check in "u $iv_udp UDP" "t $iv_web TCP"; do
		iv_p=${iv_check%% *} iv_rest=${iv_check#* }
		iv_port=${iv_rest%% *} iv_name=${iv_rest#* }
		iv_waited=0
		while :; do
			iv_holders=$(ss -H -l"$iv_p"np "( sport = :$iv_port )" 2>/dev/null | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | sort -u | tr '\n' ' ')
			[ "$iv_holders" != "$iv_pid " ] || break
			[ $iv_waited -lt $iv_patience ] || { echo "$iv_name $iv_port is held by pids ${iv_holders:-none} (ours: $iv_pid)"; return 0; }
			sleep 1
			iv_waited=$((iv_waited + 1))
		done
	done
	ip link show "$iv_iface" >/dev/null 2>&1 || { echo "the server made no $iv_iface"; return 0; }
	command -v curl >/dev/null 2>&1 || return 0
	# the secret path only on curl's stdin, never in a process's arguments
	iv_try=0
	until printf 'url = "https://127.0.0.1:%s/%s/"\n' "$iv_web" "$iv_secret" |
		curl -sk -K - -o /dev/null -m 3 -w '%{http_code}' 2>/dev/null | grep -qE '^(200|301|302|401)$'; do
		iv_try=$((iv_try + 1))
		[ $iv_try -lt 10 ] || { echo "the panel does not answer on 127.0.0.1:$iv_web"; return 0; }
		sleep 1
	done
}

# the objects a manifest names, as `kind path` lines (our own format: one object per {…})
mf_objects_of() {
	sed -n 's/.*"objects":\[\(.*\)\],"host".*/\1/p' "$1" | sed 's/},{/}\n{/g' |
		sed -n 's/.*"kind":"\([^"]*\)","path":"\([^"]*\)".*/\1 \2/p'
}

# The server's own footprint once it has run (B8 review F4): if it died rather than stopped, its rules tagged with its instance
# (SQC-<ID>_…, every table, v4 and v6) and its forward chain stay; and the host-wide values it sets at every start are put
# back as they were before the switch, unless another csqtt server still runs and needs them
cr_server_undo() {
	inst_sweep "$cr_id"
	# iptables-nft leaves the tables the server's rules made, empty: a table new since the switch and holding no rule goes
	if [ -f "$RUN_DIR/nft-before" ] && command -v nft >/dev/null 2>&1; then
		nft list tables 2>/dev/null | while read -r cs_w cs_fam cs_name; do
			[ "$cs_w" = table ] || continue
			grep -qx "table $cs_fam $cs_name" "$RUN_DIR/nft-before" && continue
			nft list table "$cs_fam" "$cs_name" 2>/dev/null | awk '!/^[ \t]*((table|chain) .*\{|type .*;|\}|)$/ {n++} END {exit n > 0}' &&
				nft delete table "$cs_fam" "$cs_name" 2>/dev/null
		done
	fi
	if [ -f "$RUN_DIR/sysctl-before" ] && ! pgrep -x csqtt-server >/dev/null 2>&1 && ! pgrep -x csqtt >/dev/null 2>&1 &&
		! pgrep -x csqtt25 >/dev/null 2>&1; then
		while IFS='=' read -r cs_key cs_val; do
			[ -z "$cs_key" ] || sysctl -q -w "$cs_key=$cs_val" 2>/dev/null
		done <"$RUN_DIR/sysctl-before"
		# bbr's module, loaded by the server's congestion control: unloaded when it was not loaded before the switch
		grep -qx tcp_bbr "$RUN_DIR/modules-before" 2>/dev/null || modprobe -r tcp_bbr 2>/dev/null
	fi
	return 0
}

# An instance's own iptables objects, by its tag (SQC-<ID>_…, every table, v4 and v6): what its server leaves when it dies
# rather than stops; bounded by the id, never by a manifest (B8 review F4, B11)
inst_sweep() {  # inst_sweep <id>
	is_tag=SQC-$(printf '%s' "$1" | tr 'a-f' 'A-F')_
	for is_ipt in iptables ip6tables; do
		command -v $is_ipt >/dev/null 2>&1 || continue
		for is_t in filter nat mangle raw; do
			$is_ipt -w 5 -t $is_t -S 2>/dev/null | grep '^-A ' | grep -F -- "$is_tag" | sed 's/^-A /-D /' | while read -r is_rule; do
				eval "$is_ipt -w 5 -t $is_t $is_rule" 2>/dev/null
			done
			$is_ipt -w 5 -t $is_t -S 2>/dev/null | awk -v t="$is_tag" '$1 == "-N" && index($2, t) == 1 {print $2}' | while read -r is_c; do
				$is_ipt -w 5 -t $is_t -F "$is_c" 2>/dev/null
				$is_ipt -w 5 -t $is_t -X "$is_c" 2>/dev/null
			done
		done
	done
	return 0
}

# On a plain iptables box whose INPUT drops, an instance's openings are its unit's own: made at each start (a reboot, a
# restore of the saved rules), taken at each stop, tagged SQC-<ID>_OPEN (§1.5). Writes <state>/open.sh and prints the unit
# lines that run it.
inst_open_sh() {  # inst_open_sh <id> <state dir> <openings: port/proto…>
	io_tag=SQC-$(printf '%s' "$1" | tr 'a-f' 'A-F')_OPEN
	{
		printf '#!/bin/sh\n# SanityProtocol csqtt: sqc-%s'"'"'s openings in INPUT, run by its unit (P2 B9)\n' "$1"
		printf 'for r in'
		for io_r in $3; do printf ' "-p %s --dport %s"' "${io_r#*/}" "${io_r%/*}"; done
		printf '; do\n'
		# shellcheck disable=SC2016 # the generated script's own variables
		printf '\tif [ "${1:-add}" = add ]; then\n\t\tiptables -w 5 -C INPUT $r -m comment --comment %s -j ACCEPT 2>/dev/null ||\n' "$io_tag"
		# shellcheck disable=SC2016
		printf '\t\t\tiptables -w 5 -I INPUT $r -m comment --comment %s -j ACCEPT\n\telse\n' "$io_tag"
		# shellcheck disable=SC2016
		printf '\t\tiptables -w 5 -D INPUT $r -m comment --comment %s -j ACCEPT 2>/dev/null\n\tfi\ndone\nexit 0\n' "$io_tag"
	} >"$2/open.sh" && chmod 700 "$2/open.sh" || return 1
	printf 'ExecStartPre=-/bin/sh %s/open.sh add\nExecStopPost=-/bin/sh %s/open.sh del\n' "$2" "$2"
}

# an instance's unit
inst_unit_text() {  # inst_unit_text <id> <root> <state> <flags> <open lines> [a [Unit] line: a move's marker condition]
	printf '[Unit]\nDescription=csqtt server sqc-%s (SanityProtocol)\nWants=network-online.target\nAfter=network-online.target\n' "$1"
	[ -z "${6:-}" ] || printf '%s\n' "$6"
	cat <<EOF

[Service]
# no CSQTT_SERVICE_MANAGER: the panel's «Перезапустить» then ends the server, and Restart=always starts it again (the
# systemd route would restart a unit named after the instance, sqc-$1.service, which is not this one)
Type=notify
NotifyAccess=main
WatchdogSec=30
EnvironmentFile=$3/env
ExecStart=$2/current/csqtt-server $4
# an update's record goes the first time a start outside an update run is ready: after a boot the build it went to serves,
# its store is live from then on, and no later run may put the copy from before it back over that (B10 verify N1); while an
# update run lives, its marker in /run keeps the record, which the run settles itself (B10 verify of round 2, V1)
ExecStartPost=-/bin/sh -c '[ -e /run/sanity-csqtt-$1.updating ] || exec rm -f $3/update.pending'
$5
Restart=always
RestartSec=3
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
}

# the host-wide values the server sets at every start (rust-server tokio_io.rs KERNEL_PARAMETERS), saved before the switch
# (accept_redirects follows forwarding: the kernel resets it to !forwarding whenever ip_forward is written)
CR_SERVER_SYSCTLS='net.ipv4.ip_forward net.ipv4.conf.all.accept_redirects net.ipv4.conf.all.rp_filter net.ipv4.conf.default.rp_filter net.core.default_qdisc
net.ipv4.tcp_congestion_control net.ipv4.tcp_sack net.ipv4.tcp_dsack net.ipv4.tcp_reordering net.ipv4.tcp_window_scaling
net.ipv4.tcp_fastopen net.ipv4.tcp_slow_start_after_idle'

# an env value as systemd's EnvironmentFile reads it: a surrounding pair of double quotes taken off and \" \\ unescaped (the
# stock apps' systemd mode quotes their env so); a bare value as it stands
env_unquote() { sed '/^".*"$/ { s/^"//; s/"$//; s/\\\(["\\]\)/\1/g; }'; }

# inst_env <state dir> <key>: an instance's env value as systemd reads it (a moved instance's carries the stock app's lines,
# quoted: every reader goes through here, B12 review F1)
inst_env() { sed -n "s/^$2=//p" "$1/env" 2>/dev/null | head -n 1 | env_unquote; }

# ── the move (P2 B12, P2-PLAN §1.7): create's run taking a stock install's place through one marker. Our unit carries
# ConditionPathExists=<state>/moved and a drop-in on the stock unit its negation: renaming the marker into place moves the
# box from theirs to ours, and a reboot at any instant starts exactly one of the two ──────────────────────────────────────
# what is copied from the stock config dir once its server is stopped (consistent then): the store, its certificate (the
# panel's pin stays), a one-shot overrides file it had not yet consumed (B6)
MV_STORE='csqtt.db csqtt.db-wal csqtt.db-shm passwords.json passwords.json.imported web_cert.pem web_key.pem deploy-overrides.json'
# A stock container's restart policy and whether it ran, as the first move that touched it found them (P2 B13b): its `no` and
# its stop outlive a reboot by themselves, so this record does too: outside the state dir (an undo removes that first), synced
# before the container is touched, never written over while it stands, read by every later run that puts the container back
# and removed only once it is back, or gone (B13b review F2)
MV_STOCK=/var/lib/sanityprotocol/stock-

mv_line() {  # mv_line <key> <fallback>: the stock env's line for <key>, its value as it stands (quoted or not), or <fallback>
	ml_v=$(sed -n "s/^\(export \)\{0,1\}$1=//p" "$cr_mv_store/csqtt.env" 2>/dev/null | head -n 1)
	[ -n "$ml_v" ] || ml_v=$2
	[ -z "$ml_v" ] || printf '%s=%s\n' "$1" "$ml_v"
}

mv_secret() { inst_env "$cr_state" CSQTT_WEB_SECRET_PATH; }

# the move's end, in the plan's order: our unit without its condition (a marker gone later must never skip it at a boot), the
# stock install's objects through the foreign removal (its store kept: DE-T3), its drop-in, the marker last
mv_finish() {
	run_phase finish 90
	# (in Docker no unit carries the marker's condition: its container runs with its restart policy, P2 B13b)
	if [ $cr_docker = 0 ]; then
		sed '/^ConditionPathExists=/d' "/etc/systemd/system/$cr_unit" >"/etc/systemd/system/$cr_unit.new" &&
			mv -f "/etc/systemd/system/$cr_unit.new" "/etc/systemd/system/$cr_unit" ||
			run_result failed-not-rolled-back "cannot write $cr_unit without its marker condition" "«Починить»: it serves while the marker stays"
	fi
	systemctl daemon-reload
	for cr_u in $(plan move_units | tr , ' '); do fr_stop_unit "$cr_u"; done
	fr_take move_ 0
	if [ -n "$cr_mv_unit" ]; then
		rm -f "/etc/systemd/system/$cr_mv_unit.d/sanity-move.conf"
		rmdir "/etc/systemd/system/$cr_mv_unit.d" 2>/dev/null
		MF_OBJECTS=$(printf '%s\n' "$MF_OBJECTS" | grep -v "^file /etc/systemd/system/$cr_mv_unit.d/sanity-move.conf\$")
	fi
	# the stock store stays (DE-T3), marked as moved: its leftovers get «Удалить остатки» alone, never a second move onto this
	# instance's ports (B12 review F5)
	[ ! -d "$cr_mv_store" ] || printf 'sqc-%s\n' "$cr_id" >"$cr_mv_store/.sanity-moved"
	systemctl daemon-reload
	mf_write
	rm -f "$cr_state/stock-ran" "$cr_state/moved"
}

# mv_container_back: a stock Docker install's container as its record says the first move found it: its restart policy back,
# started again if it ran; the record goes only once both are done, so a run cut before that leaves it for the next (P2 B13b,
# review F2). Nothing without a record: nothing of the container was touched
mv_container_back() {
	[ -n "$cr_mv_ctr" ] && [ -s "$MV_STOCK$cr_mv_ctr" ] || return 0
	mc_pol=$(sed -n 1p "$MV_STOCK$cr_mv_ctr") mc_ran=$(sed -n 2p "$MV_STOCK$cr_mv_ctr")
	case $mc_pol in no | always | unless-stopped | on-failure | on-failure:[0-9]*) ;; *) return 0 ;; esac
	run_cmd docker update --restart="$mc_pol" "$cr_mv_ctr" || return 0
	[ "$mc_ran" != 1 ] || run_cmd docker start "$cr_mv_ctr" || return 0
	rm -f "$MV_STOCK$cr_mv_ctr"
	sync
	return 0
}

# A move cut off after its switch (a reboot, a kill): the marker holds ours as the box's server. Serving, the move is finished;
# not, the box goes back to the stock install, which was never changed, and ours stays as a kept store «Восстановить» knows:
# it may have served its clients since, so nothing of it is deleted
mv_settle() {
	run_phase recover 8
	: >"$RUN_DIR/switching"
	# (the stock server is started again only if it ran when the move stopped it: B12 review F4)
	cr_mv_ran=0
	[ ! -f "$cr_state/stock-ran" ] || cr_mv_ran=1
	# (what the cut run's manifest names, only as far as it is the instance's own: the guard)
	MF_OBJECTS=$(mf_objects_of "$MF" | mf_objects_ours "$cr_id")
	MF_HOST=$(mf_host_of "$MF" | in_unit=$cr_unit in_state=$cr_state inst_ours_only "$cr_id")
	# ours as the cut run made it: its unit, or in Docker its container, made from its docker file when the cut came first (P2 B13b)
	cr_docker=0 ms_check=$cr_unit
	if [ -f "$cr_state/docker" ]; then
		cr_docker=1 ms_ctr=$(sed -n 's/^# docker //p' "$cr_state/docker")
		ms_check=docker:$ms_ctr
		if [ "$(docker inspect -f '{{.State.Running}}' "$ms_ctr" 2>/dev/null)" != true ]; then
			if docker container inspect "$ms_ctr" >/dev/null 2>&1; then
				run_cmd docker start "$ms_ctr"
			else
				ms_fw=none
				[ ! -f "$cr_state/open.sh" ] || ms_fw=iptables
				printf '%s\n' "$MF_OBJECTS" | grep -qxF "container $ms_ctr" || mf_add container "$ms_ctr"
				# shellcheck disable=SC2046 # the instance's own flags
				run_cmd dk_run "$ms_ctr" "$(sed -n 's/^# image //p' "$cr_state/docker")" "$cr_state" "$ms_fw" \
					$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "$cr_state/docker" | head -n 1)
			fi
		fi
	else
		systemctl is-active --quiet "$cr_unit" || run_cmd systemctl start "$cr_unit"
	fi
	cr_why=$(inst_verify "$ms_check" "$cr_udp" "$cr_web" "$cr_iface" "$(mv_secret)")
	if [ -z "$cr_why" ]; then
		# (a kept store's lines a settle cut off before had written: ours serves, so it is live)
		rm -f "$cr_state/unit.saved"
		mv_finish
		run_phase "done" 100
		run_result ok "" "the move cut off before is finished: read the connection back"
	fi
	if [ $cr_docker = 1 ]; then
		# (kept as a Docker removal keeps it: the docker file's lines in unit.saved, the file itself gone, P2 B13b)
		docker stop -t 15 "$ms_ctr" >/dev/null 2>&1
		{ printf '# release %s\n' "$(sed -n 's/^# image sanity-csqtt://p' "$cr_state/docker")"; cat "$cr_state/docker"; } >"$cr_state/unit.saved.new"
	else
		systemctl stop "$cr_unit" 2>/dev/null
		{ printf '# release %s\n' "$(basename "$(readlink "$cr_root/current" 2>/dev/null)" 2>/dev/null)"
			sed '/^ConditionPathExists=/d' "/etc/systemd/system/$cr_unit"; } >"$cr_state/unit.saved.new"
	fi
	mv -f "$cr_state/unit.saved.new" "$cr_state/unit.saved"
	# the marker before the docker file: a settle cut off from here leaves a kept store, never a second settle that reads ours as
	# a unit and writes its lines over unit.saved (B13b review F2)
	rm -f "$cr_state/moved"
	sync
	rm -f "$cr_state/docker" "$cr_state/stock-ran"
	MF_OBJECTS=$(printf '%s\n' "$MF_OBJECTS" | grep -v "^dir $cr_state\$")
	mf_undo
	inst_sweep "$cr_id"
	MF_HOST=$(printf '%s\n' "$MF_HOST" | grep -v '^sysctl ')
	host_undo
	MF_OBJECTS="dir $cr_state" MF_HOST=''
	mf_write
	systemctl daemon-reload
	[ -z "$cr_mv_unit" ] || [ $cr_mv_ran = 0 ] || run_cmd systemctl start "$cr_mv_unit"
	mv_container_back
	run_result failed-rolled-back "the move cut off before does not serve: $cr_why" \
		"the stock install is back as it was; the new instance's store is kept («Восстановить» brings it back)"
}

# ── Docker mode (P2 B13a; APP-PLAN §6.11; P2-PLAN §1.8): our image made on the box from a pinned root filesystem and our
# core, never pulled; the container on the host's network with NET_ADMIN and the TUN, never privileged ──────────────────────
# The image's entrypoint: the host's iptables backend (SANITY_IPTABLES: legacy or nft; Alpine defaults to nft, which on a
# legacy host puts rules where the kernel's path does not look), the instance's INPUT openings on a box whose INPUT is closed
# (SANITY_OPEN, its open.sh, at every start as its unit would run it), then the server in place of this shell
dk_entrypoint() {
	cat <<'EOF'
#!/bin/sh
case ${SANITY_IPTABLES:-nft} in legacy) m=xtables-legacy-multi ;; *) m=xtables-nft-multi ;; esac
for t in iptables iptables-save iptables-restore ip6tables ip6tables-save ip6tables-restore; do ln -sf "$m" "/usr/sbin/$t"; done
[ -z "${SANITY_OPEN:-}" ] || sh "$SANITY_OPEN" add
# an update's record from an earlier boot goes once this server holds its port, seen twice 5 s apart, as a unit's ExecStartPost
# drops it after READY=1; a record of this boot is its run's own, and a build that never serves leaves it for the next run's
# rollback (P2 B13c verify N1)
d='' u='' w=''
for a in "$@"; do
	case $w in --config-dir) d=$a ;; --listen) u=${a##*:} ;; esac
	w=$a
done
r=$d/update.pending b=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
if [ -n "$d" ] && [ -n "$u" ] && [ -f "$r" ]; then
	(
		h=0 n=0
		while [ $n -lt 120 ] && [ -f "$r" ] && [ "$(sed -n 's/^boot=//p' "$r")" != "$b" ]; do
			sleep 5
			n=$((n + 1))
			if netstat -lnup 2>/dev/null | grep -q ":$u .*/csqtt-server"; then h=$((h + 1)); else h=0; fi
			[ $h -lt 2 ] || { rm -f "$r"; break; }
		done
	) </dev/null >/dev/null 2>&1 &
fi
exec "$@"
EOF
}

# the host's own iptables backend, as its iptables names itself (the one Docker's own rules went to)
dk_backend() { case $(iptables -V 2>/dev/null) in *legacy*) echo legacy ;; *) echo nft ;; esac; }

# dk_image_rm <tag>: one of our images, gone when nothing needs it: an instance's image while no container uses it (Docker
# refuses then: its own count), the root filesystem's while no image of ours is built from it (Docker would untag a parent
# all the same: the zoo, docker.io 24.0.7)
dk_image_rm() {
	case $1 in
	sanity-csqtt-rootfs:*)
		# (built on it: its one layer is their first; another root filesystem's images, after an update to a newer pin, do not
		# keep it: P2 B13c)
		di_layer=$(docker image inspect -f '{{index .RootFS.Layers 0}}' "$1" 2>/dev/null) || return 0
		for di_i in $(docker images -q sanity-csqtt 2>/dev/null); do
			[ "$(docker image inspect -f '{{index .RootFS.Layers 0}}' "$di_i" 2>/dev/null)" != "$di_layer" ] || return 0
		done
		docker image rm "$1" >/dev/null 2>&1
		;;
	sanity-csqtt:*) docker image rm "$1" >/dev/null 2>&1 ;;
	esac
	return 0
}

# every root filesystem image of ours through dk_image_rm: each goes once no image of ours stands on it (after an update
# across a pin change the older one is named nowhere any more: B13c review F1)
dk_rootfs_sweep() {
	for dr_t in $(docker images --format '{{.Repository}}:{{.Tag}}' sanity-csqtt-rootfs 2>/dev/null); do dk_image_rm "$dr_t"; done
	return 0
}

# the last lines a container wrote, for a result's cause
dk_lines() { docker logs --tail 5 "$1" 2>&1 | tr '\n' ' ' | cut -c1-300; }

# dk_daemon: Docker running now and enabled at boot, so its restart policy brings our container back after a reboot (§6.11
# rule 6); each, when it was not so, is this run's host change: undone if the run fails, kept by a removal (others may rely
# on it by then)
dk_daemon() {
	if ! systemctl is-enabled --quiet docker.service 2>/dev/null; then
		host_add service-enabled unit=docker.service
		run_cmd systemctl enable docker.service || cr_fail "Docker could not be enabled at boot"
	fi
	if ! systemctl is-active --quiet docker.service; then
		host_add service-started unit=docker.service
		run_cmd systemctl start docker.service || cr_fail "Docker did not start: $(inst_start_lines docker.service)"
	fi
	docker info >/dev/null 2>&1 || cr_fail "Docker does not answer" "check Docker on the box"
}

# dk_rootfs <sha256> <url>: the root filesystem image, sanity-csqtt-rootfs:<sha8>, as Docker has it, or imported from the
# client's upload or from <url> (the release's), its sha256 checked first (C3)
dk_rootfs() {
	dr_tag=sanity-csqtt-rootfs:$(printf '%.8s' "$1")
	docker image inspect "$dr_tag" >/dev/null 2>&1 && return 0
	if [ ! -f "$RUN_DIR/rootfs" ]; then
		[ -n "$2" ] || cr_fail "the root filesystem is not on the box, and none was uploaded or is to be fetched" "check again: the app then brings it"
		if command -v curl >/dev/null 2>&1; then
			run_cmd curl -fsSL --retry 2 --connect-timeout 15 --max-time 300 -o "$RUN_DIR/rootfs" "$2"
		else
			run_cmd wget -q -T 30 -O "$RUN_DIR/rootfs" "$2"
		fi || cr_fail "cannot fetch the root filesystem from $2" "check again: the app then brings it itself"
	fi
	[ "$(sha256sum "$RUN_DIR/rootfs" | cut -d' ' -f1)" = "$1" ] || cr_fail "the root filesystem is damaged" "try again"
	mf_add image "$dr_tag"
	run_cmd docker import "$RUN_DIR/rootfs" "$dr_tag" || cr_fail "Docker would not import the root filesystem"
	rm -f "$RUN_DIR/rootfs"
}

# dk_image <rootfs tag> <image tag> <core>: our core and the entrypoint on the root filesystem, built with no network (no
# registry, no mirror: §6.11 rule 1), as Docker has it or made now. Both live under /opt/sanityprotocol in the image (P2-PLAN
# §1.5): an old app's cleanup removes any container whose command holds /usr/local/bin/csqtt (2.1.9's deploy.sh)
dk_image() {
	docker image inspect "$2" >/dev/null 2>&1 && return 0
	di_ctx=$RUN_DIR/image
	{ mkdir -p "$di_ctx" && install -m 755 "$3" "$di_ctx/csqtt-server" && dk_entrypoint >"$di_ctx/sanity-entrypoint" &&
		chmod 755 "$di_ctx/sanity-entrypoint" &&
		printf 'FROM %s\nCOPY csqtt-server /opt/sanityprotocol/csqtt-server\nCOPY sanity-entrypoint /opt/sanityprotocol/entrypoint\nENTRYPOINT ["/opt/sanityprotocol/entrypoint"]\n' \
			"$1" >"$di_ctx/Dockerfile"; } || cr_fail "cannot lay out the image's files"
	mf_add image "$2"
	run_cmd docker build -q --network none -t "$2" "$di_ctx" || { rm -rf "$di_ctx"; cr_fail "Docker would not build the image"; }
	rm -rf "$di_ctx"
}

# dk_run <container> <image> <state dir> <firewall mode> <flags…>: the instance's container, started. Its restart policy
# brings it back with Docker after a reboot or a crash (M-2: the server's own watchdog exits a stuck process); its logs
# rotate (Docker's local driver); its store and env are the state dir, mounted at its own path
dk_run() {
	dn_c=$1 dn_img=$2 dn_state=$3 dn_open=''
	[ "$4" != iptables ] || dn_open=$3/open.sh
	shift 4
	docker run -d --name "$dn_c" --restart unless-stopped --network host --cap-add NET_ADMIN --device /dev/net/tun \
		--log-driver local --env-file "$dn_state/env" -e "SANITY_IPTABLES=$(dk_backend)" -e "SANITY_OPEN=$dn_open" \
		-v "$dn_state:$dn_state" --label "net.sanityprotocol.csqtt.instance=$dn_c" "$dn_img" /opt/sanityprotocol/csqtt-server "$@"
}

action_create() {
	cr_id=$(plan id) cr_iface=$(plan iface) cr_subnet=$(plan subnet) cr_udp=$(plan udp) cr_web=$(plan web)
	cr_slot=$(plan slot) cr_bind=$(plan web_bind) cr_version=$(plan version) cr_sha=$(plan core_sha)
	case $cr_id in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) run_result failed-cleaned "bad instance id $cr_id" "update the app" ;; esac
	cr_root=/opt/sanityprotocol/csqtt/$cr_id cr_state=/var/lib/sanityprotocol/csqtt/$cr_id
	cr_unit=sanity-csqtt-$cr_id.service
	cr_release=$cr_root/releases/$cr_version-$(printf '%.8s' "$cr_sha")
	MF=$cr_state/manifest.json MF_ID=$cr_id MF_OBJECTS='' MF_HOST=
	# the parents this run makes are its own, never the manifest's (another instance may come to share them): removed when
	# the run is undone and they are empty
	cr_parents=
	for cr_d in /opt/sanityprotocol /opt/sanityprotocol/csqtt /var/lib/sanityprotocol/csqtt; do
		[ -e "$cr_d" ] || cr_parents="$cr_d $cr_parents"
	done
	# a move (P2 B12, §1.7): the stock install this instance takes the place of, its role, its server unit (none when its unit
	# is gone, B2) and its config dir; all empty for a plain create
	cr_mv_role=$(plan move_role) cr_mv_unit=$(plan move_unit) cr_mv_store=$(plan move_store) cr_mv_ctr=$(plan move_container)
	cr_move=0 cr_mv_stopped=0 cr_mv_ran=0
	[ -z "$cr_mv_role" ] || cr_move=1
	# Docker mode (P2 B13a): the instance a container, sanity-csqtt-<id>, its image ours; a move into it is B13b's
	cr_docker=0 cr_ctr=sanity-csqtt-$cr_id
	[ "$(plan mode)" != docker ] || cr_docker=1
	action_cleanup() {
		# a move undone: its marker goes first, so a reboot from here on starts the stock install alone (B12 review F3)
		if [ $cr_move = 1 ] && [ -f "$cr_state/moved" ]; then
			rm -f "$cr_state/moved"
			sync
		fi
		mf_undo
		# the server's own footprint back to before the switch, then our host changes back to before this run
		[ ! -e "$RUN_DIR/switching" ] || cr_server_undo
		host_undo
		for cr_d in $cr_parents; do rmdir "$cr_d" 2>/dev/null; done
		# a move undone: the stock install as it was, never changed (its drop-in went with the objects above), its server
		# started again if it ran when the move stopped it (B12 review F4)
		if [ "$cr_mv_stopped" = 1 ] && [ -n "$cr_mv_unit" ] && [ $cr_mv_ran = 1 ]; then
			systemctl daemon-reload
			run_cmd systemctl start "$cr_mv_unit"
		fi
		# (a stock container as its record says, whichever run wrote it: one an earlier move left at `no` included)
		[ $cr_move = 0 ] || mv_container_back
		return 0
	}
	cr_fail() { action_cleanup; run_result failed-cleaned "$1" "${2:-check again}"; }
	cr_flags="--listen 0.0.0.0:$cr_udp --web-port $cr_web --config-dir $cr_state --instance sqc-$cr_id --iface $cr_iface"
	cr_flags="$cr_flags --tun-addr ${cr_subnet%.0/24}.1/24 --instance-slot $cr_slot"
	[ -z "$cr_bind" ] || cr_flags="$cr_flags --web-bind $cr_bind"
	# (in a container /proc/sys is read-only: the host's values are this run's own host phase, as for a unit)
	[ $cr_docker = 0 ] || cr_flags="$cr_flags --host-tuning off"
	cr_open="$cr_udp/udp"
	[ "$cr_bind" = 127.0.0.1 ] || cr_open="$cr_open $cr_web/tcp"

	# stage: nothing is made until the box and the binary have answered, and every object the manifest will name is new, so
	# undoing this run can remove only what it made
	run_phase stage 10
	if [ $cr_move = 1 ]; then
		# every stock object the move will take, in its role's namespace, before anything changes
		fr_validate "$cr_mv_role" move_
		fr_ok "$cr_mv_role" store "$cr_mv_store" || run_result failed-cleaned "$cr_mv_store is not the stock $cr_mv_role store" "update the app"
		[ -z "$cr_mv_unit" ] || fr_ok "$cr_mv_role" unit "$cr_mv_unit" ||
			run_result failed-cleaned "$cr_mv_unit is not the stock $cr_mv_role unit" "update the app"
		[ -z "$cr_mv_ctr" ] || fr_ok "$cr_mv_role" container "$cr_mv_ctr" ||
			run_result failed-cleaned "$cr_mv_ctr is not the stock $cr_mv_role container" "update the app"
		[ ! -f "$cr_state/moved" ] || mv_settle
		# a stock container gone since the inspection (another phone's move took it) is no move to make: a unit's is run_recheck's
		# expect_unit (P2 B13b)
		[ -z "$cr_mv_ctr" ] || docker container inspect "$cr_mv_ctr" >/dev/null 2>&1 ||
			run_result failed-cleaned "the stock container $cr_mv_ctr is gone since the check" "check again"
	fi
	if [ -e "$cr_state" ] || [ -e "$cr_root" ] || [ -e "/etc/systemd/system/$cr_unit" ]; then
		# «Продолжить» undoes only what the run it continues made and left with no result: never a finished instance (whose
		# store holds its clients), never one a later run made (B8 verify F8)
		cr_of=$(plan resume_of)
		cr_made_by=$(sed -n 's/.*"run":"\([^"]*\)".*/\1/p' "$cr_state/manifest.json" 2>/dev/null)
		if [ "$(plan resume)" = 1 ] && [ -n "$cr_of" ] && [ "$cr_made_by" = "$cr_of" ] && [ -f "$RUNS/$cr_of/log" ] &&
			[ ! -s "$RUNS/$cr_of/result" ] && ! grep -q '^CSQTT_RESULT|' "$RUNS/$cr_of/log"; then
			# «Продолжить» (B8 review F1): an earlier run of this plan was cut off; what its manifest names goes (its server
			# stopped, its rules with it), and the create runs again from the start with this run's credentials
			MF_OBJECTS=$(mf_objects_of "$cr_state/manifest.json" | mf_objects_ours "$cr_id")
			MF_HOST=$(mf_host_of "$cr_state/manifest.json" | in_unit=$cr_unit in_state=$cr_state inst_ours_only "$cr_id")
			[ ! -f "$cr_state/stock-ran" ] || cr_mv_ran=1
			# the images that run made (pinned, the same again) stay for this one, its own to take if it fails (P2 B13a review F1)
			cr_kept_images=$(printf '%s\n' "$MF_OBJECTS" | grep '^image ')
			MF_OBJECTS=$(printf '%s\n' "$MF_OBJECTS" | grep -v '^image ')
			# the floors are the box's: while another instance of ours stands, they stay (it may have skipped what this one set)
			for cr_m in /var/lib/sanityprotocol/csqtt/*/manifest.json; do
				[ -f "$cr_m" ] && [ "$cr_m" != "$cr_state/manifest.json" ] || continue
				MF_HOST=$(printf '%s\n' "$MF_HOST" | grep -v '^sysctl ')
				break
			done
			run_cmd systemctl stop "$cr_unit"
			mf_undo
			cr_server_undo
			host_undo
			MF_OBJECTS=$cr_kept_images MF_HOST=''
			# a move cut off before its switch: the stock server it stopped serves again until this run stops it in turn (one
			# that did not run stays as it was, B12 review F4)
			if [ $cr_move = 1 ] && [ -n "$cr_mv_unit" ] && [ $cr_mv_ran = 1 ] && ! systemctl is-active --quiet "$cr_mv_unit"; then
				systemctl daemon-reload
				run_cmd systemctl start "$cr_mv_unit"
			fi
			# (its container as the first move found it, P2 B13b)
			[ $cr_move = 0 ] || mv_container_back
		elif [ "$(plan resume)" = 1 ]; then
			run_result failed-cleaned "sqc-$cr_id is not what run ${cr_of:-?} left: it was finished or made again since (run ${cr_made_by:-?})" \
				"check again; to use it, «Подключиться»"
		else
			run_result failed-cleaned "sqc-$cr_id exists already: an earlier run of this plan was cut off" \
				"continue it («Продолжить»), or check again"
		fi
	fi
	# a slot another instance of ours took since the plan (a second phone's create) would share its policy table and mark
	for cr_u in /etc/systemd/system/sanity-csqtt-*.service /var/lib/sanityprotocol/csqtt/*/docker; do
		[ -f "$cr_u" ] && [ "$cr_u" != "$cr_state/docker" ] || continue
		if sed -n 's/^ExecStart=//p' "$cr_u" | grep -qE -- "--instance-slot $cr_slot( |\$)"; then
			cr_who=${cr_u##*/}
			[ "$cr_who" != docker ] || cr_who=sanity-csqtt-$(basename "${cr_u%/docker}")
			run_result failed-cleaned "slot $cr_slot is now $cr_who's" "check again: the app proposes another slot"
		fi
	done
	[ "$(sha256sum "$RUN_DIR/core" 2>/dev/null | cut -d' ' -f1)" = "$cr_sha" ] || cr_fail "the uploaded core is damaged" "try again"
	# its own answer with the flags this unit uses: an unknown flag or value fails here, not in a crash loop (M-129)
	# shellcheck disable=SC2086 # the flags are words of ours
	cr_said=$("$RUN_DIR/core" $cr_flags --version 2>&1 | head -n 1)
	[ "$cr_said" = "csqtt $cr_version CSQTT-WIRE-3" ] || cr_fail "the core does not run here: $cr_said" "check the box's architecture"
	# the TUN and the NAT the server needs, tried for real (M-130), as inspect.sh does: in a network namespace of their own
	# where the box allows one, so what they make goes with it (iptables-nft's nat table included); else undone here
	cr_probe=sqcp$cr_id
	cr_ns=0
	unshare -n true 2>/dev/null && cr_ns=1
	cr_in_ns() { if [ $cr_ns = 1 ]; then unshare -n "$@"; else "$@"; fi; }
	run_cmd cr_in_ns ip tuntap add mode tun name "$cr_probe" || cr_fail "this box cannot make a TUN interface" "ask the hoster to enable TUN/TAP"
	[ $cr_ns = 1 ] || ip link del "$cr_probe" 2>/dev/null
	host_iptables
	# shellcheck disable=SC2016 # expanded by the sh that runs it, with the chain as $1
	run_cmd cr_in_ns sh -c 'iptables -w 5 -t nat -N "$1" && iptables -w 5 -t nat -A "$1" -j MASQUERADE &&
		iptables -w 5 -t mangle -N "$1" && iptables -w 5 -t mangle -A "$1" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu' \
		sh "$cr_probe" || cr_nat=failed
	if [ $cr_ns = 0 ]; then
		for cr_t in nat mangle; do iptables -w 5 -t $cr_t -F "$cr_probe" 2>/dev/null; iptables -w 5 -t $cr_t -X "$cr_probe" 2>/dev/null; done
	fi
	[ "${cr_nat:-}" != failed ] || cr_fail "this box's iptables cannot NAT (see the step's output)" "ask the hoster to enable NAT (iptable_nat, xt_TCPMSS)"
	# the firewall the openings go through, known before the unit is written (a plain iptables box's are the unit's own)
	cr_fw=$(host_fw_mode)
	run_safe_point

	mkdir -p "${cr_state%/*}" || cr_fail "cannot make ${cr_state%/*}"
	mf_add dir "$cr_state"
	mkdir -m 700 "$cr_state" || cr_fail "cannot make $cr_state"
	mf_write
	# (once the state dir is there, so its manifest names each image and Docker change as it is made: a cut leaves them
	# named for «Продолжить», P2 B13a review F1)
	if [ $cr_docker = 1 ]; then
		# Docker running and enabled, the root filesystem's image, ours from it, and its core asked to run inside it (M-129)
		command -v docker >/dev/null 2>&1 || cr_fail "Docker is not installed here" "check again: systemd is offered"
		dk_daemon
		cr_rootfs=sanity-csqtt-rootfs:$(printf '%.8s' "$(plan rootfs_sha256)")
		cr_image=sanity-csqtt:$cr_version-$(printf '%.8s' "$cr_sha")
		dk_rootfs "$(plan rootfs_sha256)" "$(plan rootfs_url)"
		dk_image "$cr_rootfs" "$cr_image" "$RUN_DIR/core"
		# shellcheck disable=SC2086 # the flags are words of ours
		cr_said=$(docker run --rm --network none "$cr_image" /opt/sanityprotocol/csqtt-server $cr_flags --version 2>&1 | head -n 1)
		[ "$cr_said" = "csqtt $cr_version CSQTT-WIRE-3" ] || cr_fail "the core does not run in its image: $cr_said" "check the box's architecture"
	fi
	mf_add dir "$cr_root"
	mkdir -p "$cr_release" && chmod 755 "$cr_root" "$cr_root/releases" "$cr_release" || cr_fail "cannot make $cr_release"
	install -m 755 "$RUN_DIR/core" "$cr_release/csqtt-server" && ln -sfn "$cr_release" "$cr_root/current" || cr_fail "cannot install the core"
	run_phase "state and unit" 30
	# env (0600): the panel login, its secret path, the public address; never the main password, which env would apply over
	# the store at every start (undoing a change made in the panel): it goes in the one-shot overrides the server consumes
	if [ $cr_move = 1 ]; then
		# a move: the stock install's own login, password, secret path and address, each value as its line had it (systemd reads
		# both files alike); one missing (B5) is this plan's new one, which the app shows. Its main password is in its store
		{
			mv_line CSQTT_WEB_USER "$(plan web_user)"
			mv_line CSQTT_WEB_PASS "$(sed -n 's/^web_pass=//p' "$RUN_DIR/secrets")"
			mv_line CSQTT_WEB_SECRET_PATH ""
			mv_line CSQTT_PUBLIC_IP "$(plan public_ip)"
		} >"$cr_state/env" && chmod 600 "$cr_state/env" || cr_fail "cannot write $cr_state/env"
	else
		{
			printf 'CSQTT_WEB_USER=%s\n' "$(plan web_user)"
			sed -n 's/^web_pass=/CSQTT_WEB_PASS=/p; s/^secret_path=/CSQTT_WEB_SECRET_PATH=/p' "$RUN_DIR/secrets"
			printf 'CSQTT_PUBLIC_IP=%s\n' "$(plan public_ip)"
		} >"$cr_state/env" && chmod 600 "$cr_state/env" || cr_fail "cannot write $cr_state/env"
		( umask 077 && printf '{"main_password":"%s"}\n' "$(sed -n 's/^main_password=//p' "$RUN_DIR/secrets" | sed 's/[\\"]/\\&/g')" \
			>"$cr_state/deploy-overrides.json" ) || cr_fail "cannot write the main password"
	fi
	cr_open_unit=''
	if [ "$cr_fw" = iptables ]; then
		cr_open_unit=$(inst_open_sh "$cr_id" "$cr_state" "$cr_open") || cr_fail "cannot write $cr_state/open.sh"
	fi
	# a move's unit starts only once the marker is there: until the switch, a boot starts the stock one
	if [ $cr_docker = 1 ]; then
		# what the instance is in Docker, read by its removal, read-back, restart and journal: its container, its images, and
		# its flags on an ExecStart= line, as a unit's (a kept store's flags are then read alike)
		printf '# docker %s\n# image %s\n# rootfs %s\nExecStart=/opt/sanityprotocol/csqtt-server %s\n' "$cr_ctr" "$cr_image" "$cr_rootfs" \
			"$cr_flags" >"$cr_state/docker" || cr_fail "cannot write $cr_state/docker"
	else
		cr_cond=''
		[ $cr_move = 0 ] || cr_cond="ConditionPathExists=$cr_state/moved"
		mf_add unit "/etc/systemd/system/$cr_unit"
		inst_unit_text "$cr_id" "$cr_root" "$cr_state" "$cr_flags" "$cr_open_unit" "$cr_cond" >"/etc/systemd/system/$cr_unit" ||
			cr_fail "cannot write $cr_unit"
		systemctl daemon-reload
	fi
	run_safe_point

	# host: forwarding and the floors (in Docker the server's own tuning too, review F5), then the openings (each recorded as
	# made, undone by a failure from here on)
	run_phase host 45
	if [ $cr_docker = 1 ]; then host_sysctl tuning; else host_sysctl; fi
	host_firewall
	[ "$cr_fw" != iptables ] || host_add iptables-input "ports=$(printf '%s' "$cr_open" | tr ' ' ,)"
	run_safe_point

	# switch: nothing ran before, so it is the start (Type=notify: it returns once the server said READY=1). From here a
	# cancel is refused (D4): the run ends in ok or undoes itself
	run_phase switch 60
	# what the server will set host-wide, as it is now: put back if this run is undone and no other csqtt server runs (F4)
	for cr_k in $CR_SERVER_SYSCTLS; do printf '%s=%s\n' "$cr_k" "$(sysctl -n "$cr_k" 2>/dev/null)"; done >"$RUN_DIR/sysctl-before"
	! command -v nft >/dev/null 2>&1 || nft list tables >"$RUN_DIR/nft-before" 2>/dev/null
	awk '{print $1}' /proc/modules >"$RUN_DIR/modules-before" 2>/dev/null
	: >"$RUN_DIR/switching"
	if [ $cr_move = 1 ]; then
		# the stock unit starts no more once the marker is there (a drop-in on it, this run's object), ours only then
		if [ -n "$cr_mv_unit" ]; then
			cr_mv_dropin=/etc/systemd/system/$cr_mv_unit.d/sanity-move.conf
			mf_add file "$cr_mv_dropin"
			mkdir -p "${cr_mv_dropin%/*}" && printf '[Unit]\nConditionPathExists=!%s/moved\n' "$cr_state" >"$cr_mv_dropin" ||
				cr_fail "cannot write $cr_mv_dropin"
			systemctl daemon-reload
		fi
		[ $cr_docker = 1 ] || run_cmd systemctl enable "$cr_unit" || cr_fail "$cr_unit could not be enabled"
		# the stock server stops (its tagged rules go with a graceful stop); nothing of it may run on outside its unit (M-120).
		# Whether it ran is kept first (a crash loop between two tries counts): an undo starts it again only then (B12 review F4)
		if [ -n "$cr_mv_unit" ]; then
			case $(systemctl is-active "$cr_mv_unit" 2>/dev/null) in
			inactive | failed | '') ;;
			*) cr_mv_ran=1; : >"$cr_state/stock-ran" ;;
			esac
		fi
		# a stock Docker install's container (P2 B13b): its restart policy and whether it ran recorded first (MV_STOCK: synced, kept
		# from an earlier move cut off); the policy set to `no` and the container stopped is its half of the switch (ours runs with
		# `unless-stopped`): a reboot after ours runs starts ours alone, one before leaves neither running and both startable,
		# which «Продолжить» settles
		if [ -n "$cr_mv_ctr" ] && [ ! -s "$MV_STOCK$cr_mv_ctr" ]; then
			cr_mv_pol=$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$cr_mv_ctr" 2>/dev/null)
			[ -n "$cr_mv_pol" ] || cr_fail "cannot read $cr_mv_ctr's restart policy" "check again"
			cr_mv_cran=0
			[ "$(docker inspect -f '{{.State.Running}}' "$cr_mv_ctr" 2>/dev/null)" != true ] || cr_mv_cran=1
			printf '%s\n%s\n' "$cr_mv_pol" "$cr_mv_cran" >"$MV_STOCK$cr_mv_ctr.new" && mv -f "$MV_STOCK$cr_mv_ctr.new" "$MV_STOCK$cr_mv_ctr" &&
				sync || cr_fail "cannot write $MV_STOCK$cr_mv_ctr"
		fi
		cr_mv_stopped=1
		[ -z "$cr_mv_unit" ] || run_cmd systemctl stop "$cr_mv_unit"
		fr_stop_containers move_ || cr_fail "the stock container $cr_mv_ctr would not stop" "stop it, then try again"
		[ -z "$(fr_outside "$cr_mv_role")" ] || cr_fail "the stock $cr_mv_role server runs outside its unit" "stop it, then try again"
		# its store, consistent now it is stopped, read through a symlinked config dir (M-131), with its certificate (the
		# panel's pin stays) and an overrides file it had not consumed (B6)
		for cr_f in $MV_STORE; do
			[ ! -f "$cr_mv_store/$cr_f" ] || cp -p "$cr_mv_store/$cr_f" "$cr_state/$cr_f" || cr_fail "cannot copy the stock $cr_f"
		done
		sync
		# the switch: one rename moves the box from theirs to ours
		: >"$cr_state/moved.new" && mv -f "$cr_state/moved.new" "$cr_state/moved" && sync || cr_fail "cannot switch"
	elif [ $cr_docker = 0 ]; then
		# enabled, then started on its own: systemd 255's `enable --now` exits 0 for a start that failed (the zoo, Ubuntu 24.04)
		run_cmd systemctl enable "$cr_unit" || cr_fail "$cr_unit could not be enabled"
	fi
	if [ $cr_docker = 1 ]; then
		mf_add container "$cr_ctr"
		# shellcheck disable=SC2086 # the flags are words of ours
		run_cmd dk_run "$cr_ctr" "$cr_image" "$cr_state" "$cr_fw" $cr_flags || cr_fail "Docker would not run $cr_ctr: $(dk_lines "$cr_ctr")"
	else
		run_cmd systemctl start "$cr_unit" || cr_fail "$cr_unit did not start: $(inst_start_lines "$cr_unit")"
	fi

	# verify: it runs, it alone holds its ports (SO_REUSEADDR makes a bind prove nothing), its TUN is up, the panel answers
	run_phase verify 80
	if [ $cr_move = 1 ]; then cr_secret=$(mv_secret); else cr_secret=$(sed -n 's/^secret_path=//p' "$RUN_DIR/secrets"); fi
	if [ $cr_docker = 1 ]; then
		cr_why=$(inst_verify "docker:$cr_ctr" "$cr_udp" "$cr_web" "$cr_iface" "$cr_secret")
		[ -z "$cr_why" ] || cr_why="$cr_why: $(dk_lines "$cr_ctr")"
	else
		cr_why=$(inst_verify "$cr_unit" "$cr_udp" "$cr_web" "$cr_iface" "$cr_secret")
	fi
	[ -z "$cr_why" ] || cr_fail "$cr_why"
	# a move ends in the plan's order: its unit without the condition, the stock install's objects, the marker last
	[ $cr_move = 0 ] || mv_finish
	run_phase "done" 100
	run_result ok "" "read the connection back"
}

# ── update: an instance of ours to another build, or the same one again («Починить») (P2-PLAN §3 B10) ─────────────────
# The new release in a directory of its own; at the switch the server is stopped, its store copied aside, `current` flipped
# in one rename and the server started; a failure from there puts the store copy and the old release back. Nothing of the
# instance's config is written: ports, env, its certificate, the secret path all stay (M-133, M-148).
UP_STORE='csqtt.db csqtt.db-wal csqtt.db-shm passwords.json passwords.json.imported'

# The store copied aside whole before the new build can touch it: built beside, synced (a reboot after the flip finds it on
# disk), then put in place of the last copy, so a copy that fails or is cut off leaves the store and that copy as they were
# (B10 review F1)
up_copy_store() {
	rm -rf "$up_copy.new" && mkdir -m 700 "$up_copy.new" || return 1
	for up_f in $UP_STORE; do
		[ ! -f "$up_state/$up_f" ] || cp -p "$up_state/$up_f" "$up_copy.new/$up_f" || return 1
	done
	sync
	rm -rf "$up_copy" && mv "$up_copy.new" "$up_copy"
}

# The store put back from that copy: every file copied beside its place first, then renamed into it, so neither a full disk nor
# a cut leaves a file missing; a file the copy has not (a WAL the new build made) goes
up_store_back() {
	rm -rf "$up_state/.restore" && mkdir -m 700 "$up_state/.restore" || return 1
	for up_f in $UP_STORE; do
		[ ! -f "$up_copy/$up_f" ] || cp -p "$up_copy/$up_f" "$up_state/.restore/$up_f" || return 1
	done
	for up_f in $UP_STORE; do
		if [ -f "$up_state/.restore/$up_f" ]; then mv -f "$up_state/.restore/$up_f" "$up_state/$up_f" || return 1
		else rm -f "$up_state/$up_f" || return 1; fi
	done
	rmdir "$up_state/.restore"
}

up_prune() {  # the release before stays for one more update; older ones go (an instance in Docker: their images with them)
	for up_r in "$up_root"/releases/*/; do
		up_r=${up_r%/}
		[ "$up_r" = "$up_new" ] || [ "$up_r" = "$up_old" ] || {
			rm -rf "$up_r"
			[ $up_docker = 0 ] || dk_image_rm "sanity-csqtt:${up_r##*/}"
		}
	done
	[ $up_docker = 0 ] || dk_rootfs_sweep
}

# The instance's server stopped and started on the build `current` names (P2 B13c): its unit, or, in Docker, its container made
# again from that build's image (the docker file's image line set from `current` first, so a removal or a read reads it); a
# container's openings and backend as its create's
up_stop() {
	if [ $up_docker = 1 ]; then docker stop -t 15 "$up_ctr" >/dev/null 2>&1; else systemctl stop "$up_unit" 2>/dev/null; fi
	return 0
}
up_start() {
	if [ $up_docker = 0 ]; then
		run_cmd systemctl start "$up_unit"
		return
	fi
	us_img=sanity-csqtt:$(basename "$(readlink "$up_root/current")")
	sed "s|^# image .*|# image $us_img|" "$up_state/docker" >"$up_state/docker.new" && mv -f "$up_state/docker.new" "$up_state/docker" || return 1
	docker rm -f "$up_ctr" >/dev/null 2>&1
	us_fw=none
	[ ! -f "$up_state/open.sh" ] || us_fw=iptables
	# shellcheck disable=SC2086 # the instance's own flags
	run_cmd dk_run "$up_ctr" "$us_img" "$up_state" "$us_fw" $up_flags
}
up_running() {
	# (in Docker the build that runs is its container's image, which must be the one `current` names: B13c review F6)
	if [ $up_docker = 1 ]; then
		[ "$(docker inspect -f '{{.State.Running}} {{.Config.Image}}' "$up_ctr" 2>/dev/null)" = "true sanity-csqtt:$(basename "$(readlink "$up_root/current")")" ]
	else systemctl is-active --quiet "$up_unit"; fi
}
up_where_runs() {  # for a result's cause: the server's state word
	if [ $up_docker = 1 ]; then docker inspect -f '{{.State.Status}}' "$up_ctr" 2>/dev/null || echo gone; else systemctl is-active "$up_unit"; fi
}

# up_back <why> [store]: back to the build before, setting up_why (empty: it serves). With `store` (the new build has run on it)
# the store comes back from the copy first; without, the new build never ran and the store stays as it is, and no copy is
# named (this run made none: one there is an older update's). `current` is flipped back only where it moved. The record goes
# once the box is back on disk; a store that cannot be put back keeps it, the unit stopped, so the next run tries again. Never
# run inside $(…): its results end the run
up_back() {
	up_stop
	if [ "${2:-}" = store ]; then
		up_where="the store before the update is whole in $up_copy"
		up_store_back 2>"$RUN_DIR/restore.err" ||
			run_result failed-not-rolled-back "$1; the store could not be put back: $(head -c 200 "$RUN_DIR/restore.err" | tr '\n' ' ')" \
				"free some disk, then «Запустить» or «Починить»: $up_where"
	elif [ "$up_new" = "$up_old" ]; then
		up_where="its store is its own: the same build runs on it"
	else
		up_where="its store is as it was: the new build never ran on it"
	fi
	if [ "$(readlink "$up_root/current")" != "$up_old" ]; then
		ln -sfn "$up_old" "$up_root/current.new" && mv -Tf "$up_root/current.new" "$up_root/current" ||
			run_result failed-not-rolled-back "$1; cannot switch back to $up_old" "«Починить»: $up_where"
	fi
	# the server back on the build before first, its record dropped only then (a cut between them must find the record:
	# B13c review F6); the failed build's directory and image after it (Docker refuses an image a container, stopped or not,
	# still uses: the zoo, 13:48 UTC)
	up_start >/dev/null 2>&1
	rm -f "$UP_PENDING"
	if [ "$up_new" != "$up_old" ]; then
		rm -rf "$up_new"
		[ $up_docker = 0 ] || dk_image_rm "sanity-csqtt:${up_new##*/}"
	fi
	up_why=$(inst_verify "$up_check" "$up_udp" "$up_web" "$up_iface" "$up_secret")
	[ -z "$up_why" ] || run_result failed-not-rolled-back \
		"$1; the rollback did not serve either: $up_why ($(up_where_runs), current -> $(readlink "$up_root/current"))" \
		"read its journal; $up_where"
}

up_rollback() {  # up_rollback <why> [store]: up_back, and the run ends there
	run_phase "roll back" 90
	up_back "$1" "${2:-}"
	run_result failed-rolled-back "$1" "the build it ran before runs again, its store as it was"
}

# An update left unfinished inside its switch (a reboot, a kill, a store that could not be put back) is settled first, from its
# record (B10 review F2):
#  - left before its flip (current still the build before), or the same build again: the build before runs on its own store,
#    which it may have served on since, so the store stays; what that update made goes; its unit is started if it is not running
#  - left after its flip: the new build is verified where it is; serving, the update is finished; not, it is rolled back with
#    the store from the copy (whole: the flip follows the synced copy). up_settled names the build that then serves, up_failed
#    the one rolled back from
up_recover() {
	rc_old=$(sed -n 's/^old=//p' "$UP_PENDING" | head -n 1) rc_new=$(sed -n 's/^new=//p' "$UP_PENDING" | head -n 1)
	# both are releases of this instance, or the record is not one this file wrote: it names nothing to remove then
	for rc_p in "$rc_old" "$rc_new"; do
		case ${rc_p#"$up_root/releases/"} in "$rc_p" | '' | */* | .*) rm -f "$UP_PENDING"; return 0 ;; esac
	done
	run_phase recover 8
	: >"$RUN_DIR/switching"
	rm -rf "$up_copy.new" "$up_state/.restore"
	if [ "$(readlink "$up_root/current")" != "$rc_new" ] || [ "$rc_new" = "$rc_old" ]; then
		up_running || up_start
		rm -f "$UP_PENDING"
		if [ "$rc_new" != "$rc_old" ] && [ "$rc_new" != "$(readlink "$up_root/current")" ]; then
			rm -rf "$rc_new"
			[ $up_docker = 0 ] || dk_image_rm "sanity-csqtt:${rc_new##*/}"
		fi
		return 0
	fi
	# (a record a build that served since has gone: a unit's ExecStartPost drops it after READY=1, our entrypoint in Docker once
	# its server holds its port after a boot, B13c review F2 and verify N1; Docker counts a restart loop as running)
	up_old=$rc_old up_new=$rc_new
	up_running || up_start
	up_why=$(inst_verify "$up_check" "$up_udp" "$up_web" "$up_iface" "$up_secret")
	if [ -n "$up_why" ]; then
		rc_cause="the update to ${rc_new##*/} left unfinished before does not serve: $up_why"
		up_back "$rc_cause" store
		up_settled=$rc_old up_failed=$rc_new
		return 0
	fi
	up_prune
	rm -f "$UP_PENDING"
	up_settled=$rc_new
}

action_update() {
	up_id=$(plan id) up_version=$(plan version) up_sha=$(plan core_sha)
	case $up_id in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) run_result failed-cleaned "bad instance id $up_id" "update the app" ;; esac
	up_root=/opt/sanityprotocol/csqtt/$up_id up_state=/var/lib/sanityprotocol/csqtt/$up_id up_unit=sanity-csqtt-$up_id.service
	up_target=$up_root/releases/$up_version-$(printf '%.8s' "$up_sha") up_copy=$up_state/pre-update UP_PENDING=$up_state/update.pending
	# an instance in Docker (P2 B13c): its flags are its docker file's, its server its container on the image `current` names
	up_docker=0 up_ctr=sanity-csqtt-$up_id up_check=$up_unit
	if [ -f "$up_state/docker" ] && [ ! -f "$up_state/unit.saved" ]; then
		up_docker=1 up_check=docker:$up_ctr
		[ -L "$up_root/current" ] || run_result failed-cleaned "sqc-$up_id is not installed here" "check again"
		up_flags=$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "$up_state/docker" | head -n 1)
	else
		[ -f "/etc/systemd/system/$up_unit" ] && [ -L "$up_root/current" ] || run_result failed-cleaned "sqc-$up_id is not installed here" "check again"
		up_flags=$(sed -n "s|^ExecStart=$up_root/current/csqtt-server ||p" "/etc/systemd/system/$up_unit" | head -n 1)
	fi
	up_udp=$(printf '%s\n' "$up_flags" | sed -n 's/.*--listen [^ ]*:\([0-9]*\).*/\1/p')
	up_web=$(printf '%s\n' "$up_flags" | sed -n 's/.*--web-port \([0-9]*\).*/\1/p')
	up_iface=$(printf '%s\n' "$up_flags" | sed -n 's/.*--iface \([^ ]*\).*/\1/p')
	up_secret=$(inst_env "$up_state" CSQTT_WEB_SECRET_PATH)
	up_settled='' up_failed=''
	# while this run lives no start of the unit clears the record (its ExecStartPost looks for this marker): only a run knows
	# whether the build it went to serves. cmd_exec's trap takes it at the run's end; a run killed leaves it until the next one
	# or a boot (B10 verify of round 2, V1)
	UP_MARK=/run/sanity-csqtt-$up_id.updating
	: >"$UP_MARK"

	run_phase stage 10
	[ "$(sha256sum "$RUN_DIR/core" 2>/dev/null | cut -d' ' -f1)" = "$up_sha" ] || run_result failed-cleaned "the uploaded core is damaged" "try again"
	# shellcheck disable=SC2086 # the unit's own flags
	up_said=$("$RUN_DIR/core" $up_flags --version 2>&1 | head -n 1)
	[ "$up_said" = "csqtt $up_version CSQTT-WIRE-3" ] || run_result failed-cleaned "the new core does not run here with this instance's flags: $up_said" "check the box's architecture"
	[ ! -f "$UP_PENDING" ] || up_recover
	up_old=$(readlink "$up_root/current") up_new=$up_target
	if [ -n "$up_settled" ]; then
		# what an update left unfinished was settled on this very build, and it serves: nothing is left to do
		[ "$up_settled" != "$up_new" ] || run_result ok "" "read the connection back"
		# it was rolled back from this very build just now: going to it again would fail as it did
		[ "$up_failed" != "$up_new" ] || run_result failed-rolled-back "$rc_cause" "the build it ran before runs again, its store as it was"
	fi
	# (in Docker: the images this run made go too, through the manifest's own kinds)
	MF_OBJECTS=''
	action_cleanup() {
		[ ! -d "$up_new" ] || [ "$up_new" = "$up_old" ] || rm -rf "$up_new"
		mf_undo
	}
	cr_fail() { action_cleanup; run_result failed-cleaned "$1" "${2:-check again}"; }
	if [ $up_docker = 1 ]; then
		# Docker answering, the root filesystem's image, the new build's image from it, and its core asked to run inside it
		docker info >/dev/null 2>&1 || cr_fail "Docker does not answer" "check Docker on the box"
		dk_rootfs "$(plan rootfs_sha256)" "$(plan rootfs_url)"
		up_img=sanity-csqtt:${up_new##*/}
		dk_image "sanity-csqtt-rootfs:$(printf '%.8s' "$(plan rootfs_sha256)")" "$up_img" "$RUN_DIR/core"
		# shellcheck disable=SC2086 # the instance's own flags
		up_said=$(docker run --rm --network none "$up_img" /opt/sanityprotocol/csqtt-server $up_flags --version 2>&1 | head -n 1)
		[ "$up_said" = "csqtt $up_version CSQTT-WIRE-3" ] || cr_fail "the new core does not run in its image: $up_said" "check the box's architecture"
	fi
	if [ "$up_new" != "$up_old" ]; then
		mkdir -p "$up_new" && install -m 755 "$RUN_DIR/core" "$up_new/csqtt-server.new" && mv -f "$up_new/csqtt-server.new" "$up_new/csqtt-server" ||
			{ action_cleanup; run_result failed-cleaned "cannot install the new release in $up_new" "check the box's disk"; }
	fi
	run_safe_point

	# switch (D4: a cancel is refused from here)
	run_phase switch 60
	: >"$RUN_DIR/switching"
	if [ "$up_new" = "$up_old" ]; then
		# «Починить»: the same build again, over a damaged or missing binary. Nothing migrates, so its store needs no copy, and
		# the copy from before the last real update is never overwritten by a build that may be the failing one (V1)
		up_with=''
		up_stop
		install -m 755 "$RUN_DIR/core" "$up_new/csqtt-server.new" && mv -f "$up_new/csqtt-server.new" "$up_new/csqtt-server" || up_rollback "cannot reinstall the binary"
	else
		# recorded, stopped, its store copied aside, `current` flipped (the record names its boot: in Docker the entrypoint drops
		# only an earlier boot's, never this run's own: B13c verify N1)
		up_with=store
		printf 'old=%s\nnew=%s\nboot=%s\n' "$up_old" "$up_new" "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)" >"$UP_PENDING.new" &&
			mv -f "$UP_PENDING.new" "$UP_PENDING" ||
			{ rm -f "$UP_PENDING.new"; action_cleanup; run_result failed-cleaned "cannot write $UP_PENDING" "check the box's disk"; }
		up_stop
		if ! up_copy_store 2>"$RUN_DIR/copy.err"; then
			rm -rf "$up_copy.new"
			up_rollback "cannot keep a copy of the store aside: $(head -c 200 "$RUN_DIR/copy.err" | tr '\n' ' ')"
		fi
		ln -sfn "$up_new" "$up_root/current.new" && mv -Tf "$up_root/current.new" "$up_root/current" || up_rollback "cannot switch to $up_new"
	fi
	if [ $up_docker = 1 ]; then
		up_start || up_rollback "$up_ctr did not start on the new build: $(dk_lines "$up_ctr")" "$up_with"
	else
		up_start || up_rollback "$up_unit did not start on the new build: $(inst_start_lines "$up_unit")" "$up_with"
	fi

	run_phase verify 80
	up_why=$(inst_verify "$up_check" "$up_udp" "$up_web" "$up_iface" "$up_secret")
	[ -z "$up_why" ] || up_rollback "the new build does not serve: $up_why" "$up_with"
	up_prune
	rm -f "$UP_PENDING"
	run_phase "done" 100
	run_result ok "" "read the connection back"
}

# ── remove, restore, restart: an instance of ours (P2-PLAN §3 B11; APP-PLAN §6.10) ────────────────────────────────────
# Bounded by construction: what goes is named by the id alone (its two roots, its unit, its SQC-<ID>_ objects, its
# openings in the manifest's host list, our drop-in when no instance of ours remains), never by a manifest's paths.
inst_paths() {  # inst_paths <id>: sets in_root in_state in_unit
	case $1 in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) run_result failed-cleaned "bad instance id $1" "update the app" ;; esac
	in_root=/opt/sanityprotocol/csqtt/$1 in_state=/var/lib/sanityprotocol/csqtt/$1 in_unit=sanity-csqtt-$1.service
}

# Our drop-in goes with the last instance of ours, unless a csqtt server still runs beside (a stock one, the side tool's, swg's):
# that one leans on the same floors, so the drop-in stays and keeps them across a boot, and a later create or restore finds
# them recorded there; a later removal when none runs takes it (B11a verify N1). A floor goes back to what its comment says it
# was only while its live value is still the one the drop-in set: one raised since by someone else keeps its value.
# ip_forward is never written back: Docker, a WireGuard, any router may have come to rely on it, and writing it resets every
# device's forwarding and accept_redirects (B11a review F1); it is the server's own tuning as well. The iptables-nft tables
# the server's rules made stay, empty and inert, until the next boot: telling them from an admin's (a DROP policy and nothing
# else) is not safe (B11a review F2)
host_dropin_drop() {
	# (a stock unit counts installed, running or not: slim and the side tool write no sysctl file of their own and refuse to
	# start with forwarding off, B18; its removal drops this file after it, B11c review F3)
	for hd_u in /etc/systemd/system/sanity-csqtt-*.service /etc/systemd/system/csqtt.service /etc/systemd/system/csqtt25.service; do
		[ -f "$hd_u" ] && return 0
	done
	# (an instance of ours in Docker counts by its docker file, whatever Docker's state: asking a stopped Docker would start it
	# through its socket, P2 B13a review F4; a removal takes the file, keeping its lines in unit.saved)
	for hd_d in /var/lib/sanityprotocol/csqtt/*/docker; do [ -f "$hd_d" ] && return 0; done
	[ -f "$HOST_DROPIN" ] || return 0
	! pgrep -x 'csqtt(25|-server|-swg)?' >/dev/null 2>&1 || return 0
	sed -n 's/^# \([a-z0-9_.]*\) was \(.*\)$/\1 \2/p' "$HOST_DROPIN" | while read -r hd_k hd_was; do
		[ "$hd_k" != net.ipv4.ip_forward ] || continue
		hd_set=$(sed -n "s/^$hd_k = //p" "$HOST_DROPIN" | head -n 1)
		[ "$(sysctl -n "$hd_k" 2>/dev/null | tr -s '\t ' '  ')" = "$hd_set" ] || continue
		sysctl -q -w "$hd_k=$hd_was" 2>/dev/null
	done
	rm -f "$HOST_DROPIN"
}

# inst_found_host <id> (after inst_paths): the host changes of an instance of ours found by their own names, for one whose
# manifest was lost (§1.3; B11c): ufw's rules commented sqc-<id>, firewalld's zone sqc<id> and policy sqc<id>-fwd (and its
# reload), the INPUT openings of its open.sh as its create recorded them. A port it opened in firewalld's default zone cannot
# be told from one that was open before, so it is not found: a removal leaves it open, never closes another's
inst_found_host() {
	if command -v ufw >/dev/null 2>&1; then
		ufw status 2>/dev/null | awk -v c="# sqc-$1" '$2 != "(v6)" && $0 ~ (c "[[:space:]]*$") {print "ufw rule=" $1}' | sort -u
	fi
	if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd -q --state 2>/dev/null; then
		if_fw=0
		if firewall-cmd --permanent --get-zones 2>/dev/null | tr ' ' '\n' | grep -qx "sqc$1"; then
			echo "firewalld-zone zone=sqc$1"
			if_fw=1
		fi
		if firewall-cmd --permanent --get-policies 2>/dev/null | tr ' ' '\n' | grep -qx "sqc$1-fwd"; then
			echo "firewalld-policy policy=sqc$1-fwd"
			if_fw=1
		fi
		[ $if_fw = 0 ] || echo firewalld-reload
	fi
	if [ -f "$in_state/open.sh" ]; then
		# the ports create opened: the UDP one, and the panel's unless it listens on 127.0.0.1 alone (B11c review F5)
		if_flags=$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "/etc/systemd/system/$in_unit" 2>/dev/null | head -n 1)
		if_ports="$(printf '%s\n' "$if_flags" | sed -n 's/.*--listen [^ ]*:\([0-9]*\).*/\1/p')/udp"
		printf '%s\n' "$if_flags" | grep -qE -- '--web-bind 127\.0\.0\.1( |$)' ||
			if_ports="$if_ports,$(printf '%s\n' "$if_flags" | sed -n 's/.*--web-port \([0-9]*\).*/\1/p')/tcp"
		echo "iptables-input ports=$if_ports"
	fi
	return 0
}

# inst_ours_only <id> (after inst_paths; stdin: the host changes a manifest names): those that are this instance's own by their
# names. A manifest is trusted for what its create recorded, never for another's: a corrupt one naming an admin's ufw rule, or
# another instance's, must not take it (P2-PLAN §2.4 «a corrupt manifest», the zoo's corrupt cell). ufw's rules commented
# sqc-<id>, as ufw keeps them whether it is on or off; firewalld's zone sqc<id>, its policy sqc<id>-fwd, a port in that zone or
# one of the instance's own ports in another, and the reload; a raised floor or the server's tuning by its key (run.sh's own
# lists), Docker's unit as a create enabled or started it. (A removal takes the last two out before: they stay with the box.
# A cut-off run's undo, «Продолжить» and a move's settle, keeps them: the operator's word of 2026-11-24, the guard)
inst_ours_only() {
	io_keys=" $(printf '%s\n%s\n' "$HOST_FLOORS" "$HOST_TUNING" | awk '{print $1}' | tr '\n' ' ') "
	io_ufw=''
	! command -v ufw >/dev/null 2>&1 || io_ufw=$(ufw show added 2>/dev/null)
	# its own ports, from its flags: its unit's, its container's, or those kept for «Восстановить»
	io_ports=''
	for io_f in "/etc/systemd/system/$in_unit" "$in_state/docker" "$in_state/unit.saved"; do
		[ -f "$io_f" ] || continue
		io_flags=$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "$io_f" | head -n 1)
		io_ports="$(printf '%s\n' "$io_flags" | sed -n 's/.*--listen [^ ]*:\([0-9][0-9]*\).*/\1/p')/udp"
		io_ports="$io_ports $(printf '%s\n' "$io_flags" | sed -n 's/.*--web-port \([0-9][0-9]*\).*/\1/p')/tcp"
		break
	done
	while read -r io_kind io_rest; do
		[ -n "$io_kind" ] || continue
		io_zone='' io_port='' io_rule='' io_policy='' io_key='' io_unit=''
		for io_kv in $io_rest; do
			case $io_kv in
			zone=*) io_zone=${io_kv#zone=} ;; port=*) io_port=${io_kv#port=} ;; rule=*) io_rule=${io_kv#rule=} ;;
			policy=*) io_policy=${io_kv#policy=} ;; key=*) io_key=${io_kv#key=} ;; unit=*) io_unit=${io_kv#unit=} ;;
			esac
		done
		case $io_kind in
		ufw) printf '%s\n' "$io_ufw" | grep -qxF "ufw allow $io_rule comment 'sqc-$1'" || continue ;;
		firewalld-zone) [ "$io_zone" = "sqc$1" ] || continue ;;
		firewalld-policy) [ "$io_policy" = "sqc$1-fwd" ] || continue ;;
		firewalld-port)
			io_own=0
			case " $io_ports " in *" $io_port "*) [ -z "$io_port" ] || io_own=1 ;; esac
			[ "$io_zone" = "sqc$1" ] || [ $io_own = 1 ] || continue
			;;
		firewalld-reload) ;;
		sysctl)
			io_own=0
			case $io_keys in *" $io_key "*) [ -z "$io_key" ] || io_own=1 ;; esac
			[ $io_own = 1 ] || continue
			;;
		service-enabled | service-started) [ "$io_unit" = docker.service ] || continue ;;
		*) continue ;;
		esac
		printf '%s\n' "$io_kind${io_rest:+ $io_rest}"
	done
	return 0
}

# inst_take_down <id> (after inst_paths): an instance of ours stopped and taken off the box by its id alone: its unit, its
# tagged rules, its openings (ufw's and firewalld's through their tool; the iptables ones go with its unit's stop), the drop-in
# with the last instance, its release. The state dir stays as it is
inst_take_down() {
	MF=$in_state/manifest.json MF_ID=$1
	# (a manifest lost by hand: its openings found by their names, so a later «Восстановить» opens them again, B11c review F2)
	# (Docker enabled or started by its create stays so: other containers may rely on it by now, P2 B13a)
	if [ -f "$MF" ]; then MF_HOST=$(mf_host_of "$MF" 2>/dev/null | grep -v '^sysctl ' | grep -v '^service-' | inst_ours_only "$1")
	else MF_HOST=$(inst_found_host "$1"); fi
	if [ -f "/etc/systemd/system/$in_unit" ]; then
		run_cmd systemctl disable --now "$in_unit"
		rm -f "/etc/systemd/system/$in_unit"
		systemctl daemon-reload
		systemctl reset-failed "$in_unit" >/dev/null 2>&1
	fi
	if [ -f "$in_state/docker" ]; then
		# its container stopped gracefully (its server takes its own rules), then gone; our images once no container uses
		# them (Docker refuses while one does: its own count), the instance's before the root filesystem's
		in_ctr=$(sed -n 's/^# docker //p' "$in_state/docker")
		if [ -n "$in_ctr" ] && docker container inspect "$in_ctr" >/dev/null 2>&1; then
			run_cmd docker stop -t 15 "$in_ctr"
			run_cmd docker rm -f "$in_ctr"
		fi
		# (every release's image: the one before an update is kept with its release, B13c review F1; a restore taking a cut
		# restore down keeps them, the same again)
		if [ "${IN_KEEP_IMAGES:-0}" = 0 ]; then
			dk_image_rm "$(sed -n 's/^# image //p' "$in_state/docker")"
			for in_r in "$in_root"/releases/*/; do [ ! -d "$in_r" ] || dk_image_rm "sanity-csqtt:$(basename "$in_r")"; done
			dk_rootfs_sweep
		fi
		# (its lines are unit.saved's now, for «Восстановить»: the file alone marks a live instance, review F4)
		rm -f "$in_state/docker"
	fi
	inst_sweep "$1"
	host_undo
	host_dropin_drop
	rm -rf "$in_root"
	rm -f "$in_state/open.sh"
	# (its Let's Encrypt reference: with the last one, our timer, venv and ACME state, P2 B14)
	le_drop "$in_state"
}

action_remove() {
	rm_id=$(plan id) rm_purge=$(plan purge)
	inst_paths "$rm_id"
	[ -e "$in_state" ] || [ -e "$in_root" ] || [ -f "/etc/systemd/system/$in_unit" ] || run_result failed-cleaned "sqc-$rm_id is not here" "check again"
	# its flags kept for «Восстановить» when the store stays, with the release it ran (a phone with an older core must not
	# restore it: M-150, B11a review F6); nothing has changed if they cannot be
	# (a Docker instance's are its docker file's, its release its image's tag: P2 B13a)
	rm_def='' rm_rel=''
	if [ -f "/etc/systemd/system/$in_unit" ]; then
		rm_def=/etc/systemd/system/$in_unit rm_rel=$(basename "$(readlink "$in_root/current" 2>/dev/null)" 2>/dev/null)
	elif [ -f "$in_state/docker" ]; then
		rm_def=$in_state/docker rm_rel=$(sed -n 's/^# image sanity-csqtt://p' "$in_state/docker")
	fi
	if [ "$rm_purge" != 1 ] && [ -d "$in_state" ] && [ -n "$rm_def" ]; then
		{ printf '# release %s\n' "$rm_rel"; cat "$rm_def"; } >"$in_state/unit.saved.new" && mv -f "$in_state/unit.saved.new" "$in_state/unit.saved" ||
			{ rm -f "$in_state/unit.saved.new"; run_result failed-cleaned "cannot keep sqc-$rm_id's flags in $in_state" "check the box's disk"; }
	fi
	run_phase stop 20
	: >"$RUN_DIR/switching"
	inst_take_down "$rm_id"
	run_phase remove 50
	if [ "$rm_purge" = 1 ]; then
		rm -rf "$in_state"
		rmdir /var/lib/sanityprotocol/csqtt 2>/dev/null
	elif [ -d "$in_state" ]; then
		# what stays: the store, env, certificate, the unit's flags; the manifest names the state dir alone now
		MF_OBJECTS="dir $in_state" MF_HOST=''
		mf_write
	fi
	rmdir /opt/sanityprotocol/csqtt /opt/sanityprotocol 2>/dev/null
	run_phase "done" 100
	if [ "$rm_purge" = 1 ]; then run_result ok "" "sqc-$rm_id is gone, its clients with it"; fi
	run_result ok "" "sqc-$rm_id is gone; its store stays: «Восстановить» brings its clients back"
}

# «Восстановить»: the kept store under the same id, its unit made again from the flags it ran with, the host prepared as a
# create prepares it; the store is never touched, so its clients and passwords come back
action_restore() {
	rs_id=$(plan id) rs_version=$(plan version) rs_sha=$(plan core_sha)
	inst_paths "$rs_id"
	[ -f "$in_state/unit.saved" ] || run_result failed-cleaned "sqc-$rs_id has no kept store to restore" "check again"
	# a store an instance in Docker kept (P2 B13c): it comes back in Docker, its container made again from today's image
	rs_docker=0 rs_ctr=sanity-csqtt-$rs_id rs_dk_live=0
	! grep -q '^# docker ' "$in_state/unit.saved" || rs_docker=1
	# (Docker is asked only while it runs: a stopped one's socket would start it, and with it every container that has a
	# restart policy, unrecorded and unannounced: B13c review F4; a systemd restore never asks it)
	[ $rs_docker = 0 ] || ! systemctl is-active --quiet docker.service || rs_dk_live=1
	if [ -e "$in_root" ] || [ -f "/etc/systemd/system/$in_unit" ] || { [ $rs_dk_live = 1 ] && docker container inspect "$rs_ctr" >/dev/null 2>&1; }; then
		systemctl is-active --quiet "$in_unit" && run_result failed-cleaned "sqc-$rs_id is installed already" "check again"
		[ $rs_dk_live = 0 ] || [ "$(docker inspect -f '{{.State.Running}}' "$rs_ctr" 2>/dev/null)" != true ] ||
			run_result failed-cleaned "sqc-$rs_id is installed already" "check again"
		# a restore (or a removal) cut off before its end left part of it, its flags still kept: what it made goes first, by
		# the id, as a removal takes it, and the restore runs again (B11a review F4)
		run_phase "take down" 5
		: >"$RUN_DIR/switching"
		# (the images a cut restore made stay for this one: the same again, B13c review)
		IN_KEEP_IMAGES=1
		inst_take_down "$rs_id"
		IN_KEEP_IMAGES=0
		MF_OBJECTS="dir $in_state" MF_HOST=''
		mf_write
		rm -f "$RUN_DIR/switching"
	fi
	rs_flags=$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "$in_state/unit.saved" | head -n 1)
	rs_udp=$(printf '%s\n' "$rs_flags" | sed -n 's/.*--listen [^ ]*:\([0-9]*\).*/\1/p')
	rs_web=$(printf '%s\n' "$rs_flags" | sed -n 's/.*--web-port \([0-9]*\).*/\1/p')
	rs_iface=$(printf '%s\n' "$rs_flags" | sed -n 's/.*--iface \([^ ]*\).*/\1/p')
	rs_slot=$(printf '%s\n' "$rs_flags" | sed -n 's/.*--instance-slot \([0-9]*\).*/\1/p')
	rs_bind=$(printf '%s\n' "$rs_flags" | sed -n 's/.*--web-bind \([^ ]*\).*/\1/p')
	rs_secret=$(inst_env "$in_state" CSQTT_WEB_SECRET_PATH)
	# its own ports, interface and slot must be free again: its clients come back on them
	for rs_p in $rs_udp $rs_web; do
		[ -z "$(ss -H -ltun "( sport = :$rs_p )" 2>/dev/null)" ] || run_result failed-cleaned "port $rs_p is held now" "free it, or remove the kept store"
	done
	! ip link show "$rs_iface" >/dev/null 2>&1 || run_result failed-cleaned "interface $rs_iface exists now" "check again"
	for rs_u in /etc/systemd/system/sanity-csqtt-*.service /var/lib/sanityprotocol/csqtt/*/docker; do
		# (an instance of ours in Docker, or a kept store of one, by its docker file: P2 B13a)
		[ "$rs_u" != "$in_state/docker" ] || continue
		if [ -f "$rs_u" ] && sed -n 's/^ExecStart=//p' "$rs_u" | grep -qE -- "--instance-slot $rs_slot( |\$)"; then
			rs_who=${rs_u##*/}
			[ "$rs_who" != docker ] || rs_who=sanity-csqtt-$(basename "${rs_u%/docker}")
			run_result failed-cleaned "slot $rs_slot is $rs_who's now" "remove the kept store, or that instance"
		fi
	done
	cr_id=$rs_id cr_state=$in_state cr_unit=$in_unit cr_iface=$rs_iface
	cr_open="$rs_udp/udp"
	[ "$rs_bind" = 127.0.0.1 ] || cr_open="$cr_open $rs_web/tcp"
	MF=$in_state/manifest.json MF_ID=$rs_id MF_OBJECTS='' MF_HOST=''
	# what this run makes (never the state dir: it holds the clients) goes if it fails, and the manifest names the kept store
	# alone again, as the removal left it (B11a review F5)
	action_cleanup() {
		mf_undo
		[ ! -e "$RUN_DIR/switching" ] || cr_server_undo
		host_undo
		rmdir /opt/sanityprotocol/csqtt /opt/sanityprotocol 2>/dev/null
		MF_OBJECTS="dir $in_state" MF_HOST=''
		mf_write
		return 0
	}
	cr_fail() { action_cleanup; run_result failed-cleaned "$1" "${2:-check again}"; }

	run_phase stage 10
	[ "$(sha256sum "$RUN_DIR/core" 2>/dev/null | cut -d' ' -f1)" = "$rs_sha" ] || cr_fail "the uploaded core is damaged" "try again"
	# shellcheck disable=SC2086 # the flags it ran with
	rs_said=$("$RUN_DIR/core" $rs_flags --version 2>&1 | head -n 1)
	[ "$rs_said" = "csqtt $rs_version CSQTT-WIRE-3" ] || cr_fail "the core does not run here with this instance's flags: $rs_said" "check the box's architecture"
	host_iptables
	cr_fw=$(host_fw_mode)
	run_safe_point
	rs_release=$in_root/releases/$rs_version-$(printf '%.8s' "$rs_sha")
	mf_add dir "$in_root"
	mkdir -p "$rs_release" && chmod 755 /opt/sanityprotocol /opt/sanityprotocol/csqtt "$in_root" "$in_root/releases" "$rs_release" &&
		install -m 755 "$RUN_DIR/core" "$rs_release/csqtt-server" && ln -sfn "$rs_release" "$in_root/current" || cr_fail "cannot install the core"
	rs_open_unit=''
	[ "$cr_fw" != iptables ] || rs_open_unit=$(inst_open_sh "$rs_id" "$in_state" "$cr_open") || cr_fail "cannot write $in_state/open.sh"
	if [ $rs_docker = 1 ]; then
		# Docker as a create makes it ready, the root filesystem's image and today's image from it, its core asked to run in it;
		# the docker file names them, its flags those it ran with
		command -v docker >/dev/null 2>&1 || cr_fail "Docker is not installed here" "install Docker, or remove the kept store"
		dk_daemon
		rs_rootfs=sanity-csqtt-rootfs:$(printf '%.8s' "$(plan rootfs_sha256)")
		rs_image=sanity-csqtt:$rs_version-$(printf '%.8s' "$rs_sha")
		dk_rootfs "$(plan rootfs_sha256)" "$(plan rootfs_url)"
		dk_image "$rs_rootfs" "$rs_image" "$RUN_DIR/core"
		# shellcheck disable=SC2086 # the flags it ran with
		rs_said=$(docker run --rm --network none "$rs_image" /opt/sanityprotocol/csqtt-server $rs_flags --version 2>&1 | head -n 1)
		[ "$rs_said" = "csqtt $rs_version CSQTT-WIRE-3" ] || cr_fail "the core does not run in its image: $rs_said" "check the box's architecture"
		# (its own object: a failed or cancelled restore takes it, never leaving it beside unit.saved: B13c review F5)
		mf_add file "$in_state/docker"
		printf '# docker %s\n# image %s\n# rootfs %s\nExecStart=/opt/sanityprotocol/csqtt-server %s\n' "$rs_ctr" "$rs_image" "$rs_rootfs" \
			"$rs_flags" >"$in_state/docker" || cr_fail "cannot write $in_state/docker"
	else
		mf_add unit "/etc/systemd/system/$in_unit"
		inst_unit_text "$rs_id" "$in_root" "$in_state" "$rs_flags" "$rs_open_unit" >"/etc/systemd/system/$in_unit" || cr_fail "cannot write $in_unit"
		systemctl daemon-reload
	fi
	run_safe_point
	run_phase host 45
	# (in Docker the server's own tuning too, as its create's: P2 B13a review F5)
	if [ $rs_docker = 1 ]; then host_sysctl tuning; else host_sysctl; fi
	host_firewall
	[ "$cr_fw" != iptables ] || host_add iptables-input "ports=$(printf '%s' "$cr_open" | tr ' ' ,)"
	run_safe_point
	run_phase switch 60
	for cr_k in $CR_SERVER_SYSCTLS; do printf '%s=%s\n' "$cr_k" "$(sysctl -n "$cr_k" 2>/dev/null)"; done >"$RUN_DIR/sysctl-before"
	! command -v nft >/dev/null 2>&1 || nft list tables >"$RUN_DIR/nft-before" 2>/dev/null
	awk '{print $1}' /proc/modules >"$RUN_DIR/modules-before" 2>/dev/null
	: >"$RUN_DIR/switching"
	if [ $rs_docker = 1 ]; then
		mf_add container "$rs_ctr"
		# shellcheck disable=SC2086 # the flags it ran with
		run_cmd dk_run "$rs_ctr" "$rs_image" "$in_state" "$cr_fw" $rs_flags || cr_fail "Docker would not run $rs_ctr: $(dk_lines "$rs_ctr")"
		run_phase verify 80
		rs_why=$(inst_verify "docker:$rs_ctr" "$rs_udp" "$rs_web" "$rs_iface" "$rs_secret")
		[ -z "$rs_why" ] || rs_why="$rs_why: $(dk_lines "$rs_ctr")"
	else
		# enabled, then started on its own: systemd 255's `enable --now` exits 0 for a start that failed (the zoo, Ubuntu 24.04)
		run_cmd systemctl enable "$in_unit" || cr_fail "$in_unit could not be enabled"
		run_cmd systemctl start "$in_unit" || cr_fail "$in_unit did not start: $(inst_start_lines "$in_unit")"
		run_phase verify 80
		rs_why=$(inst_verify "$in_unit" "$rs_udp" "$rs_web" "$rs_iface" "$rs_secret")
	fi
	[ -z "$rs_why" ] || cr_fail "$rs_why"
	# the manifest names the state dir again, first, as a create's does
	MF_OBJECTS="dir $in_state$(printf '\n%s' "$MF_OBJECTS")"
	mf_write
	rm -f "$in_state/unit.saved"
	run_phase "done" 100
	run_result ok "" "read the connection back"
}

# «Перезапустить» and «Запустить»: the unit (re)started and verified
action_restart() {
	rt_id=$(plan id)
	inst_paths "$rt_id"
	if [ -f "$in_state/docker" ] && [ ! -f "$in_state/unit.saved" ]; then
		# a Docker instance's container (P2 B13a): restarted, or started when stopped
		rt_ctr=$(sed -n 's/^# docker //p' "$in_state/docker") rt_check=docker:$(sed -n 's/^# docker //p' "$in_state/docker")
		docker container inspect "$rt_ctr" >/dev/null 2>&1 || run_result failed-cleaned "sqc-$rt_id's container is gone" "check again"
		rt_flags=$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "$in_state/docker" | head -n 1)
		run_phase switch 50
		: >"$RUN_DIR/switching"
		# on the build `current` names: a container of another image (an update cut between its flip and its container) is
		# made again, never restarted on the build before (B13c review F6)
		rt_img=sanity-csqtt:$(basename "$(readlink "$in_root/current")")
		if [ "$(docker inspect -f '{{.Config.Image}}' "$rt_ctr" 2>/dev/null)" != "$rt_img" ]; then
			sed "s|^# image .*|# image $rt_img|" "$in_state/docker" >"$in_state/docker.new" && mv -f "$in_state/docker.new" "$in_state/docker" ||
				run_result failed-cleaned "cannot write $in_state/docker" "check the box's disk"
			docker rm -f "$rt_ctr" >/dev/null 2>&1
			rt_fw=none
			[ ! -f "$in_state/open.sh" ] || rt_fw=iptables
			# shellcheck disable=SC2086 # the instance's own flags
			run_cmd dk_run "$rt_ctr" "$rt_img" "$in_state" "$rt_fw" $rt_flags ||
				run_result failed-not-rolled-back "$rt_ctr did not start: $(dk_lines "$rt_ctr")" "read its journal («Журнал»)"
		else
			run_cmd docker restart -t 15 "$rt_ctr" ||
				run_result failed-not-rolled-back "$rt_ctr did not start: $(dk_lines "$rt_ctr")" "read its journal («Журнал»)"
		fi
	else
		[ -f "/etc/systemd/system/$in_unit" ] || run_result failed-cleaned "sqc-$rt_id is not installed here" "check again"
		rt_check=$in_unit
		rt_flags=$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "/etc/systemd/system/$in_unit" | head -n 1)
		run_phase switch 50
		: >"$RUN_DIR/switching"
		run_cmd systemctl enable "$in_unit"
		run_cmd systemctl restart "$in_unit" ||
			run_result failed-not-rolled-back "$in_unit did not start: $(inst_start_lines "$in_unit")" "read its journal («Журнал»)"
	fi
	run_phase verify 80
	rt_why=$(inst_verify "$rt_check" "$(printf '%s\n' "$rt_flags" | sed -n 's/.*--listen [^ ]*:\([0-9]*\).*/\1/p')" \
		"$(printf '%s\n' "$rt_flags" | sed -n 's/.*--web-port \([0-9]*\).*/\1/p')" "$(printf '%s\n' "$rt_flags" | sed -n 's/.*--iface \([^ ]*\).*/\1/p')" \
		"$(inst_env "/var/lib/sanityprotocol/csqtt/$rt_id" CSQTT_WEB_SECRET_PATH)")
	[ -z "$rt_why" ] || run_result failed-not-rolled-back "$rt_why" "read its journal («Журнал»)"
	# (in Docker no unit's ExecStartPost drops an update's record: a verified restart on the build `current` names does,
	# B13c review F2)
	[ "${rt_ctr:-}" = "" ] || rm -f "$in_state/update.pending"
	run_phase "done" 100
	run_result ok "" ""
}

# ── remove-foreign: a stock systemd install (P2-PLAN §3 B11b; APP-PLAN §6.10; M-116, M-120, M-126, M-131) ────────────────
# Every object the plan names is checked against its stock role's own namespace before anything changes (server-footprints.md
# §1.6, §1.7), so a plan cannot widen it; a path is never followed through a link: a symlinked config dir loses its link,
# never its target. Rules go by their tag, each line as iptables prints it; the untagged FORWARD RELATED,ESTABLISHED rule and
# the LE lineage only when the plan says this is the last stock install.
fr_ok() {  # fr_ok <role> <kind> <name>
	case "$1 $2 $3" in
	"main unit csqtt.service" | "main unit csqtt-docker-prereq.service" | "main unit csqtt-letsencrypt.service" | \
		"main unit csqtt-letsencrypt.timer") ;;
	"side unit csqtt25.service" | "side unit csqtt25-docker-prereq.service" | "side unit csqtt25-letsencrypt.service" | \
		"side unit csqtt25-letsencrypt.timer") ;;
	"main path /usr/local/bin/csqtt" | "main path /usr/local/lib/csqtt" | "main path /usr/local/lib/csqtt-letsencrypt-renew.sh" | \
		"main path /opt/csqtt-certbot" | "main path /run/csqtt" | "main path /etc/sysctl.d/99-csqtt.conf" | \
		"main path /etc/sysctl.d/99-csqtt-udp-buffers.conf" | "main path /var/log/csqtt-install.log") ;;
	"side path /usr/local/lib/csqtt25" | "side path /opt/csqtt25-certbot" | "side path /run/csqtt25" | \
		"side path /etc/sysctl.d/99-csqtt25.conf" | "side path /etc/sysctl.d/99-csqtt25-udp-buffers.conf" | \
		"side path /var/log/csqtt25-install.log") ;;
	"main store /etc/csqtt" | "side store /etc/csqtt25") ;;
	"main tag CSQTT_MANAGED" | "main tag CSQTT_TPROXY" | "side tag CSQTT25_MANAGED" | "side tag CSQTT25_TPROXY") ;;
	"main table 30001" | "main table 1066" | "side table 30002") ;;
	# a stock Docker install's (P2 B13b): its container by its role's name, its repository's images (a plain tag)
	"main container csqtt" | "side container csqtt25") ;;
	"main image csqtt:"* | "side image csqtt25:"*)
		case ${3#*:} in '' | *[!A-Za-z0-9._-]*) return 1 ;; esac ;;
	"main lineage "* | "side lineage "*)
		case $3 in csqtt-ip-*[!0-9-]* | csqtt-ip-) return 1 ;; csqtt-ip-*) ;; *) return 1 ;; esac ;;
	*) return 1 ;;
	esac
}

# fr_stop_unit <unit>: a stock unit disabled and stopped. Its Docker prereq is RequiredBy=docker.service: its links go first, by
# hand too as the stock deploy's own removal takes them (one its [Install] no longer names would stay, and Docker would
# require a unit that is gone at the next boot: B14), and it is stopped only once Docker no longer requires it (a stop through
# that link would stop Docker, with every container on the box: P2 B13b)
fr_stop_unit() {
	case $1 in
	*-docker-prereq.service)
		run_cmd systemctl disable "$1"
		rm -f "/etc/systemd/system/docker.service.requires/$1" "/etc/systemd/system/docker.service.wants/$1"
		systemctl daemon-reload
		case " $(systemctl show -p Requires -p BindsTo --value docker.service 2>/dev/null | tr '\n' ' ') " in
		*" $1 "*) ;;
		*) run_cmd systemctl stop "$1" ;;
		esac
		;;
	*) run_cmd systemctl disable --now "$1" ;;
	esac
}

# fr_stop_containers <key prefix>: a stock Docker install's container, its restart policy off first (nothing starts it again),
# then stopped gracefully (its server takes its own rules) (P2 B13b); 1: one would not stop
fr_stop_containers() {
	for fs_c in $(plan "$1containers" | tr , ' '); do
		docker container inspect "$fs_c" >/dev/null 2>&1 || continue
		run_cmd docker update --restart=no "$fs_c" || return 1
		run_cmd docker stop -t 15 "$fs_c" || return 1
	done
	return 0
}

fr_rm() {  # fr_rm <path>: a link is unlinked; a directory goes with what is in it (rm never follows a link inside it)
	if [ -L "$1" ]; then rm -f "$1"
	elif [ -d "$1" ]; then rm -rf "$1"
	elif [ -e "$1" ]; then rm -f "$1"
	fi
}

# fr_sweep_tag <tag>: its rules, v4 and v6, every table, as iptables prints them; never an INPUT rule that opens a port sshd
# listens on (2.0.0's deploy tagged its SSH opening: on a box whose INPUT drops, taking it would end every new SSH login)
fr_sweep_tag() {
	fs_ssh=$(ss -Hltnp 2>/dev/null | awk '/"sshd"/ {n = split($4, a, ":"); print a[n]}' | sort -u | tr '\n' '|')
	for fs_ipt in iptables ip6tables; do
		command -v $fs_ipt >/dev/null 2>&1 || continue
		for fs_t in filter nat mangle raw; do
			$fs_ipt -w 5 -t $fs_t -S 2>/dev/null | grep '^-A ' | grep -E -- "--comment \"?$1([:\" ]|\$)" |
				{ if [ -n "$fs_ssh" ]; then grep -vE -- "^-A INPUT .*--dport (${fs_ssh%|})( |\$)"; else cat; fi; } | sed 's/^-A /-D /' |
				while read -r fs_rule; do eval "$fs_ipt -w 5 -t $fs_t $fs_rule" 2>/dev/null; done
		done
	done
	return 0
}

# fr_validate <role> <key prefix>: every object a plan names (under its keys <prefix>units, …), in the role's namespace,
# checked before anything changes (the move's are under move_)
fr_validate() {
	case $1 in main | side) ;; *) run_result failed-cleaned "bad role $1" "update the app" ;; esac
	for fr_kind in unit path tag table container image; do
		for fr_v in $(plan "$2${fr_kind}s" | tr , ' '); do
			fr_ok "$1" "$fr_kind" "$fr_v" || run_result failed-cleaned "$fr_v is not the stock $1 install's" "update the app"
		done
	done
	for fr_v in $(plan "$2lineage" | tr , ' '); do
		fr_ok "$1" lineage "$fr_v" || run_result failed-cleaned "$fr_v is not a stock certificate" "update the app"
	done
	fr_v=$(plan "$2store")
	[ -z "$fr_v" ] || fr_ok "$1" store "$fr_v" || run_result failed-cleaned "$fr_v is not the stock $1 store" "update the app"
}

# fr_outside <role>: the pids of its binary still running once its units are stopped (M-120: a server that outlived its unit
# keeps its port and TUN, and nothing of it is deleted under it); empty when none
fr_outside() {
	case $1 in main) fr_bin=/usr/local/bin/csqtt ;; *) fr_bin=/usr/local/lib/csqtt25/csqtt25 ;; esac
	fr_live=''
	for fr_pid in $(pgrep -x "${fr_bin##*/}" 2>/dev/null); do
		[ "$(readlink "/proc/$fr_pid/exe" 2>/dev/null)" != "$fr_bin" ] || fr_live="$fr_live $fr_pid"
	done
	printf '%s' "$fr_live"
}

# fr_keep_floors <key prefix>: an instance of ours on the box leans on the stock sysctl files this removal deletes (our create
# records a floor only where the live value was below it, and the stock 2.5 files set exactly our floors: B11b review F1).
# Each floor they set that our drop-in lacks goes into it at its live value, recorded so (the value it was is the value it
# is): it survives the next boot, and a later last removal of ours writes nothing back
fr_keep_floors() {
	# (one in Docker by its docker file, as the drop-in's rule counts it: B13b review F1)
	for fk_u in /etc/systemd/system/sanity-csqtt-*.service /var/lib/sanityprotocol/csqtt/*/docker; do [ -f "$fk_u" ] && break; fk_u=''; done
	[ -n "$fk_u" ] || return 0
	for fk_p in $(plan "$1paths" | tr , ' '); do
		case $fk_p in /etc/sysctl.d/*.conf) [ -f "$fk_p" ] || continue ;; *) continue ;; esac
		for fk_k in $(sed -n 's/^[[:space:]]*\([a-z0-9_.]*\)[[:space:]]*=.*/\1/p' "$fk_p"); do
			printf '%s\n' "$HOST_FLOORS" | awk '{print $1}' | grep -qxF "$fk_k" || continue
			! grep -q "^$fk_k = " "$HOST_DROPIN" 2>/dev/null || continue
			fk_v=$(sysctl -n "$fk_k" 2>/dev/null | tr -s '\t ' '  ')
			[ -n "$fk_v" ] || continue
			[ -f "$HOST_DROPIN" ] ||
				printf '# SanityProtocol csqtt (APP-PLAN §6.9): forwarding and raise-only floors; each raised from the value above it\n' >"$HOST_DROPIN"
			printf '# %s was %s\n%s = %s\n' "$fk_k" "$fk_v" "$fk_k" "$fk_v" >>"$HOST_DROPIN"
		done
	done
	return 0
}

# fr_take <key prefix> <purge>: the stock install's objects (after fr_validate, its units stopped and disabled, nothing of it
# left running): its unit files, its tagged rules, its routing, its paths (the floors its sysctl files set kept for an instance
# of ours first), what all stock installs share when the plan says this is the last, its store only with <purge>
fr_take() {
	# only the stock deploy's own unit files; a unit packaged elsewhere is not its to delete
	for fr_u in $(plan "$1units" | tr , ' '); do rm -f "/etc/systemd/system/$fr_u"; done
	systemctl daemon-reload
	for fr_u in $(plan "$1units" | tr , ' '); do systemctl reset-failed "$fr_u" >/dev/null 2>&1; done
	# a stock Docker install's container and images (an image another container still uses is Docker's to keep) (P2 B13b)
	for fr_v in $(plan "$1containers" | tr , ' '); do docker rm -f "$fr_v" >/dev/null 2>&1; rm -f "$MV_STOCK$fr_v"; done
	for fr_v in $(plan "$1images" | tr , ' '); do docker image rm "$fr_v" >/dev/null 2>&1; done
	for fr_v in $(plan "$1tags" | tr , ' '); do fr_sweep_tag "$fr_v"; done
	for fr_v in $(plan "$1tables" | tr , ' '); do
		while ip rule del table "$fr_v" 2>/dev/null; do :; done
		ip route flush table "$fr_v" 2>/dev/null
	done
	fr_keep_floors "$1"
	for fr_v in $(plan "$1paths" | tr , ' '); do fr_rm "$fr_v"; done
	if [ "$(plan "$1untagged")" = 1 ]; then
		iptables -w 5 -D FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
		ip6tables -w 5 -D FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
	fi
	for fr_v in $(plan "$1lineage" | tr , ' '); do
		fr_rm "/etc/letsencrypt/live/$fr_v"
		fr_rm "/etc/letsencrypt/archive/$fr_v"
		fr_rm "/etc/letsencrypt/renewal/$fr_v.conf"
	done
	fr_v=$(plan "$1store")
	[ "$2" != 1 ] || [ -z "$fr_v" ] || fr_rm "$fr_v"
	return 0
}

action_remove_foreign() {
	fr_role=$(plan role) fr_purge=$(plan purge) fr_store=$(plan store)
	fr_validate "$fr_role" ""
	run_phase stop 20
	: >"$RUN_DIR/switching"
	for fr_u in $(plan units | tr , ' '); do fr_stop_unit "$fr_u"; done
	fr_stop_containers "" || run_result failed-not-rolled-back "the stock $fr_role container would not stop" \
		"stop it, then remove again: its restart policy is off and its units disabled, nothing else changed"
	fr_live=$(fr_outside "$fr_role")
	[ -z "$fr_live" ] || run_result failed-not-rolled-back "the stock $fr_role binary still runs outside its unit (pid$fr_live)" \
		"stop it, then remove again: its units are disabled, nothing else changed"
	run_phase remove 50
	fr_take "" "$fr_purge"
	# our drop-in, kept for a csqtt server that ran beside our last instance (B11a verify N1), goes once none runs
	host_dropin_drop
	run_phase "done" 100
	if [ "$fr_purge" = 1 ]; then run_result ok "" "the stock $fr_role install is gone, its clients with it"; fi
	run_result ok "" "the stock $fr_role install is gone; its store stays in ${fr_store:-its place}"
}

# ── le: the panel's certificate from Let's Encrypt (P2 B14; P2-PLAN B14 "as designed"; M-119, M-135) ───────────────────────
# An IP certificate on the short-lived profile for an instance's panel, in a run of its own after a create, a move or a restore
# ended ok (a cut here never undoes a verified instance), and again at will. Our ACME state is ours alone: the distro's
# certbot.timer renews /etc/letsencrypt with its own config and hooks, and a stock removal takes csqtt-ip-* there. One lineage
# per IP for all our instances on it; our timer renews it, and the server reloads it on SIGUSR1: no client is cut.
LE_DIR=/var/lib/sanityprotocol/letsencrypt
LE_VENV=/opt/sanityprotocol/certbot
LE_HOOK=$LE_VENV/le-hook.sh
LE_CERTBOT=5.10.0

# the hook our issuance and our timer share (it runs with no run.sh beside it): TCP 80 opened for a challenge where the box's
# INPUT is closed and closed again after (only what was closed), a lineage copied into every live instance that uses it with its
# server told to reload, and the renewal itself, isolated from the host's certbot setup (M-135)
le_hook_text() {
	cat <<'EOF'
#!/bin/sh
# SanityProtocol csqtt (P2 B14): our Let's Encrypt certificates, renewed by sanity-csqtt-le.timer
LE_DIR=${LE_DIR:-/var/lib/sanityprotocol/letsencrypt}
SQC_DIR=${SQC_DIR:-/var/lib/sanityprotocol/csqtt}
LE_VENV=${LE_VENV:-/opt/sanityprotocol/certbot}
LE_HOST_INI=${LE_HOST_INI:-/etc/letsencrypt/cli.ini}
[ ! -f "$LE_DIR/acme-ca.pem" ] || export REQUESTS_CA_BUNDLE="$LE_DIR/acme-ca.pem"
fw() {
	if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | head -n 1 | grep -q 'Status: active'; then echo ufw
	elif [ "$(systemctl is-active firewalld 2>/dev/null)" = active ]; then echo firewalld
	elif iptables -w 5 -S INPUT 2>/dev/null | awk '$1 == "-P" && ($3 == "DROP" || $3 == "REJECT") {d = 1}
		$1 == "-A" && $3 == "-j" && ($4 == "DROP" || $4 == "REJECT") {d = 1} END {exit !d}'; then echo iptables
	else echo none; fi
}
case ${1:-} in
open)
	# (the rule itself is asked, never the note alone: a reboot drops a runtime rule and keeps the note, B14 review F5)
	case $(fw) in
	ufw) ufw status 2>/dev/null | grep -qE '^80/tcp[[:space:]]+ALLOW' ||
		{ ufw allow 80/tcp comment sanity-csqtt-le >/dev/null && echo ufw >"$LE_DIR/port80"; } ;;
	firewalld) z=$(firewall-cmd --get-default-zone 2>/dev/null)
		firewall-cmd -q --zone="$z" --query-port=80/tcp 2>/dev/null ||
			{ firewall-cmd -q --zone="$z" --add-port=80/tcp && echo "firewalld $z" >"$LE_DIR/port80"; } ;;
	iptables) iptables -w 5 -C INPUT -p tcp --dport 80 -m comment --comment SANITY_CSQTT_LE -j ACCEPT 2>/dev/null ||
		{ iptables -w 5 -I INPUT -p tcp --dport 80 -m comment --comment SANITY_CSQTT_LE -j ACCEPT && echo iptables >"$LE_DIR/port80"; } ;;
	esac
	exit 0 ;;
close)
	[ -f "$LE_DIR/port80" ] || exit 0
	read -r m z <"$LE_DIR/port80"
	case $m in
	ufw) ufw delete allow 80/tcp >/dev/null 2>&1 ;;
	firewalld) firewall-cmd -q --zone="$z" --remove-port=80/tcp 2>/dev/null ;;
	iptables) while iptables -w 5 -D INPUT -p tcp --dport 80 -m comment --comment SANITY_CSQTT_LE -j ACCEPT 2>/dev/null; do :; done ;;
	esac
	rm -f "$LE_DIR/port80"
	exit 0 ;;
deploy)
	n=${RENEWED_LINEAGE:-${2:-}}
	n=${n##*/}
	[ -n "$n" ] && [ -s "$LE_DIR/live/$n/fullchain.pem" ] && [ -s "$LE_DIR/live/$n/privkey.pem" ] || exit 1
	for d in "$SQC_DIR"/*/; do
		d=${d%/}
		[ "$(cat "$d/le" 2>/dev/null)" = "$n" ] && [ ! -f "$d/unit.saved" ] || continue
		{ cp "$LE_DIR/live/$n/fullchain.pem" "$d/web_cert.pem.new" && (umask 077 && cat "$LE_DIR/live/$n/privkey.pem" >"$d/web_key.pem.new") &&
			mv -f "$d/web_key.pem.new" "$d/web_key.pem" && mv -f "$d/web_cert.pem.new" "$d/web_cert.pem"; } ||
			{ logger -t sanity-csqtt-le "cannot install $n into $d"; continue; }
		i=${d##*/}
		if [ -f "$d/docker" ]; then
			# (a stopped Docker is not started through its socket for this: its container is down with it, B13c review F4)
			systemctl is-active --quiet docker.service || continue
			docker kill -s USR1 "sanity-csqtt-$i" >/dev/null 2>&1 || logger -t sanity-csqtt-le "cannot signal sanity-csqtt-$i"
		else
			# (its main process by its pid: systemctl kill's --kill-whom= came in systemd 252, B14 review F1; a stopped server
			# reads the files at its next start)
			p=$(systemctl show -p MainPID --value "sanity-csqtt-$i.service" 2>/dev/null)
			[ "${p:-0}" -gt 0 ] 2>/dev/null || continue
			kill -USR1 "$p" || logger -t sanity-csqtt-le "cannot signal sanity-csqtt-$i.service"
		fi
	done
	exit 0 ;;
certbot)
	# our certbot alone (M-135): no host config and no directory hooks; configargparse opens /etc/letsencrypt/cli.ini and
	# $XDG_CONFIG_HOME's whatever --config says, so the first is hidden in a mount namespace of its own (the host's certbot still
	# reads it) and the second is ours (B14 review F2)
	shift
	export XDG_CONFIG_HOME="$LE_DIR"
	set -- "$LE_VENV/bin/certbot" --config /dev/null --no-directory-hooks --config-dir "$LE_DIR" \
		--work-dir "$LE_DIR/work" --logs-dir "$LE_DIR/logs" "$@"
	# (where root may make no mount namespace, a restricted LXC or OpenVZ VPS, or the bind fails, the host's cli.ini applies as it
	# always did: B14 verify)
	[ -e "$LE_HOST_INI" ] && unshare -m true 2>/dev/null || exec "$@"
	exec unshare -m sh -c 'mount --bind /dev/null "$0" 2>/dev/null; exec "$@"' "$LE_HOST_INI" "$@" ;;
renew)
	exec "$0" certbot renew --non-interactive --pre-hook "$0 open" --post-hook "$0 close" --deploy-hook "$0 deploy" ;;
esac
exit 2
EOF
}

# le_venv: our certbot in its venv (one per box), pinned when it is made; one there that works with --ip-address stays (the venv
# holds the renewal's hook too: made again only when it does not work, never under the other instances' renewals). Its cause in
# le_why when it cannot be had (called as it is, never in a subshell: a cancel in its lock wait ends the run, B14 review F3)
le_venv() {
	le_why=''
	[ ! -x "$LE_VENV/bin/certbot" ] || ! "$LE_VENV/bin/certbot" certonly --help all 2>&1 | grep -q -- --ip-address || return 0
	command -v python3 >/dev/null 2>&1 || { le_why="python3 is not installed"; return 1; }
	python3 -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2>/dev/null ||
		{ le_why="$(python3 -V 2>&1) is older than 3.10, which certbot $LE_CERTBOT needs"; return 1; }
	if ! python3 -c 'import ensurepip, venv' 2>/dev/null; then
		# python3-venv by the package manager, kept as B9's iptables is (a package installed stays)
		if command -v apt-get >/dev/null 2>&1; then
			host_lock_wait || { le_why="the package lock stays held ($(apt_lock_holder))"; return 1; }
			run_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends python3-venv </dev/null ||
				{ run_cmd env DEBIAN_FRONTEND=noninteractive apt-get update -q </dev/null &&
					run_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends python3-venv </dev/null; }
		elif command -v dnf >/dev/null 2>&1; then
			run_cmd dnf install -y -q python3 </dev/null
		fi
		python3 -c 'import ensurepip, venv' 2>/dev/null || { le_why="python3-venv could not be installed"; return 1; }
	fi
	# (a venv is not relocatable: made in its place, removed whole when it fails)
	rm -rf "$LE_VENV"
	python3 -m venv "$LE_VENV" >"$RUN_DIR/pip.log" 2>&1 || { rm -rf "$LE_VENV"; le_why="python3 could not make a venv"; return 1; }
	# (a source the plan names: the zoo's wheelhouse; pip ignores a plain-HTTP one unless its host is named trusted)
	le_src='' le_links=$(plan pip_find_links)
	if [ -n "$le_links" ]; then
		le_src="--no-index --find-links $le_links"
		case $le_links in http://*) le_host=${le_links#http://} && le_src="$le_src --trusted-host ${le_host%%[:/]*}" ;; esac
	fi
	# shellcheck disable=SC2086 # the source's words
	if ! timeout 600 "$LE_VENV/bin/pip" install --disable-pip-version-check --no-cache-dir --timeout 60 $le_src \
		"certbot==$LE_CERTBOT" >>"$RUN_DIR/pip.log" 2>&1; then
		le_why="certbot $LE_CERTBOT could not be installed: $(tail -n 2 "$RUN_DIR/pip.log" | tr '\n' ' ' | cut -c1-240)"
		rm -rf "$LE_VENV"
		return 1
	fi
	"$LE_VENV/bin/certbot" certonly --help all 2>&1 | grep -q -- --ip-address ||
		{ rm -rf "$LE_VENV"; le_why="certbot $LE_CERTBOT has no --ip-address"; return 1; }
}

# le_drop <state dir>: the instance's lineage reference gone; with the last live one our timer, its units, the venv and the
# ACME state go (as the drop-in's rule: nothing of ours stays behind for nobody). A kept store drops it too: «Восстановить»
# gets its certificate again
le_drop() {
	rm -f "$1/le"
	for ld_f in /var/lib/sanityprotocol/csqtt/*/le; do [ -f "$ld_f" ] && return 0; done
	[ -e /etc/systemd/system/sanity-csqtt-le.timer ] || [ -e "$LE_VENV" ] || [ -e "$LE_DIR" ] || return 0
	systemctl disable --now sanity-csqtt-le.timer >/dev/null 2>&1
	systemctl stop sanity-csqtt-le.service >/dev/null 2>&1
	[ ! -x "$LE_HOOK" ] || "$LE_HOOK" close
	rm -f /etc/systemd/system/sanity-csqtt-le.timer /etc/systemd/system/sanity-csqtt-le.service
	systemctl daemon-reload
	systemctl reset-failed sanity-csqtt-le.service sanity-csqtt-le.timer >/dev/null 2>&1
	rm -rf "$LE_VENV" "$LE_DIR"
	return 0
}

# le_keep <cause> <next>: the run ends ok with the panel on a certificate of its own. A short-lived one nothing here renews (a
# moved store's Let's Encrypt one, a restored one's) gives way to a self-signed one made as the server makes its own, so the panel
# never serves one that expires in days; a certificate of the user's, long-lived, stays (B14 review F4)
le_keep() {
	if [ ! -f "$in_state/le" ] && command -v openssl >/dev/null 2>&1; then
		lk_from=$(openssl x509 -noout -startdate -in "$in_state/web_cert.pem" 2>/dev/null | sed 's/^notBefore=//')
		lk_to=$(openssl x509 -noout -enddate -in "$in_state/web_cert.pem" 2>/dev/null | sed 's/^notAfter=//')
		lk_days=$(( ($(date -d "$lk_to" +%s 2>/dev/null || echo 0) - $(date -d "$lk_from" +%s 2>/dev/null || echo 0)) / 86400 ))
		if [ -n "$lk_from" ] && [ "$lk_days" -lt 10 ]; then
			lk_san="subjectAltName=DNS:localhost,IP:127.0.0.1"
			case $le_ip in *[!0-9.]* | '') ;; *) lk_san="$lk_san,IP:$le_ip" ;; esac
			(umask 077 && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 -subj "/CN=rcgen self signed cert" \
				-addext "$lk_san" -keyout "$in_state/web_key.pem.new" -out "$in_state/web_cert.pem.new" >/dev/null 2>&1) &&
				chmod 644 "$in_state/web_cert.pem.new" && mv -f "$in_state/web_key.pem.new" "$in_state/web_key.pem" &&
				mv -f "$in_state/web_cert.pem.new" "$in_state/web_cert.pem" && le_signal
		fi
	fi
	run_result ok "the panel keeps its own certificate: $1" "$2"
}

# le_signal: the instance's server told to read its certificate again (SIGUSR1: no client is cut)
le_signal() {
	if [ -f "$in_state/docker" ]; then
		# (a stopped Docker is not started through its socket for this, as the hook's: B14 verify)
		systemctl is-active --quiet docker.service || return 0
		docker kill -s USR1 "sanity-csqtt-$le_id" >/dev/null 2>&1
	else
		ls_p=$(systemctl show -p MainPID --value "$in_unit" 2>/dev/null)
		[ "${ls_p:-0}" -le 0 ] 2>/dev/null || kill -USR1 "$ls_p" 2>/dev/null
	fi
	return 0
}

action_le() {
	le_id=$(plan id) le_ip=$(plan public_ip) le_url=$(plan acme_url)
	inst_paths "$le_id"
	le_def=/etc/systemd/system/$in_unit
	[ -f "$le_def" ] || le_def=$in_state/docker
	[ -f "$le_def" ] && [ -f "$in_state/env" ] && [ ! -f "$in_state/unit.saved" ] ||
		run_result failed-cleaned "sqc-$le_id is not installed here" "check again"
	# a panel bound to loopback is reached through the tunnel alone: no certificate to get for it
	! grep -q -- '--web-bind 127\.0\.0\.1' "$le_def" || run_result ok "the panel keeps its own certificate: the panel listens on 127.0.0.1 only" ""
	case $le_ip in
	'' | *[!0-9.]* | *..* | .* | *.) le_keep "no public IPv4 address to certify" "" ;;
	10.* | 127.* | 169.254.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].* | 100.6[4-9].* | 100.[7-9][0-9].* | 100.1[01][0-9].* | 100.12[0-7].*)
		# (a private one only against a directory the plan names: the zoo's Pebble)
		[ -n "$le_url" ] || le_keep "$le_ip is no public address (Let's Encrypt certifies public ones)" "" ;;
	esac
	run_phase prepare 10
	[ -z "$(ss -Hltn 'sport = :80' 2>/dev/null)" ] ||
		le_keep "TCP 80 is taken (Let's Encrypt checks the address there)" "free TCP 80, then get the certificate again"
	le_venv || le_keep "$le_why" "get the certificate again later"
	mkdir -p "$LE_DIR" && chmod 700 "$LE_DIR" && le_hook_text >"$LE_HOOK.new" && chmod 755 "$LE_HOOK.new" && mv -f "$LE_HOOK.new" "$LE_HOOK" ||
		run_result failed-cleaned "cannot write $LE_HOOK" "check the box's disk"
	if [ -n "$(plan acme_ca)" ]; then plan acme_ca | base64 -d >"$LE_DIR/acme-ca.pem" || run_result failed-cleaned "the plan's ACME CA is damaged" "update the app"
	else rm -f "$LE_DIR/acme-ca.pem"; fi
	run_phase issue 40
	le_name=ip-$(printf '%s' "$le_ip" | tr . -)
	"$LE_HOOK" open
	# (through the hook, as the renewal: our certbot alone, the directory the plan names trusted through its CA, else Let's
	# Encrypt's own; short-lived: the only profile it issues IP certificates on)
	# shellcheck disable=SC2086 # the server's two words when named
	timeout 180 "$LE_HOOK" certbot certonly --standalone --non-interactive --agree-tos --register-unsafely-without-email \
		--preferred-profile shortlived --ip-address "$le_ip" --cert-name "$le_name" --keep-until-expiring \
		${le_url:+--server "$le_url"} >"$RUN_DIR/certbot.log" 2>&1
	le_rc=$?
	"$LE_HOOK" close
	[ $le_rc = 0 ] && [ -s "$LE_DIR/live/$le_name/fullchain.pem" ] ||
		le_keep "Let's Encrypt gave none: $(grep -v '^ *$' "$RUN_DIR/certbot.log" | tail -n 3 | tr '\n' ' ' | cut -c1-300)" \
			"get the certificate again later"
	# renewed below 3 days left (a short-lived certificate lives about 6: two days of retries on a 12-hour timer)
	grep -q '^renew_before_expiry' "$LE_DIR/renewal/$le_name.conf" 2>/dev/null ||
		sed -i '1i renew_before_expiry = 3 days' "$LE_DIR/renewal/$le_name.conf"
	run_phase install 70
	printf '%s\n' "$le_name" >"$in_state/le.new" && mv -f "$in_state/le.new" "$in_state/le" ||
		run_result failed-cleaned "cannot write $in_state/le" "check the box's disk"
	"$LE_HOOK" deploy "$le_name" || run_result failed-not-rolled-back "the certificate could not be put in place" "get the certificate again"
	printf '[Unit]\nDescription=SanityProtocol csqtt: Let'"'"'s Encrypt renewal (P2 B14)\nAfter=network-online.target\nWants=network-online.target\n\n[Service]\nType=oneshot\nExecStart=%s renew\n' \
		"$LE_HOOK" >/etc/systemd/system/sanity-csqtt-le.service &&
		printf '[Unit]\nDescription=SanityProtocol csqtt: Let'"'"'s Encrypt renewal, twice a day (P2 B14)\n\n[Timer]\nOnCalendar=*-*-* 00,12:00:00\nRandomizedDelaySec=1h\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n' \
			>/etc/systemd/system/sanity-csqtt-le.timer || run_result failed-not-rolled-back "cannot write our renewal timer" "check the box's disk"
	systemctl daemon-reload
	run_cmd systemctl enable --now sanity-csqtt-le.timer ||
		run_result failed-not-rolled-back "our renewal timer would not start" "get the certificate again"
	# the server reloads on SIGUSR1: it serves the lineage's certificate now, or the reload failed
	run_phase verify 90
	le_want=$(openssl x509 -noout -fingerprint -sha256 -in "$LE_DIR/live/$le_name/fullchain.pem" 2>/dev/null)
	le_web=$(sed -n 's/.*--web-port \([0-9]*\).*/\1/p' "$le_def" | head -n 1)
	le_at=$(sed -n 's/.*--web-bind \([^ ]*\).*/\1/p' "$le_def" | head -n 1)
	case ${le_at:-0.0.0.0} in 0.0.0.0 | ::) le_at=127.0.0.1 ;; esac
	le_i=0 le_got=''
	while [ $le_i -lt 10 ]; do
		le_got=$(echo | timeout 5 openssl s_client -connect "$le_at:$le_web" 2>/dev/null | openssl x509 -noout -fingerprint -sha256 2>/dev/null)
		[ -z "$le_want" ] || [ "$le_got" != "$le_want" ] || break
		le_i=$((le_i + 1))
		sleep 1
	done
	[ -z "$le_want" ] || [ "$le_got" = "$le_want" ] ||
		run_result failed-not-rolled-back "the server did not take the new certificate (it keeps the one it had)" "«Перезапустить», then read the connection back"
	run_phase "done" 100
	run_result ok "" "read the connection back"
}

# ── repair-box: «Исправить» for B14/B24 and B15 (P2-PLAN §1.3 "Box problems"; P2 B11c) ───────────────────────────────────
# Only the named unit, only while its condition still holds
action_repair_box() {
	rb_code=$(plan code) rb_unit=$(plan unit)
	case "$rb_code $rb_unit" in
	"B14 csqtt-docker-prereq.service" | "B14 csqtt25-docker-prereq.service" | "B24 mrtt-docker-prereq.service")
		# docker.service requires it and its helper is gone, so Docker will not start: the unit goes (an install that put its
		# helper back since is left alone)
		rb_exec=$(systemctl show -p ExecStart --value "$rb_unit" 2>/dev/null | sed -n 's/.*path=\([^ ;]*\).*/\1/p' | head -n 1)
		[ -n "$rb_exec" ] && [ ! -e "$rb_exec" ] || run_result failed-cleaned "$rb_unit has its helper again, or is gone" "check again"
		run_phase repair 50
		: >"$RUN_DIR/switching"
		run_cmd systemctl disable "$rb_unit"
		rm -f "/etc/systemd/system/$rb_unit"
		systemctl daemon-reload
		systemctl reset-failed "$rb_unit" >/dev/null 2>&1
		# Docker, down when the box was inspected, starts again (its containers by their own restart policies) only when the
		# plan says so, as its «Будет» did: it may have been stopped on purpose (B11c review F4)
		rb_docker=''
		if [ "$(plan start_docker)" = 1 ] && systemctl is-enabled --quiet docker.service 2>/dev/null &&
			! systemctl is-active --quiet docker.service; then
			run_cmd systemctl start docker.service || run_result failed-not-rolled-back "docker.service did not start" "read its journal"
			rb_docker='; Docker runs again'
		fi
		run_phase "done" 100
		run_result ok "" "docker.service no longer requires $rb_unit$rb_docker"
		;;
	"B15 csqtt-letsencrypt.timer")
		# swg-panel holds the stock install's port, and the stock certificate timer still runs for a server that will not
		systemctl is-active --quiet csqtt-letsencrypt.timer && ! systemctl is-active --quiet csqtt.service ||
			run_result failed-cleaned "the stock certificate timer no longer runs beside a stopped stock install" "check again"
		run_phase repair 50
		: >"$RUN_DIR/switching"
		run_cmd systemctl disable --now csqtt-letsencrypt.timer
		run_phase "done" 100
		run_result ok "" "the stock certificate timer is off"
		;;
	"S1 /etc/sysctl.d/99-sanity-csqtt.conf")
		# ours, kept for a csqtt server that ran beside our last instance and went its own way since (B11a verify R1): taken by
		# the removal's own rules, which keep it while an instance of ours or a csqtt server is there to need it
		[ -f "$HOST_DROPIN" ] || run_result failed-cleaned "our sysctl drop-in is gone already" "check again"
		run_phase repair 50
		: >"$RUN_DIR/switching"
		host_dropin_drop
		[ ! -f "$HOST_DROPIN" ] || run_result failed-cleaned "an instance of ours, a stock install or a csqtt server beside still needs our sysctl drop-in" "check again"
		run_phase "done" 100
		run_result ok "" "our sysctl drop-in is gone; forwarding stays on until the next boot"
		;;
	*) run_result failed-cleaned "no repair for $rb_code $rb_unit" "update the app" ;;
	esac
}

# ── rebuild-manifest: «Починить» for an instance of ours whose manifest was lost (§1.3; P2 B11c) ──────────────────────────
# From what lives, by the id alone: its two roots and its unit; its openings found by their own names (inst_found_host)
action_rebuild_manifest() {
	rbm_id=$(plan id)
	inst_paths "$rbm_id"
	[ -f "/etc/systemd/system/$in_unit" ] && [ -d "$in_state" ] || run_result failed-cleaned "sqc-$rbm_id is not installed here" "check again"
	[ ! -f "$in_state/manifest.json" ] || run_result failed-cleaned "sqc-$rbm_id has its manifest" "check again"
	run_phase repair 50
	MF=$in_state/manifest.json MF_ID=$rbm_id
	MF_OBJECTS="dir $in_state
dir $in_root
unit /etc/systemd/system/$in_unit"
	MF_HOST=$(inst_found_host "$rbm_id")
	mf_write || run_result failed-cleaned "cannot write $MF" "check the box's disk"
	run_phase "done" 100
	run_result ok "" "the manifest of sqc-$rbm_id is rebuilt: $(printf '%s\n' "$MF_HOST" | awk 'NF {print $1}' | sort | uniq -c | awk '{printf "%s%s x%s", s, $2, $1; s = ", "}')"
}

# journal <instance id> [lines]: «Журнал», the unit's last lines, for the engine alone (root, stdout), each redacted against
# nothing secret it could print (ours print no password: it never generates one)
cmd_journal() {
	case $1 in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) csqtt_emit JOURNAL "bad instance id"; csqtt_end; exit 2 ;; esac
	jn_n=${2:-200}
	case $jn_n in '' | *[!0-9]*) jn_n=200 ;; esac
	if [ -f "/var/lib/sanityprotocol/csqtt/$1/docker" ]; then
		# a Docker instance's: its container's log (P2 B13a)
		jn_out=$(docker logs -t --tail "$jn_n" "$(sed -n 's/^# docker //p' "/var/lib/sanityprotocol/csqtt/$1/docker")" 2>&1)
	else
		jn_out=$(journalctl -u "sanity-csqtt-$1.service" -n "$jn_n" -o short-iso --no-pager 2>/dev/null)
	fi
	while IFS= read -r jn_l; do
		[ -z "$jn_l" ] || csqtt_emit JOURNAL "$jn_l"
	done <<EOF
$jn_out
EOF
	csqtt_end
}

# readback <instance id>: the connection to an instance of ours, for the engine alone (stdout; never a log): the ports and
# the panel's bind from its unit, the panel's login from its env, the main password from a copy of its store read by its
# own binary (the panel may have changed it), and the sha256 of the panel's certificate (DER) for the app's pin.
cmd_readback() {
	rb_id=$1
	case $rb_id in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) csqtt_emit RB error "bad instance id"; csqtt_end; exit 2 ;; esac
	rb_state=/var/lib/sanityprotocol/csqtt/$rb_id rb_unit=/etc/systemd/system/sanity-csqtt-$rb_id.service
	# (a Docker instance's flags are its docker file's ExecStart= line, as a unit's: P2 B13a)
	[ -f "$rb_unit" ] || [ ! -f "$rb_state/docker" ] || rb_unit=$rb_state/docker
	rb_bin=/opt/sanityprotocol/csqtt/$rb_id/current/csqtt-server
	if [ ! -f "$rb_unit" ] || [ ! -x "$rb_bin" ] || [ ! -f "$rb_state/env" ]; then
		csqtt_emit RB error "sqc-$rb_id is not installed"
		csqtt_end
		exit 1
	fi
	rb_exec=$(sed -n 's/^ExecStart=//p' "$rb_unit" | head -n 1)
	rb_env() { inst_env "$rb_state" "$1"; }
	csqtt_emit RB public_ip "$(rb_env CSQTT_PUBLIC_IP)"
	csqtt_emit RB udp_port "$(printf '%s\n' "$rb_exec" | sed -n 's/.*--listen [^ ]*:\([0-9]*\).*/\1/p')"
	csqtt_emit RB web_port "$(printf '%s\n' "$rb_exec" | sed -n 's/.*--web-port \([0-9]*\).*/\1/p')"
	csqtt_emit RB web_bind "$(printf '%s\n' "$rb_exec" | sed -n 's/.*--web-bind \([^ ]*\).*/\1/p')"
	csqtt_emit RB web_user "$(rb_env CSQTT_WEB_USER)"
	csqtt_emit RB web_pass "$(rb_env CSQTT_WEB_PASS)"
	csqtt_emit RB secret_path "$(rb_env CSQTT_WEB_SECRET_PATH)"
	rb_cert=$(awk '/-----BEGIN CERTIFICATE-----/ {f = 1; next} /-----END CERTIFICATE-----/ {exit} f' "$rb_state/web_cert.pem" 2>/dev/null |
		base64 -d 2>/dev/null | sha256sum | cut -d' ' -f1)
	[ "$rb_cert" = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 ] && rb_cert=
	csqtt_emit RB cert_sha256 "$rb_cert"
	# who issued it and until when (P2 B14: Let's Encrypt's, renewed by our timer, or the server's own), the lineage it follows
	if command -v openssl >/dev/null 2>&1 && [ -n "$rb_cert" ]; then
		csqtt_emit RB cert_issuer "$(openssl x509 -noout -issuer -nameopt RFC2253 -in "$rb_state/web_cert.pem" 2>/dev/null | sed 's/^issuer=//')"
		csqtt_emit RB cert_not_after "$(openssl x509 -noout -enddate -in "$rb_state/web_cert.pem" 2>/dev/null | sed 's/^notAfter=//')"
	fi
	csqtt_emit RB cert_le "$(cat "$rb_state/le" 2>/dev/null)"
	# the store is copied, so the live one is never opened by a second process; the server's line is escaped already
	rb_copy=$(mktemp -d) || { csqtt_emit RB error "no temporary directory"; csqtt_end; exit 1; }
	for rb_f in csqtt.db csqtt.db-wal csqtt.db-shm passwords.json passwords.json.imported; do
		[ ! -f "$rb_state/$rb_f" ] || cp -p "$rb_state/$rb_f" "$rb_copy/"
	done
	rb_main=$("$rb_bin" --read-store "$rb_copy" 2>/dev/null | grep '^CSQTT_RB|main_password|' | head -n 1)
	rm -rf "$rb_copy"
	if [ -n "$rb_main" ]; then
		printf '%s\n' "$rb_main"
		csqtt_marker_count=$((csqtt_marker_count + 1))
	else
		csqtt_emit RB error "the main password could not be read from the store"
	fi
	csqtt_end
}

cmd_exec() {
	RUN_ID=$1
	RUN_DIR=$(run_dir "$RUN_ID")
	RUN_LOG=$RUN_DIR/log
	# (an update's marker in /run goes with the run too: UP_MARK, set by action_update)
	trap 'run_beat_stop; rm -f "$RUN_DIR/secrets" "$RUN_DIR/out" ${UP_MARK:+"$UP_MARK"}' EXIT
	run_emit RUN "$RUN_ID" "$(plan action)"
	run_recheck
	case $(plan action) in
	selftest) action_selftest ;;
	create) action_create ;;
	update) action_update ;;
	remove) action_remove ;;
	restore) action_restore ;;
	restart) action_restart ;;
	remove-foreign) action_remove_foreign ;;
	repair-box) action_repair_box ;;
	le) action_le ;;
	rebuild-manifest) action_rebuild_manifest ;;
	*) run_result failed-cleaned "unknown action $(plan action)" "update the app" ;;
	esac
}

case ${1:-} in
start) cmd_start "${2:?run id}" "${3:?upload dir}" ;;
tail) cmd_tail "${2:?run id}" "${3:-0}" ;;
cancel) cmd_cancel "${2:?run id}" ;;
status) cmd_status ;;
exec) cmd_exec "${2:?run id}" ;;
readback) cmd_readback "${2:?instance id}" ;;
journal) cmd_journal "${2:?instance id}" "${3:-200}" ;;
*) csqtt_emit RUN error "unknown verb: ${1:-}"; csqtt_end; exit 2 ;;
esac
