# shellcheck shell=dash
# SPDX-FileCopyrightText: 2026 SanityProtocol
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# The bundle's half of the line protocol (the engine's half: deploy/src/main/kotlin/com/csqtt/deploy/wire/LineCodec.kt).
# Sourced by inspect.sh and the engine. Everything the engine is meant to read goes through csqtt_emit:
#   csqtt_emit <KIND> [field…]   prints CSQTT_<KIND>|<field>|…, each field with %, |, CR and LF percent-escaped
#   csqtt_end                    prints CSQTT_END|<count>, the number of csqtt_emit lines before it
# POSIX sh (dash, BusyBox ash): no arrays, no bash-isms, and no fork per field (the escaping is parameter expansion).
#
# csqtt_emit must run in the main shell. The last stage of a pipeline (`cmd | while read …; do csqtt_emit …; done`) is a
# subshell, so its lines would print while the count stays behind, and the engine would reject the whole report as Broken.
# Feed such loops from a here-document instead: `while read …; do …; done <<EOF` … `$(cmd)` … `EOF`.

csqtt_marker_count=0
csqtt_nl='
'
csqtt_cr=$(printf '\r')

# csqtt_sub <value> <char> <replacement>: every <char> of <value> replaced; the result in $csqtt_sub_r
csqtt_sub() {
	csqtt_sub_in=$1
	csqtt_sub_r=
	while :; do
		case $csqtt_sub_in in
		*"$2"*)
			csqtt_sub_r=$csqtt_sub_r${csqtt_sub_in%%"$2"*}$3
			csqtt_sub_in=${csqtt_sub_in#*"$2"}
			;;
		*) break ;;
		esac
	done
	csqtt_sub_r=$csqtt_sub_r$csqtt_sub_in
}

# csqtt_esc <value>: the value with %, |, CR and LF escaped, in $csqtt_esc_r. % goes first, so the escapes' own % stay.
csqtt_esc() {
	csqtt_esc_r=$1
	case $csqtt_esc_r in
	*%* | *'|'* | *"$csqtt_cr"* | *"$csqtt_nl"*) ;;
	*) return 0 ;;
	esac
	csqtt_sub "$csqtt_esc_r" % %25
	csqtt_sub "$csqtt_sub_r" '|' %7C
	csqtt_sub "$csqtt_sub_r" "$csqtt_cr" %0D
	csqtt_sub "$csqtt_sub_r" "$csqtt_nl" %0A
	csqtt_esc_r=$csqtt_sub_r
}

csqtt_emit() {
	csqtt_emit_line=CSQTT_$1
	shift
	for csqtt_emit_f in "$@"; do
		csqtt_esc "$csqtt_emit_f"
		csqtt_emit_line=$csqtt_emit_line'|'$csqtt_esc_r
	done
	printf '%s\n' "$csqtt_emit_line"
	csqtt_marker_count=$((csqtt_marker_count + 1))
}

csqtt_end() {
	printf 'CSQTT_END|%s\n' "$csqtt_marker_count"
}
