# shellcheck shell=dash
# SPDX-FileCopyrightText: 2026 SanityProtocol
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# deploy v3's inspection (CSQTT-APP-PLAN §6.4; P2 B3, B4). Root, read-only, under 15 s; the engine sends lib/marker.sh and
# then this file on stdin (`/bin/sh -s <host>…`), or, behind a sudo password, from a private temporary file. It reports
# FACTS and decides nothing: every verdict, stop and message is the engine's (deploy/…/inspect). POSIX sh: it must run on
# any box it meets — BusyBox included — if only to say why that box is not supported.
#   CSQTT_PRE|<check>|<key>=<value>|…    the environment (B3), one line per check
#   CSQTT_INV|<view>|<key>=<value>|…    the installations (B4): proc, exe, unit, dockerdeps, ctr, image, store, tun,
#                                        addr, route, iprule, listen, fw, nft, file
#   CSQTT_END|<count>
# The only writes are two probes, each made and removed at once: a scratch TUN and a scratch NAT chain (B3). Nothing big
# goes through csqtt_emit (marker.sh's escaping cost grows with the distance between special characters): file contents
# never, digests and named facts only.
# Arguments: the hosts the planned run needs to reach (github.com for a rootfs, the ACME server for a certificate); none
# for a plain inspection.

PATH=/usr/sbin:/usr/bin:/sbin:/bin:$PATH
export LC_ALL=C

have() { command -v "$1" >/dev/null 2>&1; }
# the first line of a command's output, or nothing; never fails
first() { "$@" 2>/dev/null | head -n 1 || true; }
readf() { [ -r "$1" ] && head -n 1 "$1" 2>/dev/null || true; }

pre_priv() {
	csqtt_emit PRE priv "uid=$(id -u 2>/dev/null)"
}

pre_running() {
	pr_lock=free
	if have flock && [ -e /run/sanity-csqtt-deploy.lock ]; then
		flock -n /run/sanity-csqtt-deploy.lock true 2>/dev/null || pr_lock=held
	fi
	pr_unit=''
	if have systemctl; then
		pr_unit=$(systemctl list-units --no-legend --plain 'sanity-csqtt-deploy-*' 2>/dev/null | awk 'NR==1 {print $1}')
	fi
	csqtt_emit PRE running "lock=$pr_lock" "unit=$pr_unit"
}

pre_os() {
	po_id='' po_version='' po_like='' po_name=''
	if [ -r /etc/os-release ]; then
		# shellcheck disable=SC1091
		po_id=$(. /etc/os-release && echo "${ID:-}")
		po_version=$(. /etc/os-release && echo "${VERSION_ID:-}")
		po_like=$(. /etc/os-release && echo "${ID_LIKE:-}")
		po_name=$(. /etc/os-release && echo "${PRETTY_NAME:-}")
	fi
	csqtt_emit PRE os "id=$po_id" "version=$po_version" "like=$po_like" "name=$po_name" "kernel=$(uname -r 2>/dev/null)"
}

pre_init() {
	pi_systemd=0
	[ -d /run/systemd/system ] && pi_systemd=1
	csqtt_emit PRE init "systemd=$pi_systemd" "version=$(first systemctl --version | awk '{print $2}')" \
		"pid1=$(readf /proc/1/comm)" "systemd_run=$(have systemd-run && echo 1 || echo 0)"
}

pre_declarative() {
	pd_kind=''
	[ -e /etc/NIXOS ] || [ -e /run/current-system ] && pd_kind=nixos
	[ -e /run/ostree-booted ] && pd_kind=ostree
	pd_ro=0
	if have findmnt; then
		case ",$(findmnt -no OPTIONS --target /etc/systemd/system 2>/dev/null)," in *,ro,*) pd_ro=1 ;; esac
	fi
	csqtt_emit PRE declarative "kind=$pd_kind" "etc_ro=$pd_ro"
}

pre_arch() {
	csqtt_emit PRE arch "machine=$(uname -m 2>/dev/null)"
}

pre_virt() {
	pv_virt=''
	have systemd-detect-virt && pv_virt=$(systemd-detect-virt 2>/dev/null || true)
	pv_wsl=0
	grep -qi microsoft /proc/version 2>/dev/null && pv_wsl=1
	csqtt_emit PRE virt "virt=${pv_virt:-none}" "wsl=$pv_wsl" "container=$(readf /run/systemd/container)"
}

# The two probes write: a scratch TUN, and scratch chains in the nat and mangle tables. Where the box allows a network
# namespace of our own (`unshare -n`: util-linux, or BusyBox's applet), they run inside one, so what they make goes with it,
# even when the inspection is cut off halfway, and iptables-nft's tables too: made in the box's own namespace, an empty `nat`
# or `mangle` table would stay behind. Where it does not (some containers), they run in the box's namespace and undo their
# own writes. The kernel is the same either way, so the answer is too.
probe_ns=0
if have unshare && unshare -n true 2>/dev/null; then probe_ns=1; fi

in_probe_ns() {
	if [ "$probe_ns" = 1 ]; then unshare -n "$@"; else "$@"; fi
}

pre_tun() {
	pt_dev=0 pt_probe=skipped pt_err=''
	[ -c /dev/net/tun ] && pt_dev=1
	if have ip; then
		pt_name=sqcprobe$$
		if pt_err=$(in_probe_ns ip tuntap add mode tun name "$pt_name" 2>&1); then
			pt_probe=ok
			[ "$probe_ns" = 1 ] || ip link del "$pt_name" 2>/dev/null || true
		else
			# BusyBox's own ip has no tuntap: its usage text says nothing about the box's TUN
			case $pt_err in *Usage:* | *BusyBox*) pt_probe=unsupported ;; *) pt_probe=failed ;; esac
			pt_err=$(printf '%s\n' "$pt_err" | head -n 1)
		fi
	fi
	csqtt_emit PRE tun "dev=$pt_dev" "probe=$pt_probe" "error=$pt_err" "ns=$probe_ns" \
		"module=$(grep -qw tun /proc/modules 2>/dev/null && echo loaded || echo absent)"
}

