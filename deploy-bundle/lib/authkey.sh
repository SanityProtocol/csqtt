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

case ${1:-} in
add)
	ak_type=${2:?key type}
	ak_key=${3:?key}
	if [ ! -d "$ak_dir" ]; then
		mkdir -p "$ak_dir"
		chmod 700 "$ak_dir"
		ak_label "$ak_dir"
	fi
	if [ -f "$ak_file" ] && grep -qF " $ak_key " "$ak_file"; then
		csqtt_emit AK present
	else
		ak_new=0
		[ -e "$ak_file" ] || ak_new=1
		# a last line with no newline would swallow ours
		if [ -s "$ak_file" ] && [ -n "$(tail -c 1 "$ak_file")" ]; then echo >>"$ak_file"; fi
		printf 'restrict,port-forwarding,permitopen="127.0.0.1:*" %s %s sanityprotocol-csqtt\n' "$ak_type" "$ak_key" >>"$ak_file"
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
		# a whole new file moved over the old one, so a cut-short write can never leave the login without its keys; it
		# takes the old file's mode, and its label from the directory it is made in, as the old one did
		ak_mode=$(stat -c %a "$ak_file" 2>/dev/null || echo 600)
		ak_tmp=$(mktemp "$ak_dir/.authorized_keys.XXXXXX")
		grep -vF " $ak_key " "$ak_file" >"$ak_tmp" || true
		chmod "$ak_mode" "$ak_tmp"
		mv "$ak_tmp" "$ak_file"
		ak_label "$ak_file"
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
