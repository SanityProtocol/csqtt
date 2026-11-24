# shellcheck shell=dash
# SPDX-FileCopyrightText: 2026 SanityProtocol
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# deploy v3's inspection (CSQTT-APP-PLAN §6.4; P2 B3, B4). Root, read-only, under 15 s; the engine sends lib/marker.sh and
# then this file on stdin (`/bin/sh -s <host>…`), or, behind a sudo password, from a private temporary file. It reports
# FACTS and decides nothing: every verdict, stop and message is the engine's (deploy/…/inspect). POSIX sh: it must run on
# any box it meets — BusyBox included — if only to say why that box is not supported.
#   CSQTT_PRE|<check>|<key>=<value>|…    the environment (B3), one line per check
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
			pn_probe=failed
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
		iptables -w 5 -S FORWARD 2>/dev/null | grep -q DOCKER && pw_docker=1
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
		pw_nftdrop=$(nft list ruleset 2>/dev/null | awk '
			/^table / {t = $2 " " $3; ipt = ($2 == "ip" || $2 == "ip6") && ($3 ~ /^(filter|nat|mangle|raw|security)$/)}
			/^[ \t]*chain / {c = $2}
			/hook forward/ && /policy drop/ && !(ipt && c == "FORWARD") {d[t] = 1}
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

pre_reach() {
	for pr_host in "$@"; do
		pr_dns=0 pr_https=skipped
		if have getent; then getent ahosts "$pr_host" >/dev/null 2>&1 && pr_dns=1; fi
		if [ "$pr_dns" = 1 ]; then
			if have curl; then
				curl -s -o /dev/null -m 5 -I "https://$pr_host/" && pr_https=ok || pr_https=failed
			elif have wget; then
				wget -q -T 5 --spider "https://$pr_host/" 2>/dev/null && pr_https=ok || pr_https=failed
			fi
		fi
		csqtt_emit PRE reach "host=$pr_host" "dns=$pr_dns" "https=$pr_https"
	done
}

# the core runs from /opt/sanityprotocol/csqtt/<id>/releases/…, its state lives under /var/lib/sanityprotocol
pre_rundir() {
	pr_noexec=''
	for pr_dir in /opt /var/lib; do
		pr_opts=''
		have findmnt && pr_opts=$(findmnt -no OPTIONS --target "$pr_dir" 2>/dev/null)
		case ",$pr_opts," in *,noexec,*) pr_noexec="$pr_noexec$pr_dir," ;; esac
	done
	csqtt_emit PRE rundir "noexec=$pr_noexec"
}

# a docker CLI whose daemon hangs would hang the inspection: every call to it is bounded
tmo() {
	if have timeout; then timeout 5 "$@"; else "$@"; fi
}

pre_docker() {
	pdk_bin='' pdk_version='' pdk_flavour='' pdk_enabled='' pdk_rootless=0
	if have docker; then
		pdk_path=$(command -v docker)
		pdk_bin=$(readlink -f "$pdk_path" 2>/dev/null)
		pdk_version=$(tmo docker version --format '{{.Server.Version}}' 2>/dev/null || true)
		# snap's commands are links to /usr/bin/snap; podman-docker's is a script that runs podman
		case "$pdk_path:$pdk_bin:$(tmo docker --version 2>/dev/null | head -n 1)" in
		/snap/* | *:/usr/bin/snap:*) pdk_flavour=snap ;;
		*podman* | *Podman*) pdk_flavour=podman ;;
		*) pdk_flavour=docker ;;
		esac
		tmo docker info --format '{{.SecurityOptions}}' 2>/dev/null | grep -q rootless && pdk_rootless=1
		have systemctl && pdk_enabled=$(systemctl is-enabled docker 2>/dev/null || true)
	fi
	csqtt_emit PRE docker "bin=$pdk_bin" "version=$pdk_version" "flavour=$pdk_flavour" "enabled=$pdk_enabled" "rootless=$pdk_rootless"
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
csqtt_end