pre_nat() {
	pn_probe=skipped pn_err=''
	if have iptables; then
		pn_chain=SQC_PROBE_$$
		# what the server itself adds: a MASQUERADE in nat, an MSS clamp in mangle
		# shellcheck disable=SC2016 # expanded by the sh that runs it, with the chain as $1
		pn_script='iptables -w 5 -t nat -N "$1" && iptables -w 5 -t nat -A "$1" -j MASQUERADE &&
			iptables -w 5 -t mangle -N "$1" &&
			iptables -w 5 -t mangle -A "$1" -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu'
		if pn_err=$(in_probe_ns sh -c "$pn_script" sh "$pn_chain" 2>&1); then
			pn_probe=ok
			pn_err=''
		else
			# another program held the xtables lock past -w (fail2ban, a Docker start): busy, not "no NAT". By its words only:
			# iptables-nft exits 4 for an unusable nf_tables too (OpenVZ 7), and that box has no NAT (verify N1)
			pn_probe=failed
			case $pn_err in *"xtables lock"*) pn_probe=busy ;; esac
			pn_err=$(printf '%s\n' "$pn_err" | head -n 1)
		fi
		if [ "$probe_ns" = 0 ]; then
			for pn_table in nat mangle; do
				iptables -w 5 -t "$pn_table" -F "$pn_chain" 2>/dev/null || true
				iptables -w 5 -t "$pn_table" -X "$pn_chain" 2>/dev/null || true
			done
		fi
	fi
	csqtt_emit PRE nat "probe=$pn_probe" "error=$pn_err" "ns=$probe_ns" "iptables=$(have iptables && echo 1 || echo 0)"
}

pre_forwarding() {
	pf_w=0
	[ -w /proc/sys/net/ipv4/ip_forward ] && pf_w=1
	csqtt_emit PRE forwarding "ipv4=$(readf /proc/sys/net/ipv4/ip_forward)" "writable=$pf_w"
}

pre_ipv4() {
	p4_dev='' p4_via='' p4_src=''
	if have ip; then
		p4_dev=$(ip -4 route show default 2>/dev/null | awk 'NR==1 {for (i=1;i<NF;i++) if ($i=="dev") print $(i+1)}')
		p4_via=$(ip -4 route show default 2>/dev/null | awk 'NR==1 {for (i=1;i<NF;i++) if ($i=="via") print $(i+1)}')
		p4_src=$(ip -4 route get 1.1.1.1 2>/dev/null | awk 'NR==1 {for (i=1;i<NF;i++) if ($i=="src") print $(i+1)}')
	fi
	p4_addrs=''
	have ip && p4_addrs=$(ip -4 -o addr show scope global 2>/dev/null | awk '{printf "%s ", $4}')
	csqtt_emit PRE ipv4 "dev=$p4_dev" "via=$p4_via" "src=$p4_src" "addrs=$p4_addrs"
}

pre_port80() {
	p8_holder=''
	if have ss; then
		p8_holder=$(ss -H -ltnp 'sport = :80' 2>/dev/null | sed -n 's/.*users:(("\([^"]*\)".*/\1/p' | head -n 1)
	fi
	csqtt_emit PRE port80 "holder=$p8_holder" "ss=$(have ss && echo 1 || echo 0)"
}

pre_firewall() {
	pw_firewalld=inactive pw_ufw=absent pw_backend='' pw_docker=0 pw_nftdrop='' pw_mixed=0 pw_nftsvc=''
	have systemctl && pw_firewalld=$(systemctl is-active firewalld 2>/dev/null || true)
	if have ufw; then
		pw_ufw=$(ufw status 2>/dev/null | awk 'NR==1 {print $2}')
	fi
	if have iptables; then
		case "$(first iptables -V)" in *nf_tables*) pw_backend=nft ;; *legacy*) pw_backend=legacy ;; *) pw_backend=other ;; esac
		# iptables-save dumps the tables there are; `iptables -S FORWARD` would make a legacy box's filter table to answer
		iptables-save 2>/dev/null | grep -q '^-A FORWARD .*DOCKER' && pw_docker=1
		if have iptables-legacy-save && have iptables-nft-save; then
			pw_l=$(iptables-legacy-save 2>/dev/null | grep -c '^-A' || true)
			pw_n=$(iptables-nft-save 2>/dev/null | grep -c '^-A' || true)
			[ "${pw_l:-0}" -gt 0 ] && [ "${pw_n:-0}" -gt 0 ] && pw_mixed=1
		fi
	fi
	if have nft; then
		# tables with a chain on the forward hook whose policy drops: our iptables accept cannot outrank them
		# (iptables-nft's own FORWARD chain is not counted: our accept sits inside it, ahead of its policy; a native table that
		# only shares iptables' table name has its own lowercase chains and is)
		# -t: terse, without set elements (a 300k-element blocklist set costs seconds and hundreds of MB); only the families
		# routed traffic meets (a bridge table's forward hook never sees the tunnel's packets)
		pw_nftdrop=$(nft -t list ruleset 2>/dev/null | awk '
			/^table / {t = $2 " " $3; fam = ($2 == "ip" || $2 == "ip6" || $2 == "inet")
				ipt = ($2 == "ip" || $2 == "ip6") && ($3 ~ /^(filter|nat|mangle|raw|security)$/)}
			/^[ \t]*chain / {c = $2}
			fam && /hook forward/ && /policy drop/ && !(ipt && c == "FORWARD") {d[t] = 1}
			END {for (x in d) printf "%s,", x}')
	fi
	if have systemctl && [ "$(systemctl is-enabled nftables 2>/dev/null)" = enabled ]; then
		pw_nftsvc=enabled
		grep -qs 'flush ruleset' /etc/nftables.conf /etc/sysconfig/nftables.conf && pw_nftsvc=enabled-flushes
	fi
	csqtt_emit PRE firewall "firewalld=$pw_firewalld" "ufw=$pw_ufw" "backend=$pw_backend" "docker_forward=$pw_docker" \
		"nft_drop=$pw_nftdrop" "mixed=$pw_mixed" "nftables_service=$pw_nftsvc"
}

