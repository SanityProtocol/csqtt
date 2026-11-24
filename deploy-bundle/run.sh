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
	rm -f "$RUNS"/*/secrets "$RUNS"/*/out "$RUNS"/*/core 2>/dev/null
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
	: >"$rs_dir/log" && chown "$rs_uid" "$rs_dir/log" && chmod 600 "$rs_dir/log"
	# the last KEEP runs are kept (the newest by name: run ids start with their UTC time)
	ls -1 "$RUNS" 2>/dev/null | sort | awk -v keep=$KEEP '{a[NR] = $0} END {for (i = 1; i <= NR - keep; i++) print a[i]}' |
		while IFS= read -r rs_old; do
			[ "$rs_old" = "$rs_id" ] || rm -rf "${RUNS:?}/$rs_old"
		done
	if ! systemd-run --unit "sanity-csqtt-deploy-$rs_id" --collect --quiet --property=KillMode=mixed \
		--property="ExecStopPost=/bin/rm -f $rs_dir/secrets $rs_dir/out $rs_dir/core" \
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
	rm -f "$RUN_DIR/secrets" "$RUN_DIR/core"
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
	# an update cut off before holds the binary until this run resolves it (action_update's up_recover, B10 review F2)
	[ "$(plan action)" != update ] || [ ! -f "/var/lib/sanityprotocol/csqtt/$(plan id)/update.pending" ] || rr_sha=''
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
host_sysctl() {
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
		[ -f "$HOST_DROPIN" ] || printf '# SanityProtocol csqtt (APP-PLAN §6.9): forwarding and raise-only floors; each raised from the value above it\n' >"$HOST_DROPIN"
		printf '# %s was %s\n%s = %s\n' "$hs_key" "$hs_now" "$hs_key" "$hs_set" >>"$HOST_DROPIN"
	done <<EOF
$HOST_FLOORS
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
	while read -r hu_kind hu_rest; do
		hu_key='' hu_was='' hu_rule='' hu_zone='' hu_port='' hu_policy='' hu_redir=''
		for hu_kv in $hu_rest; do
			case $hu_kv in
			key=*) hu_key=${hu_kv#key=} ;; was=*) hu_was=${hu_kv#was=} ;; rule=*) hu_rule=${hu_kv#rule=} ;; redirects=*) hu_redir=${hu_kv#redirects=} ;;
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
		esac
	done <<EOF
$hu_list
EOF
	[ $hu_reload = 0 ] || firewall-cmd -q --reload 2>/dev/null
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
inst_verify() {  # inst_verify <unit> <udp> <web> <iface> <secret path>
	iv_unit=$1 iv_udp=$2 iv_web=$3 iv_iface=$4 iv_secret=$5
	[ "$(systemctl is-active "$iv_unit")" = active ] || { echo "$iv_unit is not active"; return 0; }
	iv_pid=$(systemctl show -p MainPID --value "$iv_unit")
	for iv_check in "u $iv_udp UDP" "t $iv_web TCP"; do
		iv_p=${iv_check%% *} iv_rest=${iv_check#* }
		iv_port=${iv_rest%% *} iv_name=${iv_rest#* }
		iv_holders=$(ss -H -l"$iv_p"np "( sport = :$iv_port )" 2>/dev/null | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | sort -u | tr '\n' ' ')
		[ "$iv_holders" = "$iv_pid " ] || { echo "$iv_name $iv_port is held by pids ${iv_holders:-none} (ours: $iv_pid)"; return 0; }
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
inst_unit_text() {  # inst_unit_text <id> <root> <state> <flags> <open lines>
	cat <<EOF
[Unit]
Description=csqtt server sqc-$1 (SanityProtocol)
Wants=network-online.target
After=network-online.target

[Service]
# no CSQTT_SERVICE_MANAGER: the panel's «Перезапустить» then ends the server, and Restart=always starts it again (the
# systemd route would restart a unit named after the instance, sqc-$1.service, which is not this one)
Type=notify
NotifyAccess=main
WatchdogSec=30
EnvironmentFile=$3/env
ExecStart=$2/current/csqtt-server $4
# an update's record goes the first time a start is ready: the build it went to has served, its store is live from then on,
# and no later run may put the copy from before it back over that (B10 verify N1)
ExecStartPost=-/bin/rm -f $3/update.pending
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
	action_cleanup() {
		mf_undo
		# the server's own footprint back to before the switch, then our host changes back to before this run
		[ ! -e "$RUN_DIR/switching" ] || cr_server_undo
		host_undo
		for cr_d in $cr_parents; do rmdir "$cr_d" 2>/dev/null; done
		return 0
	}
	cr_fail() { action_cleanup; run_result failed-cleaned "$1" "${2:-check again}"; }
	cr_flags="--listen 0.0.0.0:$cr_udp --web-port $cr_web --config-dir $cr_state --instance sqc-$cr_id --iface $cr_iface"
	cr_flags="$cr_flags --tun-addr ${cr_subnet%.0/24}.1/24 --instance-slot $cr_slot"
	[ -z "$cr_bind" ] || cr_flags="$cr_flags --web-bind $cr_bind"
	cr_open="$cr_udp/udp"
	[ "$cr_bind" = 127.0.0.1 ] || cr_open="$cr_open $cr_web/tcp"

	# stage: nothing is made until the box and the binary have answered, and every object the manifest will name is new, so
	# undoing this run can remove only what it made
	run_phase stage 10
	if [ -e "$cr_state" ] || [ -e "$cr_root" ] || [ -e "/etc/systemd/system/$cr_unit" ]; then
		# «Продолжить» undoes only what the run it continues made and left with no result: never a finished instance (whose
		# store holds its clients), never one a later run made (B8 verify F8)
		cr_of=$(plan resume_of)
		cr_made_by=$(sed -n 's/.*"run":"\([^"]*\)".*/\1/p' "$cr_state/manifest.json" 2>/dev/null)
		if [ "$(plan resume)" = 1 ] && [ -n "$cr_of" ] && [ "$cr_made_by" = "$cr_of" ] && [ -f "$RUNS/$cr_of/log" ] &&
			[ ! -s "$RUNS/$cr_of/result" ] && ! grep -q '^CSQTT_RESULT|' "$RUNS/$cr_of/log"; then
			# «Продолжить» (B8 review F1): an earlier run of this plan was cut off; what its manifest names goes (its server
			# stopped, its rules with it), and the create runs again from the start with this run's credentials
			MF_OBJECTS=$(mf_objects_of "$cr_state/manifest.json")
			MF_HOST=$(mf_host_of "$cr_state/manifest.json")
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
			MF_OBJECTS='' MF_HOST=''
		elif [ "$(plan resume)" = 1 ]; then
			run_result failed-cleaned "sqc-$cr_id is not what run ${cr_of:-?} left: it was finished or made again since (run ${cr_made_by:-?})" \
				"check again; to use it, «Подключиться»"
		else
			run_result failed-cleaned "sqc-$cr_id exists already: an earlier run of this plan was cut off" \
				"continue it («Продолжить»), or check again"
		fi
	fi
	# a slot another instance of ours took since the plan (a second phone's create) would share its policy table and mark
	for cr_u in /etc/systemd/system/sanity-csqtt-*.service; do
		[ -f "$cr_u" ] || continue
		if sed -n 's/^ExecStart=//p' "$cr_u" | grep -qE -- "--instance-slot $cr_slot( |\$)"; then
			run_result failed-cleaned "slot $cr_slot is now ${cr_u##*/}'s" "check again: the app proposes another slot"
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
	mf_add dir "$cr_root"
	mkdir -p "$cr_release" && chmod 755 "$cr_root" "$cr_root/releases" "$cr_release" || cr_fail "cannot make $cr_release"
	install -m 755 "$RUN_DIR/core" "$cr_release/csqtt-server" && ln -sfn "$cr_release" "$cr_root/current" || cr_fail "cannot install the core"
	run_phase "state and unit" 30
	# env (0600): the panel login, its secret path, the public address; never the main password, which env would apply over
	# the store at every start (undoing a change made in the panel): it goes in the one-shot overrides the server consumes
	{
		printf 'CSQTT_WEB_USER=%s\n' "$(plan web_user)"
		sed -n 's/^web_pass=/CSQTT_WEB_PASS=/p; s/^secret_path=/CSQTT_WEB_SECRET_PATH=/p' "$RUN_DIR/secrets"
		printf 'CSQTT_PUBLIC_IP=%s\n' "$(plan public_ip)"
	} >"$cr_state/env" && chmod 600 "$cr_state/env" || cr_fail "cannot write $cr_state/env"
	( umask 077 && printf '{"main_password":"%s"}\n' "$(sed -n 's/^main_password=//p' "$RUN_DIR/secrets" | sed 's/[\\"]/\\&/g')" \
		>"$cr_state/deploy-overrides.json" ) || cr_fail "cannot write the main password"
	cr_open_unit=''
	if [ "$cr_fw" = iptables ]; then
		cr_open_unit=$(inst_open_sh "$cr_id" "$cr_state" "$cr_open") || cr_fail "cannot write $cr_state/open.sh"
	fi
	mf_add unit "/etc/systemd/system/$cr_unit"
	inst_unit_text "$cr_id" "$cr_root" "$cr_state" "$cr_flags" "$cr_open_unit" >"/etc/systemd/system/$cr_unit" || cr_fail "cannot write $cr_unit"
	systemctl daemon-reload
	run_safe_point

	# host: forwarding and the floors, then the openings (each recorded as made, undone by a failure from here on)
	run_phase host 45
	host_sysctl
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
	# enabled, then started on its own: systemd 255's `enable --now` exits 0 for a start that failed (the zoo, Ubuntu 24.04)
	run_cmd systemctl enable "$cr_unit" || cr_fail "$cr_unit could not be enabled"
	run_cmd systemctl start "$cr_unit" || cr_fail "$cr_unit did not start: $(inst_start_lines "$cr_unit")"

	# verify: it runs, it alone holds its ports (SO_REUSEADDR makes a bind prove nothing), its TUN is up, the panel answers
	run_phase verify 80
	cr_why=$(inst_verify "$cr_unit" "$cr_udp" "$cr_web" "$cr_iface" "$(sed -n 's/^secret_path=//p' "$RUN_DIR/secrets")")
	[ -z "$cr_why" ] || cr_fail "$cr_why"
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

