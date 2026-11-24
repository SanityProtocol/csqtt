# shellcheck shell=dash
# SPDX-FileCopyrightText: 2026 SanityProtocol
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# «Вход без пароля» (CSQTT-APP-PLAN §6.3; P2 B2): the app's own key in the login user's authorized_keys, and only that line.
# The engine sends lib/marker.sh and then this file on stdin and runs, as the login user (never through sudo):
#   /bin/sh -s add <key type> <base64 key>   one line: restrict,port-forwarding,permitopen="127.0.0.1:*" <type> <key> sanityprotocol-csqtt
#                                            (no PTY, agent or X11 forwarding; forwarding only to this box's loopback, which
#                                            P4's panel tunnel needs)
#   /bin/sh -s remove <base64 key>           every line carrying that key, and no other
# The line is found by its key, never by its comment. Prints CSQTT_AK|added, present, removed or absent, then the end line.

ak_dir=$HOME/.ssh
ak_file=$ak_dir/authorized_keys

# what we create gets sshd's strict modes and, on an SELinux box, its label (a mislabelled file is one sshd cannot read,
# so the key would look installed and never work); what the user already had keeps its own mode and label
ak_label() {
	if command -v restorecon >/dev/null 2>&1; then restorecon "$1" 2>/dev/null || true; fi
}

# Fails closed: a step that did not happen ends the script with AK error and its reason, and the user's file is as it was.
ak_fail() {
	csqtt_emit AK error "$1"
	csqtt_end
	exit 1
}

case ${1:-} in
add)
	ak_type=${2:?key type}
	ak_key=${3:?key}
	if [ ! -d "$ak_dir" ]; then
		if ! mkdir -p "$ak_dir" || ! chmod 700 "$ak_dir"; then ak_fail "cannot create $ak_dir"; fi
		ak_label "$ak_dir"
	fi
	if [ -f "$ak_file" ] && grep -qF " $ak_key " "$ak_file"; then
		csqtt_emit AK present
	else
		ak_new=0
		[ -e "$ak_file" ] || ak_new=1
		# a last line with no newline would swallow ours
		if [ -s "$ak_file" ] && [ -n "$(tail -c 1 "$ak_file")" ]; then
			echo >>"$ak_file" || ak_fail "cannot write $ak_file"
		fi
		printf 'restrict,port-forwarding,permitopen="127.0.0.1:*" %s %s sanityprotocol-csqtt\n' "$ak_type" "$ak_key" >>"$ak_file" ||
			ak_fail "cannot write $ak_file"
		# read back: a write can fail without saying so (an immutable file, a full disk under BusyBox)
		grep -qF " $ak_key " "$ak_file" 2>/dev/null || ak_fail "the line did not reach $ak_file"
		if [ "$ak_new" = 1 ]; then
			chmod 600 "$ak_file"
			ak_label "$ak_file"
		fi
		csqtt_emit AK added
	fi
	;;
remove)
	ak_key=${2:?key}
	if [ -f "$ak_file" ] && grep -qF " $ak_key " "$ak_file"; then
		# What must remain is measured first, through a pipe, before anything is written: a full disk makes grep's write
		# fail (and BusyBox's grep exits 0 all the same), and an empty or cut-short copy moved over the file would take every
		# other key of this login with it.
		ak_want=$(grep -vF " $ak_key " "$ak_file" | wc -c)
		ak_want=$((ak_want))
		if [ "$ak_want" -eq 0 ]; then
			# ours was the only key: the file goes, and ~/.ssh too if nothing else is in it, so a login that had no keys
			# before «Вход без пароля» has none after (a file it once had empty goes with it: sshd reads both the same)
			rm -f "$ak_file" || ak_fail "cannot remove $ak_file"
			rmdir "$ak_dir" 2>/dev/null || true
		else
			# a whole new file, checked, then moved over the old one: it takes the old file's mode, and its label from the
			# directory it is made in, as the old one did
			ak_mode=$(stat -c %a "$ak_file" 2>/dev/null || echo 600)
			ak_tmp=$(mktemp "$ak_dir/.authorized_keys.XXXXXX") || ak_fail "cannot create a file in $ak_dir; $ak_file is unchanged"
			grep -vF " $ak_key " "$ak_file" >"$ak_tmp"
			ak_got=$(wc -c <"$ak_tmp")
			if [ "$((ak_got))" -ne "$ak_want" ] || ! chmod "$ak_mode" "$ak_tmp" || ! mv "$ak_tmp" "$ak_file"; then
				rm -f "$ak_tmp"
				ak_fail "could not rewrite $ak_file (is the disk full?); it is unchanged"
			fi
			ak_label "$ak_file"
		fi
		csqtt_emit AK removed
	else
		csqtt_emit AK absent
	fi
	;;
*)
	csqtt_emit AK error "unknown verb: ${1:-}"
	csqtt_end
	exit 2
	;;
esac
csqtt_end