# the holder of a lock on a file, from /proc/locks by the file's inode: dpkg takes fcntl locks, which flock(1) cannot see,
# and walking every process's descriptors would fork once per descriptor (no fuser or lsof needed either)
lock_holder() {
	[ -e "$1" ] || return 0
	lh_ino=$(stat -c %i "$1" 2>/dev/null) || return 0
	lh_pid=$(awk -v ino=":$lh_ino" '$2 != "->" {n = length(ino); if (substr($6, length($6) - n + 1) == ino) {print $5; exit}}' /proc/locks 2>/dev/null)
	[ -n "$lh_pid" ] && echo "$lh_pid:$(readf "/proc/$lh_pid/comm")"
	return 0
}

pre_pkg() {
	pp_mgr='' pp_lock='' pp_audit=''
	if have apt-get; then
		pp_mgr=apt
		pp_lock=$(lock_holder /var/lib/dpkg/lock-frontend)
		[ -n "$pp_lock" ] || pp_lock=$(lock_holder /var/lib/dpkg/lock)
		pp_audit=$(dpkg --audit 2>/dev/null | grep -c . || true)
	elif have dnf; then
		pp_mgr=dnf
		# dnf keeps its holder's pid in the file itself
		pp_pid=$(readf /var/cache/dnf/metadata_lock.pid)
		[ -n "$pp_pid" ] && [ -d "/proc/$pp_pid" ] && pp_lock="$pp_pid:$(readf "/proc/$pp_pid/comm")"
	elif have yum; then
		pp_mgr=yum
	elif have pacman; then
		pp_mgr=pacman
		[ -e /var/lib/pacman/db.lck ] && pp_lock=db.lck
	fi
	pp_missing=''
	for pp_tool in ip ss iptables sha256sum flock systemd-run curl python3; do
		have "$pp_tool" || pp_missing="$pp_missing$pp_tool,"
	done
	csqtt_emit PRE pkg "manager=$pp_mgr" "lock=$pp_lock" "audit_lines=${pp_audit:-0}" "missing=$pp_missing"
}

pre_disk() {
	pk_root=$(df -Pk / 2>/dev/null | awk 'NR==2 {print $4}')
	pk_var=$(df -Pk /var/lib 2>/dev/null | awk 'NR==2 {print $4}')
	pk_opt=$(df -Pk /opt 2>/dev/null | awk 'NR==2 {print $4}')
	pk_docker=''
	[ -d /var/lib/docker ] && pk_docker=$(df -Pk /var/lib/docker 2>/dev/null | awk 'NR==2 {print $4}')
	csqtt_emit PRE disk "root_kb=$pk_root" "varlib_kb=$pk_var" "opt_kb=$pk_opt" "docker_kb=$pk_docker"
}

pre_ram() {
	csqtt_emit PRE ram "total_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)" \
		"available_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)" "swap_kb=$(awk '/^SwapTotal:/ {print $2}' /proc/meminfo)"
}

pre_clock() {
	csqtt_emit PRE clock "epoch=$(date -u +%s)" "ntp=$(first timedatectl show -p NTPSynchronized --value)"
}

# Each host's DNS and HTTPS, every call bounded: a dead resolver is what this check exists to report, and an unbounded wait
# (5 s per silent nameserver, wget's 20 tries) would cost the whole report its deadline. glibc's resolver is told to give
# each nameserver one try of 2 s, so a dead first one and a live second still answer inside the 8 s bound (verify N2); wget
# asks for headers only, bounded too (a full page at a trickle took 39 s, verify N3), and its exit 8 is the server's error
# answer: the host was reached. No host is started past 12 s: it is reported unchecked (dns empty), which is no finding.
pre_reach() {
	pr_start=$(date +%s)
	for pr_host in "$@"; do
		pr_dns='' pr_https=skipped
		if [ $(($(date +%s) - pr_start)) -lt 12 ]; then
			pr_dns=0
			if have getent; then tmo_s 8 env RES_OPTIONS='timeout:2 attempts:1' getent ahosts "$pr_host" >/dev/null 2>&1 && pr_dns=1; fi
			if [ "$pr_dns" = 1 ]; then
				if have curl; then
					curl -s -o /dev/null -m 5 -I "https://$pr_host/" && pr_https=ok || pr_https=failed
				elif have wget; then
					pr_rc=0
					tmo_s 8 wget -q -t 1 -T 5 --spider "https://$pr_host/" 2>/dev/null || pr_rc=$?
					case $pr_rc in 0 | 8) pr_https=ok ;; *) pr_https=failed ;; esac
				fi
			fi
		fi
		csqtt_emit PRE reach "host=$pr_host" "dns=$pr_dns" "https=$pr_https"
	done
}

# the core runs from /opt/sanityprotocol/csqtt/<id>/releases/…; nothing executes from /var/lib (scripts run as `sh <file>`)
pre_rundir() {
	pr_noexec=''
	if have findmnt; then
		case ",$(findmnt -no OPTIONS --target /opt 2>/dev/null)," in *,noexec,*) pr_noexec=/opt, ;; esac
	fi
	csqtt_emit PRE rundir "noexec=$pr_noexec"
}

# a docker CLI whose daemon hangs would hang the inspection: every call to it is bounded
tmo() {
	tmo_s 5 "$@"
}

