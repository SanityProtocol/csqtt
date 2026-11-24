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
		printf '{"schema":1,"id":"%s","objects":[' "$MF_ID"
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
		sleep 5
	done
	run_beat_stop
}

# iptables, which the server's NAT needs (Alma ships without it): installed, never removed by an undo
host_iptables() {
	command -v iptables >/dev/null 2>&1 && return 0
	run_phase packages 12
	if command -v apt-get >/dev/null 2>&1; then
		host_lock_wait || cr_fail "the package lock is held by $(apt_lock_holder)" "wait for it to finish, then try again"
		# a dpkg run cut off before leaves packages half-configured, and apt refuses until they are: once (DE-O4)
		[ -z "$(dpkg --audit 2>/dev/null)" ] || run_cmd env DEBIAN_FRONTEND=noninteractive dpkg --configure -a --force-confold </dev/null
		hi_apt="env DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends -o Dpkg::Options::=--force-confold iptables"
		# shellcheck disable=SC2086 # the words of ours
		run_cmd $hi_apt </dev/null || { run_cmd env DEBIAN_FRONTEND=noninteractive apt-get update -q </dev/null && run_cmd $hi_apt </dev/null; } ||
			cr_fail "iptables could not be installed" "check the box's package sources, then try again"
	elif command -v dnf >/dev/null 2>&1; then
		run_cmd dnf install -y -q iptables-nft </dev/null || cr_fail "iptables could not be installed" "check the box's package sources, then try again"
	else
		cr_fail "iptables is not installed (the server's NAT needs it)" "install iptables and try again"
	fi
	host_add package name=iptables
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
		host_add sysctl "key=$hs_key" "was=$(printf '%s' "$hs_now" | tr ' ' ,)" "set=$(printf '%s' "$hs_set" | tr ' ' ,)" $hs_redir
		[ -f "$HOST_DROPIN" ] || printf '# SanityProtocol csqtt (APP-PLAN §6.9): forwarding and raise-only floors; each raised from the value above it\n' >"$HOST_DROPIN"
		printf '# %s was %s\n%s = %s\n' "$hs_key" "$hs_now" "$hs_key" "$hs_set" >>"$HOST_DROPIN"
		[ "$hs_now" = "$hs_set" ] || run_cmd sysctl -w "$hs_key=$hs_set" || cr_fail "cannot set $hs_key"
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
		host_add firewalld-policy "policy=sqc$cr_id-fwd"
		run_cmd firewall-cmd -q --permanent --new-policy="sqc$cr_id-fwd" ||
			cr_fail "this firewalld has no policies (0.9 or newer is needed)" "update firewalld, then try again"
		run_cmd firewall-cmd -q --permanent --policy="sqc$cr_id-fwd" --add-ingress-zone="sqc$cr_id" &&
			run_cmd firewall-cmd -q --permanent --policy="sqc$cr_id-fwd" --add-egress-zone=ANY &&
			run_cmd firewall-cmd -q --permanent --policy="sqc$cr_id-fwd" --set-target=ACCEPT &&
			run_cmd firewall-cmd -q --reload || cr_fail "firewalld would not let $cr_iface forward"
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
		firewalld-port) firewall-cmd -q --permanent --zone="$hu_zone" --remove-port="$hu_port" 2>/dev/null; hu_reload=1 ;;
		firewalld-zone) firewall-cmd -q --permanent --delete-zone="$hu_zone" 2>/dev/null; hu_reload=1 ;;
		firewalld-policy) firewall-cmd -q --permanent --delete-policy="$hu_policy" 2>/dev/null; hu_reload=1 ;;
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

# the objects a manifest names, as `kind path` lines (our own format: one object per {…})
mf_objects_of() {
	sed -n 's/.*"objects":\[\(.*\)\],"host".*/\1/p' "$1" | sed 's/},{/}\n{/g' |
		sed -n 's/.*"kind":"\([^"]*\)","path":"\([^"]*\)".*/\1 \2/p'
}