up_prune() {  # the release before stays for one more update; older ones go
	for up_r in "$up_root"/releases/*/; do
		up_r=${up_r%/}
		[ "$up_r" = "$up_new" ] || [ "$up_r" = "$up_old" ] || rm -rf "$up_r"
	done
}

# up_back <why> [store]: back to the build before, setting up_why (empty: it serves). With `store` (the new build has run on it)
# the store comes back from the copy first; without, the new build never ran and the store stays as it is, and no copy is
# named (this run made none: one there is an older update's). `current` is flipped back only where it moved. The record goes
# once the box is back on disk; a store that cannot be put back keeps it, the unit stopped, so the next run tries again. Never
# run inside $(…): its results end the run
up_back() {
	systemctl stop "$up_unit" 2>/dev/null
	if [ "${2:-}" = store ]; then
		up_where="the store before the update is whole in $up_copy"
		up_store_back 2>"$RUN_DIR/restore.err" ||
			run_result failed-not-rolled-back "$1; the store could not be put back: $(head -c 200 "$RUN_DIR/restore.err" | tr '\n' ' ')" \
				"free some disk, then «Запустить» or «Починить»: $up_where"
	else
		up_where="its store is as it was: the new build never ran on it"
	fi
	if [ "$(readlink "$up_root/current")" != "$up_old" ]; then
		ln -sfn "$up_old" "$up_root/current.new" && mv -Tf "$up_root/current.new" "$up_root/current" ||
			run_result failed-not-rolled-back "$1; cannot switch back to $up_old" "«Починить»: $up_where"
	fi
	[ "$up_new" = "$up_old" ] || rm -rf "$up_new"
	rm -f "$UP_PENDING"
	systemctl start "$up_unit" 2>/dev/null
	up_why=$(inst_verify "$up_unit" "$up_udp" "$up_web" "$up_iface" "$up_secret")
	[ -z "$up_why" ] || run_result failed-not-rolled-back \
		"$1; the rollback did not serve either: $up_why ($(systemctl is-active "$up_unit"), current -> $(readlink "$up_root/current"))" \
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
		[ "$rc_new" = "$rc_old" ] || [ "$rc_new" = "$(readlink "$up_root/current")" ] || rm -rf "$rc_new"
		rm -f "$UP_PENDING"
		systemctl is-active --quiet "$up_unit" || run_cmd systemctl start "$up_unit"
		return 0
	fi
	up_old=$rc_old up_new=$rc_new
	systemctl is-active --quiet "$up_unit" || run_cmd systemctl start "$up_unit"
	up_why=$(inst_verify "$up_unit" "$up_udp" "$up_web" "$up_iface" "$up_secret")
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
	[ -f "/etc/systemd/system/$up_unit" ] && [ -L "$up_root/current" ] || run_result failed-cleaned "sqc-$up_id is not installed here" "check again"
	up_flags=$(sed -n "s|^ExecStart=$up_root/current/csqtt-server ||p" "/etc/systemd/system/$up_unit" | head -n 1)
	up_udp=$(printf '%s\n' "$up_flags" | sed -n 's/.*--listen [^ ]*:\([0-9]*\).*/\1/p')
	up_web=$(printf '%s\n' "$up_flags" | sed -n 's/.*--web-port \([0-9]*\).*/\1/p')
	up_iface=$(printf '%s\n' "$up_flags" | sed -n 's/.*--iface \([^ ]*\).*/\1/p')
	up_secret=$(sed -n 's/^CSQTT_WEB_SECRET_PATH=//p' "$up_state/env" | head -n 1)
	up_settled='' up_failed=''

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
	action_cleanup() { [ ! -d "$up_new" ] || [ "$up_new" = "$up_old" ] || rm -rf "$up_new"; }
	if [ "$up_new" != "$up_old" ]; then
		mkdir -p "$up_new" && install -m 755 "$RUN_DIR/core" "$up_new/csqtt-server.new" && mv -f "$up_new/csqtt-server.new" "$up_new/csqtt-server" ||
			{ action_cleanup; run_result failed-cleaned "cannot install the new release in $up_new" "check the box's disk"; }
	fi
	run_safe_point

	# switch (D4: a cancel is refused from here): recorded, stopped, its store copied aside, `current` flipped, started
	run_phase switch 60
	: >"$RUN_DIR/switching"
	printf 'old=%s\nnew=%s\n' "$up_old" "$up_new" >"$UP_PENDING.new" && mv -f "$UP_PENDING.new" "$UP_PENDING" ||
		{ rm -f "$UP_PENDING.new"; action_cleanup; run_result failed-cleaned "cannot write $UP_PENDING" "check the box's disk"; }
	run_cmd systemctl stop "$up_unit"
	if ! up_copy_store 2>"$RUN_DIR/copy.err"; then
		rm -rf "$up_copy.new"
		up_rollback "cannot keep a copy of the store aside: $(head -c 200 "$RUN_DIR/copy.err" | tr '\n' ' ')"
	fi
	if [ "$up_new" = "$up_old" ]; then
		# «Починить»: the same build again, over a damaged or missing binary
		install -m 755 "$RUN_DIR/core" "$up_new/csqtt-server.new" && mv -f "$up_new/csqtt-server.new" "$up_new/csqtt-server" || up_rollback "cannot reinstall the binary"
	else
		ln -sfn "$up_new" "$up_root/current.new" && mv -Tf "$up_root/current.new" "$up_root/current" || up_rollback "cannot switch to $up_new"
	fi
	run_cmd systemctl start "$up_unit" || up_rollback "$up_unit did not start on the new build: $(inst_start_lines "$up_unit")" store

	run_phase verify 80
	up_why=$(inst_verify "$up_unit" "$up_udp" "$up_web" "$up_iface" "$up_secret")
	[ -z "$up_why" ] || up_rollback "the new build does not serve: $up_why" store
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

# Our drop-in goes with the last instance of ours. A floor goes back to what its comment says it was only while its live value
# is still the one the drop-in set and no csqtt server runs (a stock one leans on the same floors): one raised since by someone
# else keeps its value. ip_forward is never written back: Docker, a WireGuard, any router may have come to rely on it, and
# writing it resets every device's forwarding and accept_redirects (B11a review F1); it is the server's own tuning as well.
# The iptables-nft tables the server's rules made stay, empty and inert, until the next boot: telling them from an admin's
# (a DROP policy and nothing else) is not safe (B11a review F2)
host_dropin_drop() {
	for hd_u in /etc/systemd/system/sanity-csqtt-*.service; do [ -f "$hd_u" ] && return 0; done
	[ -f "$HOST_DROPIN" ] || return 0
	if ! pgrep -x csqtt-server >/dev/null 2>&1 && ! pgrep -x csqtt >/dev/null 2>&1 && ! pgrep -x csqtt25 >/dev/null 2>&1; then
		sed -n 's/^# \([a-z0-9_.]*\) was \(.*\)$/\1 \2/p' "$HOST_DROPIN" | while read -r hd_k hd_was; do
			[ "$hd_k" != net.ipv4.ip_forward ] || continue
			hd_set=$(sed -n "s/^$hd_k = //p" "$HOST_DROPIN" | head -n 1)
			[ "$(sysctl -n "$hd_k" 2>/dev/null | tr -s '\t ' '  ')" = "$hd_set" ] || continue
			sysctl -q -w "$hd_k=$hd_was" 2>/dev/null
		done
	fi
	rm -f "$HOST_DROPIN"
}

# inst_take_down <id> (after inst_paths): an instance of ours stopped and taken off the box by its id alone: its unit, its
# tagged rules, its openings (ufw's and firewalld's through their tool; the iptables ones go with its unit's stop), the drop-in
# with the last instance, its release. The state dir stays as it is
inst_take_down() {
	MF=$in_state/manifest.json MF_ID=$1
	MF_HOST=$(mf_host_of "$MF" 2>/dev/null | grep -v '^sysctl ')
	if [ -f "/etc/systemd/system/$in_unit" ]; then
		run_cmd systemctl disable --now "$in_unit"
		rm -f "/etc/systemd/system/$in_unit"
		systemctl daemon-reload
		systemctl reset-failed "$in_unit" >/dev/null 2>&1
	fi
	inst_sweep "$1"
	host_undo
	host_dropin_drop
	rm -rf "$in_root"
	rm -f "$in_state/open.sh"
}

action_remove() {
	rm_id=$(plan id) rm_purge=$(plan purge)
	inst_paths "$rm_id"
	[ -e "$in_state" ] || [ -e "$in_root" ] || [ -f "/etc/systemd/system/$in_unit" ] || run_result failed-cleaned "sqc-$rm_id is not here" "check again"
	# its flags kept for «Восстановить» when the store stays, with the release it ran (a phone with an older core must not
	# restore it: M-150, B11a review F6); nothing has changed if they cannot be
	if [ "$rm_purge" != 1 ] && [ -d "$in_state" ] && [ -f "/etc/systemd/system/$in_unit" ]; then
		{ printf '# release %s\n' "$(basename "$(readlink "$in_root/current" 2>/dev/null)" 2>/dev/null)"
			cat "/etc/systemd/system/$in_unit"; } >"$in_state/unit.saved.new" && mv -f "$in_state/unit.saved.new" "$in_state/unit.saved" ||
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
	if [ -e "$in_root" ] || [ -f "/etc/systemd/system/$in_unit" ]; then
		systemctl is-active --quiet "$in_unit" && run_result failed-cleaned "sqc-$rs_id is installed already" "check again"
		# a restore (or a removal) cut off before its end left part of it, its flags still kept: what it made goes first, by
		# the id, as a removal takes it, and the restore runs again (B11a review F4)
		run_phase "take down" 5
		: >"$RUN_DIR/switching"
		inst_take_down "$rs_id"
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
	rs_secret=$(sed -n 's/^CSQTT_WEB_SECRET_PATH=//p' "$in_state/env" | head -n 1)
	# its own ports, interface and slot must be free again: its clients come back on them
	for rs_p in $rs_udp $rs_web; do
		[ -z "$(ss -H -ltun "( sport = :$rs_p )" 2>/dev/null)" ] || run_result failed-cleaned "port $rs_p is held now" "free it, or remove the kept store"
	done
	! ip link show "$rs_iface" >/dev/null 2>&1 || run_result failed-cleaned "interface $rs_iface exists now" "check again"
	for rs_u in /etc/systemd/system/sanity-csqtt-*.service; do
		[ -f "$rs_u" ] && sed -n 's/^ExecStart=//p' "$rs_u" | grep -qE -- "--instance-slot $rs_slot( |\$)" &&
			run_result failed-cleaned "slot $rs_slot is ${rs_u##*/}'s now" "remove the kept store, or that instance"
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
	mf_add unit "/etc/systemd/system/$in_unit"
	inst_unit_text "$rs_id" "$in_root" "$in_state" "$rs_flags" "$rs_open_unit" >"/etc/systemd/system/$in_unit" || cr_fail "cannot write $in_unit"
	systemctl daemon-reload
	run_safe_point
	run_phase host 45
	host_sysctl
	host_firewall
	[ "$cr_fw" != iptables ] || host_add iptables-input "ports=$(printf '%s' "$cr_open" | tr ' ' ,)"
	run_safe_point
	run_phase switch 60
	for cr_k in $CR_SERVER_SYSCTLS; do printf '%s=%s\n' "$cr_k" "$(sysctl -n "$cr_k" 2>/dev/null)"; done >"$RUN_DIR/sysctl-before"
	! command -v nft >/dev/null 2>&1 || nft list tables >"$RUN_DIR/nft-before" 2>/dev/null
	awk '{print $1}' /proc/modules >"$RUN_DIR/modules-before" 2>/dev/null
	: >"$RUN_DIR/switching"
	# enabled, then started on its own: systemd 255's `enable --now` exits 0 for a start that failed (the zoo, Ubuntu 24.04)
	run_cmd systemctl enable "$in_unit" || cr_fail "$in_unit could not be enabled"
	run_cmd systemctl start "$in_unit" || cr_fail "$in_unit did not start: $(inst_start_lines "$in_unit")"
	run_phase verify 80
	rs_why=$(inst_verify "$in_unit" "$rs_udp" "$rs_web" "$rs_iface" "$rs_secret")
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
	[ -f "/etc/systemd/system/$in_unit" ] || run_result failed-cleaned "sqc-$rt_id is not installed here" "check again"
	rt_flags=$(sed -n 's|^ExecStart=[^ ]*csqtt-server ||p' "/etc/systemd/system/$in_unit" | head -n 1)
	run_phase switch 50
	: >"$RUN_DIR/switching"
	run_cmd systemctl enable "$in_unit"
	run_cmd systemctl restart "$in_unit" ||
		run_result failed-not-rolled-back "$in_unit did not start: $(inst_start_lines "$in_unit")" "read its journal («Журнал»)"
	run_phase verify 80
	rt_why=$(inst_verify "$in_unit" "$(printf '%s\n' "$rt_flags" | sed -n 's/.*--listen [^ ]*:\([0-9]*\).*/\1/p')" \
		"$(printf '%s\n' "$rt_flags" | sed -n 's/.*--web-port \([0-9]*\).*/\1/p')" "$(printf '%s\n' "$rt_flags" | sed -n 's/.*--iface \([^ ]*\).*/\1/p')" \
		"$(sed -n 's/^CSQTT_WEB_SECRET_PATH=//p' "/var/lib/sanityprotocol/csqtt/$rt_id/env" | head -n 1)")
	[ -z "$rt_why" ] || run_result failed-not-rolled-back "$rt_why" "read its journal («Журнал»)"
	run_phase "done" 100
	run_result ok "" ""
}

# journal <instance id> [lines]: «Журнал», the unit's last lines, for the engine alone (root, stdout), each redacted against
# nothing secret it could print (ours print no password: it never generates one)
cmd_journal() {
	case $1 in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) csqtt_emit JOURNAL "bad instance id"; csqtt_end; exit 2 ;; esac
	jn_n=${2:-200}
	case $jn_n in '' | *[!0-9]*) jn_n=200 ;; esac
	jn_out=$(journalctl -u "sanity-csqtt-$1.service" -n "$jn_n" -o short-iso --no-pager 2>/dev/null)
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
	rb_bin=/opt/sanityprotocol/csqtt/$rb_id/current/csqtt-server
	if [ ! -f "$rb_unit" ] || [ ! -x "$rb_bin" ] || [ ! -f "$rb_state/env" ]; then
		csqtt_emit RB error "sqc-$rb_id is not installed"
		csqtt_end
		exit 1
	fi
	rb_exec=$(sed -n 's/^ExecStart=//p' "$rb_unit" | head -n 1)
	rb_env() { sed -n "s/^$1=//p" "$rb_state/env" | head -n 1; }
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
	trap 'run_beat_stop; rm -f "$RUN_DIR/secrets" "$RUN_DIR/out"' EXIT
	run_emit RUN "$RUN_ID" "$(plan action)"
	run_recheck
	case $(plan action) in
	selftest) action_selftest ;;
	create) action_create ;;
	update) action_update ;;
	remove) action_remove ;;
	restore) action_restore ;;
	restart) action_restart ;;
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