tmo_s() {  # tmo_s <seconds> <command…>
	tm_s=$1
	shift
	if have timeout; then timeout "$tm_s" "$@"; else "$@"; fi
}

# The daemon is asked only when it runs: docker.socket starts a stopped Docker on the first connect, and with it every
# container with a restart policy, which an inspection never does. Its client says the flavour without a daemon.
docker_live() {
	[ "$(systemctl is-active docker 2>/dev/null)" = active ] || ! have systemctl
}

pre_docker() {
	pdk_bin='' pdk_version='' pdk_flavour='' pdk_enabled='' pdk_active='' pdk_rootless=0
	if have docker; then
		pdk_path=$(command -v docker)
		pdk_bin=$(readlink -f "$pdk_path" 2>/dev/null)
		if have systemctl; then
			pdk_enabled=$(systemctl is-enabled docker 2>/dev/null || true)
			pdk_active=$(systemctl is-active docker 2>/dev/null || true)
		fi
		docker_live && pdk_version=$(tmo docker version --format '{{.Server.Version}}' 2>/dev/null || true)
		# snap's commands are links to /usr/bin/snap; podman-docker's is a script that runs podman
		case "$pdk_path:$pdk_bin:$(tmo docker --version 2>/dev/null | head -n 1)" in
		/snap/* | *:/usr/bin/snap:*) pdk_flavour=snap ;;
		*podman* | *Podman*) pdk_flavour=podman ;;
		*) pdk_flavour=docker ;;
		esac
		if docker_live && tmo docker info --format '{{.SecurityOptions}}' 2>/dev/null | grep -q rootless; then pdk_rootless=1; fi
	fi
	csqtt_emit PRE docker "bin=$pdk_bin" "version=$pdk_version" "flavour=$pdk_flavour" "enabled=$pdk_enabled" \
		"active=$pdk_active" "rootless=$pdk_rootless"
}

# ==========================================================================================================================
# The installations (APP-PLAN §6.4b, server-footprints.md §3; P2 B4): five views, reported raw as CSQTT_INV lines, which the
# engine joins into installs (B5). Read-only, like everything above. No foreign binary is ever run: a binary is known by its
# sha256 and the strings in it. No store is opened: its schema is read with grep -a, so no client's password is read or
# copied. Nothing secret leaves the box: env files go by key name, argv by the server's own flags only. Every loop that emits
# reads a here-document, never a pipe: a pipeline's loop runs in a subshell, and the lines it counted would be missing from
# csqtt_end's count.

inv_us=$(printf '\037')
inv_dirs=''

# The server's own flags and their values, from argv words on stdin, one per line; every other word is dropped (a hand-made
# unit may carry --password).
inv_flags() {
	awk 'BEGIN {n = split("--listen --web-port --web-bind --config-dir --iface --tun-addr --instance --instance-slot --desired", v, " ")
		for (i = 1; i <= n; i++) val[v[i]] = 1; bare["--no-web"] = 1; bare["--no-nat"] = 1}
		take {out = out " " $0; take = 0; next}
		{f = $0; sub(/=.*/, "", f)}
		$0 in bare {out = out " " $0; next}
		f in val {out = out " " $0; if (f == $0) take = 1}
		END {sub(/^ /, "", out); print out}'
}

# remembers a config dir a process, unit or container names, for the stores view
inv_note_dir() {
	ind=$(printf '%s\n' "$1" | awk '{for (i = 1; i <= NF; i++) if ($i == "--config-dir") print $(i + 1); else if ($i ~ /^--config-dir=/) {sub(/^--config-dir=/, "", $i); print $i}}')
	[ -n "$ind" ] && inv_dirs="$inv_dirs $ind"
	return 0
}

# One exe line per distinct binary (by sha256), with the strings that name a csqtt build (server-footprints.md §2), read in
# one pass: the panel's version label, our caption, the startup line, swg-panel's flag help, MRTT's wire revision. Leaves the
# sha in inv_sha for the caller.
inv_seen=''
inv_exe() {  # inv_exe <file to read> <path to report>
	inv_sha=$(sha256sum "$1" 2>/dev/null | cut -d' ' -f1)
	[ -n "$inv_sha" ] || return 0
	case " $inv_seen " in *" $inv_sha "*) return 0 ;; esac
	inv_seen="$inv_seen $inv_sha"
	ie_label='' ie_startup='' ie_caption=0 ie_swg=0 ie_mrtt=0
	ie_sig=$(grep -a -o -E 'aria-label="v[0-9][^"]{0,30} by amurcanov"|CSQTT Server [0-9][0-9A-Za-z .()+-]{0,70}|swg-panel: TUN interface name|MRTT-WIRE-3|сборка SanityProtocol' "$1" 2>/dev/null | sort -u)
	while IFS= read -r ie_s; do
		case $ie_s in
		aria-label=*) ie_label=${ie_s#aria-label=\"}; ie_label=${ie_label% by amurcanov\"} ;;
		"CSQTT Server "*) ie_startup=${ie_s#CSQTT Server } ;;
		"swg-panel: TUN interface name") ie_swg=1 ;;
		MRTT-WIRE-3) ie_mrtt=1 ;;
		*SanityProtocol) ie_caption=1 ;;
		esac
	done <<EOF
$ie_sig
EOF
	csqtt_emit INV exe "sha=$inv_sha" "path=$2" "size=$(wc -c <"$1" 2>/dev/null | tr -d ' ')" "label=$ie_label" \
		"caption=$ie_caption" "startup=$ie_startup" "swg=$ie_swg" "mrtt=$ie_mrtt"
}