# The server's own footprint once it has run (B8 review F4): if it died rather than stopped, its rules tagged with its instance
# (SQC-<ID>_…, every table, v4 and v6) and its forward chain stay; and the host-wide values it sets at every start are put
# back as they were before the switch, unless another csqtt server still runs and needs them
cr_server_undo() {
	cr_tagbase=SQC-$(printf '%s' "$cr_id" | tr 'a-f' 'A-F')_
	for cs_ipt in iptables ip6tables; do
		command -v $cs_ipt >/dev/null 2>&1 || continue
		for cs_t in filter nat mangle raw; do
			$cs_ipt -w 5 -t $cs_t -S 2>/dev/null | grep '^-A ' | grep -F -- "$cr_tagbase" | sed 's/^-A /-D /' | while read -r cs_rule; do
				eval "$cs_ipt -w 5 -t $cs_t $cs_rule" 2>/dev/null
			done
			$cs_ipt -w 5 -t $cs_t -S 2>/dev/null | awk -v t="$cr_tagbase" '$1 == "-N" && index($2, t) == 1 {print $2}' | while read -r cs_c; do
				$cs_ipt -w 5 -t $cs_t -F "$cs_c" 2>/dev/null
				$cs_ipt -w 5 -t $cs_t -X "$cs_c" 2>/dev/null
			done
		done
	done
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
		if [ "$(plan resume)" = 1 ] && grep -qs "\"id\":\"$cr_id\"" "$cr_state/manifest.json"; then
			# «Продолжить» (B8 review F1): an earlier run of this plan was cut off; what its manifest names goes (its server
			# stopped, its rules with it), and the create runs again from the start with this run's credentials
			MF_OBJECTS=$(mf_objects_of "$cr_state/manifest.json")
			MF_HOST=$(mf_host_of "$cr_state/manifest.json")
			run_cmd systemctl stop "$cr_unit"
			mf_undo
			cr_server_undo
			host_undo
			MF_OBJECTS='' MF_HOST=''
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
	# on a plain iptables box whose INPUT drops, the openings are the unit's own: made at each start (a reboot, a restore of
	# the saved rules), taken at each stop, tagged SQC-<ID>_OPEN (§1.5)
	cr_open_unit=''
	if [ "$cr_fw" = iptables ]; then
		cr_tag=SQC-$(printf '%s' "$cr_id" | tr 'a-f' 'A-F')_OPEN
		{
			printf '#!/bin/sh\n# SanityProtocol csqtt: sqc-%s'"'"'s openings in INPUT, run by its unit (P2 B9)\n' "$cr_id"
			printf 'for r in'
			for cr_r in $cr_open; do printf ' "-p %s --dport %s"' "${cr_r#*/}" "${cr_r%/*}"; done
			printf '; do\n'
			# shellcheck disable=SC2016 # the generated script's own variables
			printf '\tif [ "${1:-add}" = add ]; then\n\t\tiptables -w 5 -C INPUT $r -m comment --comment %s -j ACCEPT 2>/dev/null ||\n' "$cr_tag"
			# shellcheck disable=SC2016
			printf '\t\t\tiptables -w 5 -I INPUT $r -m comment --comment %s -j ACCEPT\n\telse\n' "$cr_tag"
			# shellcheck disable=SC2016
			printf '\t\tiptables -w 5 -D INPUT $r -m comment --comment %s -j ACCEPT 2>/dev/null\n\tfi\ndone\nexit 0\n' "$cr_tag"
		} >"$cr_state/open.sh" && chmod 700 "$cr_state/open.sh" || cr_fail "cannot write $cr_state/open.sh"
		cr_open_unit="ExecStartPre=-/bin/sh $cr_state/open.sh add
ExecStopPost=-/bin/sh $cr_state/open.sh del"
	fi
	mf_add unit "/etc/systemd/system/$cr_unit"
	cat >"/etc/systemd/system/$cr_unit" <<EOF || cr_fail "cannot write $cr_unit"
[Unit]
Description=csqtt server sqc-$cr_id (SanityProtocol)
Wants=network-online.target
After=network-online.target

[Service]
# no CSQTT_SERVICE_MANAGER: the panel's «Перезапустить» then ends the server, and Restart=always starts it again (the
# systemd route would restart a unit named after the instance, sqc-$cr_id.service, which is not this one)
Type=notify
NotifyAccess=main
WatchdogSec=30
EnvironmentFile=$cr_state/env
ExecStart=$cr_root/current/csqtt-server $cr_flags
$cr_open_unit
Restart=always
RestartSec=3
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
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
	run_cmd systemctl enable --now "$cr_unit" ||
		cr_fail "$cr_unit did not start: $(journalctl _SYSTEMD_UNIT="$cr_unit" -n 3 -o cat --no-pager 2>/dev/null | run_redact | tr '\n' ' ')"

	# verify: it runs, it alone holds its ports (SO_REUSEADDR makes a bind prove nothing), its TUN is up, the panel answers
	run_phase verify 80
	[ "$(systemctl is-active "$cr_unit")" = active ] || cr_fail "$cr_unit is not active"
	cr_pid=$(systemctl show -p MainPID --value "$cr_unit")
	for cr_check in "u $cr_udp UDP" "t $cr_web TCP"; do
		set -- $cr_check
		cr_holders=$(ss -H -l"$1"np "( sport = :$2 )" 2>/dev/null | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | sort -u | tr '\n' ' ')
		[ "$cr_holders" = "$cr_pid " ] || cr_fail "$3 $2 is held by pids ${cr_holders:-none} (ours: $cr_pid)"
	done
	ip link show "$cr_iface" >/dev/null 2>&1 || cr_fail "the server made no $cr_iface"
	if command -v curl >/dev/null 2>&1; then
		# the secret path only on curl's stdin, never in a process's arguments
		cr_try=0
		until printf 'url = "https://127.0.0.1:%s/%s/"\n' "$cr_web" "$(sed -n 's/^secret_path=//p' "$RUN_DIR/secrets")" |
			curl -sk -K - -o /dev/null -m 3 -w '%{http_code}' 2>/dev/null | grep -qE '^(200|301|302|401)$'; do
			cr_try=$((cr_try + 1))
			[ $cr_try -lt 10 ] || cr_fail "the panel does not answer on 127.0.0.1:$cr_web"
			sleep 1
		done
	fi
	run_phase "done" 100
	run_result ok "" "read the connection back"
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
*) csqtt_emit RUN error "unknown verb: ${1:-}"; csqtt_end; exit 2 ;;
esac