# View 1, processes: a csqtt or mrtt binary by name, and every TUN holder (a foreign one still claims its interface).
inv_procs() {
	# every process's exe in one ls (a readlink each would fork once per process); kernel threads have none
	ip_exes=$(ls -l /proc/[0-9]*/exe 2>/dev/null | awk -v us="$inv_us" '{for (i = 2; i < NF; i++) if ($i == "->") {
		p = $(i - 1); sub("^/proc/", "", p); sub("/exe$", "", p); print p us $(i + 1) us (($NF == "(deleted)") ? 1 : 0); break}}')
	# every open /dev/net/tun in one ls, then its interface from fdinfo
	ip_fds=$(ls -l /proc/[0-9]*/fd/ 2>/dev/null | awk '
		/^\/proc\/[0-9]+\/fd\/?:$/ {pid = $0; sub("^/proc/", "", pid); sub("/fd/?:$", "", pid); next}
		$NF == "/dev/net/tun" {for (i = 2; i < NF; i++) if ($i == "->") print "/proc/" pid "/fdinfo/" $(i - 1)}')
	ip_holders=''
	# shellcheck disable=SC2086 # one path per word
	[ -z "$ip_fds" ] || ip_holders=$(awk -v us="$inv_us" '/^iff:/ {f = FILENAME; sub("^/proc/", "", f); sub("/fdinfo/.*", "", f); print f us $2}' $ip_fds 2>/dev/null)
	ip_pids=$( { printf '%s\n' "$ip_exes" | awk -F"$inv_us" '{n = $2; sub(".*/", "", n)} n ~ /csqtt|mrtt/ {print $1}'
		printf '%s\n' "$ip_holders" | awk -F"$inv_us" 'NF > 1 {print $1}'; } | sort -un)
	while IFS= read -r ip_pid; do
		[ -n "$ip_pid" ] && [ -d "/proc/$ip_pid" ] || continue
		ip_exe=$(printf '%s\n' "$ip_exes" | awk -F"$inv_us" -v p="$ip_pid" '$1 == p {print $2; exit}')
		ip_del=$(printf '%s\n' "$ip_exes" | awk -F"$inv_us" -v p="$ip_pid" '$1 == p {print $3; exit}')
		ip_tun=$(printf '%s\n' "$ip_holders" | awk -F"$inv_us" -v p="$ip_pid" '$1 == p {printf "%s%s", s, $2; s = ","}')
		ip_cg=$(cat "/proc/$ip_pid/cgroup" 2>/dev/null)
		ip_ctr=$(printf '%s\n' "$ip_cg" | sed -n -E 's/.*(docker|libpod)[-/]([0-9a-f]{64}).*/\2/p' | head -n 1)
		ip_unit=''
		[ -n "$ip_ctr" ] || ip_unit=$(printf '%s\n' "$ip_cg" | sed -n -E 's#.*/([^/]+\.service)(/.*)?$#\1#p' | head -n 1)
		ip_flags=$(tr '\0' '\n' <"/proc/$ip_pid/cmdline" 2>/dev/null | inv_flags)
		inv_note_dir "$ip_flags"
		inv_sha=''
		case ${ip_exe##*/} in *csqtt* | *mrtt*) inv_exe "/proc/$ip_pid/exe" "$ip_exe" ;; esac
		csqtt_emit INV proc "pid=$ip_pid" "exe=$ip_exe" "deleted=${ip_del:-0}" "comm=$(readf "/proc/$ip_pid/comm")" \
			"unit=$ip_unit" "ctr=$(printf '%.12s' "$ip_ctr")" "tun=$ip_tun" "sha=$inv_sha" "flags=$ip_flags"
		[ -z "$ip_unit" ] || inv_proc_units="$inv_proc_units $ip_unit"
		[ -z "$ip_ctr" ] || inv_proc_ctrs="$inv_proc_ctrs $ip_ctr"
	done <<EOF
$ip_pids
EOF
}

# View 2, systemd: the family's units and timers by name, and every unit a process above runs in, whatever its name.
inv_units() {
	have systemctl || return 0
	iu_names=$( { { systemctl list-unit-files --no-legend --plain --type=service,timer 2>/dev/null
		systemctl list-units --all --no-legend --plain --type=service,timer 2>/dev/null; } | awk '{print $1}' | grep -E 'csqtt|mrtt|swg-noded'
		for iu in $inv_proc_units; do echo "$iu"; done; } | sort -u)
	while IFS= read -r iu; do
		[ -n "$iu" ] || continue
		iu_line=$(systemctl show "$iu" -p LoadState,ActiveState,SubState,Result,ExecMainStatus,NRestarts,MainPID,UnitFileState,FragmentPath,ExecStart,ExecStartPre,EnvironmentFiles 2>/dev/null | awk -v us="$inv_us" '
			{k = $0; sub(/=.*/, "", k); v = substr($0, length(k) + 2)}
			k == "ExecStart" && path == "" {if (match(v, /path=[^ ;]+/)) path = substr(v, RSTART + 5, RLENGTH - 5)
				if (match(v, /argv\[\]=[^;]*/)) argv = substr(v, RSTART + 7, RLENGTH - 7)}
			k == "ExecStartPre" {s = v; while (match(s, /path=[^ ;]+/)) {pre = pre (pre == "" ? "" : ",") substr(s, RSTART + 5, RLENGTH - 5)
				s = substr(s, RSTART + RLENGTH)}
				s = v; while (match(s, /status=[0-9]+/)) {st = substr(s, RSTART + 7, RLENGTH - 7); if (st != "0") prest = st; s = substr(s, RSTART + RLENGTH)}}
			k == "EnvironmentFiles" && v != "" {sub(/ \(.*/, "", v); env = env (env == "" ? "" : ",") v}
			{f[k] = v}
			END {print f["LoadState"] us f["ActiveState"] us f["SubState"] us f["Result"] us f["ExecMainStatus"] us f["NRestarts"] us \
				f["MainPID"] us f["UnitFileState"] us f["FragmentPath"] us path us argv us pre us env us prest}')
		IFS=$inv_us read -r iu_load iu_active iu_sub iu_result iu_status iu_restarts iu_pid iu_file iu_frag iu_path iu_argv iu_pre iu_env iu_prest <<EOF
$iu_line
EOF
		iu_flags=$(printf '%s\n' "$iu_argv" | tr ' ' '\n' | inv_flags)
		inv_note_dir "$iu_flags"
		iu_ok=''
		[ -z "$iu_path" ] || { [ -e "$iu_path" ] && iu_ok=1 || iu_ok=0; }
		csqtt_emit INV unit "id=$iu" "load=$iu_load" "active=$iu_active" "sub=$iu_sub" "result=$iu_result" "status=$iu_status" \
			"restarts=$iu_restarts" "pid=$iu_pid" "file_state=$iu_file" "fragment=$iu_frag" "exec=$iu_path" "exec_ok=$iu_ok" \
			"flags=$iu_flags" "pre=$iu_pre" "pre_status=$iu_prest" "env=$iu_env"
	done <<EOF
$iu_names
EOF
	# B14: a unit docker.service requires; without its helper it takes Docker down at the next boot
	iu_req='' iu_wants=''
	for iu_f in /etc/systemd/system/docker.service.requires/* /etc/systemd/system/docker.service.wants/*; do
		[ -e "$iu_f" ] || [ -L "$iu_f" ] || continue
		case $iu_f in
		*.requires/*) iu_req="$iu_req${iu_req:+,}${iu_f##*/}" ;;
		*) iu_wants="$iu_wants${iu_wants:+,}${iu_f##*/}" ;;
		esac
	done
	csqtt_emit INV dockerdeps "requires=$iu_req" "wants=$iu_wants"
}

# View 3, containers (docker, or podman where there is no docker): the family's by name or image, and every container a
# process above runs in; then the family's images.
inv_ctrs() {
	for ic_rt in docker podman; do
		have $ic_rt || continue
		if [ "$ic_rt" = docker ] && ! docker_live; then
			# a stopped Docker is not asked (its socket would start it, and its containers with it): they stay unknown, and
			# the engine is told so (podman, daemonless, is asked next)
			if [ -e /lib/systemd/system/docker.service ] || [ -e /usr/lib/systemd/system/docker.service ] ||
				[ -e /etc/systemd/system/docker.service ]; then
				csqtt_emit INV docker "daemon=inactive"
			fi
			continue
		fi
		ic_all=$(tmo $ic_rt ps -a --no-trunc --format "{{.ID}}$inv_us{{.Names}}$inv_us{{.Image}}$inv_us{{.State}}" 2>/dev/null)
		while IFS=$inv_us read -r ic_id ic_name ic_image ic_state; do
			[ -n "$ic_id" ] || continue
			ic_want=0
			case "$ic_name $ic_image" in *csqtt* | *mrtt* | *swg-node* | *sanity*) ic_want=1 ;; esac
			case " $inv_proc_ctrs " in *" $ic_id "*) ic_want=1 ;; esac
			[ "$ic_want" = 1 ] || continue
			ic_line=$(tmo $ic_rt inspect -f "{{.State.Pid}}$inv_us{{.RestartCount}}$inv_us{{.State.ExitCode}}$inv_us{{.HostConfig.RestartPolicy.Name}}$inv_us{{.HostConfig.NetworkMode}}$inv_us{{range \$k, \$v := .Config.Labels}}{{\$k}}={{\$v}},{{end}}$inv_us{{json .Config.Entrypoint}}$inv_us{{json .Config.Cmd}}$inv_us{{range .Mounts}}{{.Source}}:{{.Destination}},{{end}}" "$ic_id" 2>/dev/null)
			IFS=$inv_us read -r ic_pid ic_restarts ic_exit ic_policy ic_net ic_labels ic_ep ic_cmd ic_mounts <<EOF
$ic_line
EOF
			ic_labels=$(printf '%s\n' "$ic_labels" | tr ',' '\n' | grep -E '^(com\.csqtt|com\.mrtt|net\.sanityprotocol|com\.sanityprotocol)' | tr '\n' ',')
			ic_flags=$(printf '%s\n%s\n' "$ic_ep" "$ic_cmd" | awk -F'","' '{gsub(/^\["?|"?\]$|^null$/, ""); for (i = 1; i <= NF; i++) if ($i != "") print $i}' | inv_flags)
			inv_note_dir "$ic_flags"
			# a bind mount's host side is a store, if it holds one
			for ic_m in $(printf '%s\n' "$ic_mounts" | tr ',' ' '); do inv_dirs="$inv_dirs ${ic_m%%:*}"; done
			csqtt_emit INV ctr "id=$(printf '%.12s' "$ic_id")" "name=$ic_name" "image=$ic_image" "state=$ic_state" "pid=$ic_pid" \
				"restarts=$ic_restarts" "exit=$ic_exit" "policy=$ic_policy" "net=$ic_net" "labels=${ic_labels%,}" \
				"flags=$ic_flags" "mounts=${ic_mounts%,}" "runtime=$ic_rt"
		done <<EOF
$ic_all
EOF
		ic_imgs=$(tmo $ic_rt images --no-trunc --format "{{.Repository}}:{{.Tag}}$inv_us{{.ID}}" 2>/dev/null | grep -E 'csqtt|mrtt|sanity|swg-node')
		while IFS=$inv_us read -r ic_ref ic_iid; do
			[ -n "$ic_ref" ] || continue
			csqtt_emit INV image "ref=$ic_ref" "id=$ic_iid" "runtime=$ic_rt"
		done <<EOF
$ic_imgs
EOF
		break
	done
}

# View 4, stores: every directory holding a csqtt or mrtt store, config or lock, in the family's places and wherever a
# process, unit or container pointed.
inv_stores() {
	is_seen=' '
	for is_d in /etc/csqtt* /etc/mrtt* /opt/swg-csqtt/* /var/lib/swg-noded/csqtt/* /opt/swg-panel-docker/data/node/csqtt/* \
		/var/lib/sanityprotocol/csqtt/* $inv_dirs; do
		[ -d "$is_d" ] || continue
		is_d=${is_d%/}
		case $is_seen in *" $is_d "*) continue ;; esac
		is_seen="$is_seen$is_d "
		is_files=''
		for is_f in csqtt.db csqtt.db-wal passwords.json passwords.json.imported csqtt.env env .server.lock deploy-overrides.json \
			web_cert.pem letsencrypt.env manifest.json runtime-archive docker mrtt.db mrtt.db-wal mrtt.env; do
			[ -e "$is_d/$is_f" ] && is_files="$is_files${is_files:+,}$is_f"
		done
		[ -n "$is_files" ] || continue
		is_lock=none
		if [ -e "$is_d/.server.lock" ]; then
			is_lock=unknown
			have flock && { flock -n "$is_d/.server.lock" true 2>/dev/null && is_lock=free || is_lock=held; }
		fi
		is_schema=''
		for is_db in "$is_d/csqtt.db" "$is_d/csqtt.db-wal" "$is_d/mrtt.db" "$is_d/mrtt.db-wal"; do
			[ -f "$is_db" ] || continue
			is_schema="$is_schema $(grep -a -o -E 'CREATE TABLE "?[a-z_]+"?|device_id|devices_json|device_limit|max_devices' "$is_db" 2>/dev/null |
				sed -E 's/CREATE TABLE "?([a-z_]+)"?/table:\1/' | tr '\n' ' ')"
		done
		is_schema=$(printf '%s\n' $is_schema | sort -u | tr '\n' ',')
		is_keys='' is_ip='' is_domain=''
		for is_e in "$is_d/csqtt.env" "$is_d/mrtt.env" "$is_d/env" "$is_d/letsencrypt.env"; do
			[ -f "$is_e" ] || continue
			is_keys="$is_keys $(sed -n -E 's/^(export )?([A-Za-z_][A-Za-z0-9_]*)=.*/\2/p' "$is_e" | tr '\n' ' ')"
			is_v=$(sed -n -E 's/^(export )?(CSQTT|MRTT)_PUBLIC_IP=//p' "$is_e" | head -n 1 | tr -d "\"'")
			[ -z "$is_v" ] || is_ip=$is_v
			is_v=$(sed -n -E 's/^(export )?(CSQTT_)?DOMAIN=//p' "$is_e" | head -n 1 | tr -d "\"'")
			[ -z "$is_v" ] || is_domain=$is_v
		done
		is_keys=$(printf '%s\n' $is_keys | sort -u | tr '\n' ',')
		csqtt_emit INV store "dir=$is_d" "files=$is_files" "lock=$is_lock" "schema=${is_schema%,}" "env_keys=${is_keys%,}" \
			"public_ip=$is_ip" "domain=$is_domain"
	done
}

# View 5, network: TUN devices, every IPv4 address and route (a new subnet must overlap none), listeners, the family's
# netfilter rules and chains (by tag, by its interfaces, by its subnets), its policy rules, and the nft tables.
inv_net() {
	if have ip; then
		in_tuns=$(ip -d -o link show 2>/dev/null | awk -v us="$inv_us" '/ tun type tun / {n = $2; sub(/:$/, "", n); sub(/@.*/, "", n)
			st = ""; ps = ""; for (i = 1; i < NF; i++) {if ($i == "state") st = $(i + 1); if ($i == "persist") ps = $(i + 1)}
			print n us st us ps}')
		while IFS=$inv_us read -r in_n in_st in_ps; do
			[ -n "$in_n" ] || continue
			csqtt_emit INV tun "name=$in_n" "state=$in_st" "persist=$in_ps"
		done <<EOF
$in_tuns
EOF
		in_addrs=$(ip -4 -o addr show 2>/dev/null | awk -v us="$inv_us" '{print $2 us $4}')
		while IFS=$inv_us read -r in_dev in_cidr; do
			[ -n "$in_dev" ] || continue
			csqtt_emit INV addr "dev=$in_dev" "cidr=$in_cidr"
		done <<EOF
$in_addrs
EOF
		in_routes=$(ip -4 route show table all 2>/dev/null | awk -v us="$inv_us" '$1 != "default" && $1 != "broadcast" && $1 != "local" && $1 ~ /^[0-9]/ {
			d = ""; t = "main"; for (i = 2; i < NF; i++) {if ($i == "dev") d = $(i + 1); if ($i == "table") t = $(i + 1)}; print $1 us d us t}' | sort -u)
		while IFS=$inv_us read -r in_dst in_dev in_tab; do
			[ -n "$in_dst" ] || continue
			csqtt_emit INV route "dst=$in_dst" "dev=$in_dev" "table=$in_tab"
		done <<EOF
$in_routes
EOF
		in_rules=$(ip rule 2>/dev/null | grep -E 'lookup (1066|30001|30002|20[0-9][0-9]|70[0-9][0-9])( |$)|fwmark 0x(422|7531|7532)')
		while IFS= read -r in_r; do
			[ -n "$in_r" ] || continue
			csqtt_emit INV iprule "rule=$in_r"
		done <<EOF
$in_rules
EOF
	fi
	if have ss; then
		in_listen=$( { ss -H -lunp 2>/dev/null | sed 's/^/udp /'; ss -H -ltnp 2>/dev/null | sed 's/^/tcp /'; } | awk -v us="$inv_us" '{
			l = $5; port = l; sub(/.*:/, "", port); addr = l; sub(/:[^:]*$/, "", addr); gsub(/[][]/, "", addr)
			c = ""; p = ""; if (match($0, /users:\(\("[^"]*",pid=[0-9]+/)) {u = substr($0, RSTART + 9, RLENGTH - 9); c = u; sub(/".*/, "", c); p = u; sub(/.*pid=/, "", p)}
			print $1 us addr us port us c us p}' | sort -u)
		while IFS=$inv_us read -r in_proto in_addr in_port in_comm in_pid; do
			[ -n "$in_proto" ] || continue
			csqtt_emit INV listen "proto=$in_proto" "addr=$in_addr" "port=$in_port" "comm=$in_comm" "pid=$in_pid"
		done <<EOF
$in_listen
EOF
	fi
	for in_v in 4 6; do
		in_save=iptables-save
		[ "$in_v" = 4 ] || in_save=ip6tables-save
		have $in_save || continue
		in_fw=$($in_save 2>/dev/null | awk -v us="$inv_us" -v v="$in_v" '
			/^\*/ {t = substr($0, 2); next}
			/^:/ && /^:(SQC-|CSQTT|MRTT|SANITY)/ {c = substr($1, 2); print v us t us "chain" us c; next}
			/^-A / && ((/--comment/ && /CSQTT|MRTT|_MANAGED|_TPROXY|_OPEN|SQC-|swg/) || /10\.66\.6[78]\.0\/24|10\.77\./ || / -[io] (csqtt|mrtt|sqc)/) {print v us t us "rule" us $0}')
		while IFS=$inv_us read -r in_fv in_ft in_fk in_fr; do
			[ -n "$in_fv" ] || continue
			csqtt_emit INV fw "v=$in_fv" "table=$in_ft" "kind=$in_fk" "text=$in_fr"
		done <<EOF
$in_fw
EOF
	done
	have nft && csqtt_emit INV nft "tables=$(nft list tables 2>/dev/null | sed 's/^table //' | tr '\n' ',' | sed 's/,$//')"
	return 0
}

# Files that are claims or leftovers of the family: sysctl drop-ins, binaries and helpers (helpers with the strings that tell
# ours, slim and the side tool apart), certbot venvs and certificates, install logs, the stock installer's leftovers in /tmp
# (which hold passwords: their presence only), unit files.
inv_files() {
	for if_p in /etc/sysctl.d/*csqtt* /etc/sysctl.d/*mrtt* /etc/modules-load.d/bbr.conf /usr/local/bin/csqtt* /usr/local/bin/mrtt* \
		/usr/local/lib/csqtt* /usr/local/lib/csqtt*/* /usr/local/lib/mrtt* /usr/local/lib/mrtt*/* /opt/csqtt*-certbot \
		/etc/letsencrypt/live/csqtt-* /etc/letsencrypt/renewal/csqtt-* /var/log/csqtt*-install.log /var/log/mrtt-install.log \
		/tmp/deploy.sh /tmp/.csqtt-upload-* /tmp/.mrtt-upload-* /etc/systemd/system/*csqtt* /etc/systemd/system/*mrtt* \
		/run/csqtt* /run/mrtt* /etc/mrtt-exit /tmp/legacy-uninstall.*.sh /tmp/csqtt-docker.* \
		/opt/sanityprotocol/csqtt/* /var/lib/sanityprotocol/csqtt/* \
		/var/lib/sanityprotocol/csqtt-deploy/*; do
		[ -e "$if_p" ] || [ -L "$if_p" ] || continue
		if_t=f
		[ -d "$if_p" ] && if_t=d
		[ -L "$if_p" ] && if_t=l
		set -- "path=$if_p" "type=$if_t"
		case $if_p in
		*.sh)
			set -- "$@" "sha=$(sha256sum "$if_p" 2>/dev/null | cut -d' ' -f1)" "role=$(grep -c 'CSQTT_ROLE=' "$if_p" 2>/dev/null)" \
				"exit21=$(grep -c 'exit 21' "$if_p" 2>/dev/null)" "notsent=$(grep -c tcp_notsent_lowat "$if_p" 2>/dev/null)" \
				"version=$(sed -n -E 's/^SCRIPT_VERSION="?([^" ]*).*/\1/p' "$if_p" 2>/dev/null | head -n 1)" ;;
		*-install.log)
			# the last run finished when, after its header, a successful install logged its total time or an uninstall its
			# removal (server-footprints.md §4 B11, every version; status and uninstall runs write a header too)
			if_run=$(awk '/^=== / {fin = 0; bad = 0; next} /Общее время deploy\.sh|удалён/ {fin = 1} /\[✗\]/ {bad++}
				END {print fin + 0, bad + 0}' "$if_p" 2>/dev/null)
			set -- "$@" "runs=$(grep -c '^=== ' "$if_p" 2>/dev/null)" "last_run=$(grep '^=== ' "$if_p" 2>/dev/null | tail -n 1 | cut -c1-100)" \
				"last_line=$(tail -n 1 "$if_p" 2>/dev/null | cut -c1-100)" "finished=${if_run%% *}" "failed_steps=${if_run##* }" ;;
		esac
		[ "$if_t" = l ] && set -- "$@" "target=$(readlink "$if_p" 2>/dev/null)"
		csqtt_emit INV file "$@"
	done
}

inv_all() {
	inv_proc_units='' inv_proc_ctrs=''
	inv_procs
	inv_units
	inv_ctrs
	inv_stores
	inv_net
	inv_files
	# our own binaries, running or not
	for io_b in /opt/sanityprotocol/csqtt/*/current/csqtt-server /usr/local/bin/csqtt /usr/local/lib/csqtt25/csqtt25 /usr/local/bin/mrtt; do
		[ -f "$io_b" ] && inv_exe "$io_b" "$io_b"
	done
	return 0
}

pre_priv
pre_running
pre_os
pre_init
pre_declarative
pre_arch
pre_virt
pre_tun
pre_nat
pre_forwarding
pre_ipv4
pre_port80
pre_firewall
pre_pkg
pre_disk
pre_ram
pre_clock
pre_reach "$@"
pre_rundir
pre_docker
inv_all
csqtt_end
