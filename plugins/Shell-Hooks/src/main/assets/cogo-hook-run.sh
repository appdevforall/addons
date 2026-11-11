# Shell Hooks runner. Usage: sh cogo-hook-run.sh SCRIPT [ARG...]
#
# Runs SCRIPT with its arguments untouched. Android has no /usr/bin/env or /bin/bash, and the
# IDE's command service does not preload termux-exec, so a shebang naming /bin, /usr/bin or
# /usr/local/bin is looked up in $PREFIX/bin, as termux-exec would do. Any other interpreter path
# runs as written. POSIX sh only: this runs under Android's /system/bin/sh, not bash.

script=$1
shift

# The event's project, which for project-closed the IDE no longer has open.
if [ -n "${COGO_PROJECT_DIR:-}" ] && [ -d "$COGO_PROJECT_DIR" ]; then
	cd "$COGO_PROJECT_DIR" || exit 126
fi

# With COGO_TIMEOUT_SECONDS set and the Terminal's GNU timeout installed, the script runs under
# it: timeout puts the script in its own process group and stops the whole group at the limit
# (KILL 5 s after TERM), so programs the script started do not outlive it. Without it, the IDE's
# own timeout stops only the script's first process.
run() {
	if [ -n "${COGO_TIMEOUT_SECONDS:-}" ] && [ -n "${PREFIX:-}" ] && [ -f "$PREFIX/bin/timeout" ] && [ -x "$PREFIX/bin/timeout" ]; then
		exec "$PREFIX/bin/timeout" -k 5 "$COGO_TIMEOUT_SECONDS" "$@"
	fi
	exec "$@"
}

in_prefix() {
	[ -n "$1" ] && [ -n "${PREFIX:-}" ] && [ -f "$PREFIX/bin/$1" ] && [ -x "$PREFIX/bin/$1" ]
}

first=
IFS= read -r first < "$script" || :
cr=$(printf '\r')
first=${first%"$cr"}

trim() {
	value=$1
	while :; do
		case $value in
		[' 	']*) value=${value#?} ;;
		*[' 	']) value=${value%?} ;;
		*) break ;;
		esac
	done
	printf '%s' "$value"
}

case $first in
'#!'*)
	line=$(trim "${first#??}")
	interp=${line%%[' 	']*}
	arg=$(trim "${line#"$interp"}")
	case $interp in
	/bin/* | /usr/bin/* | /usr/local/bin/*)
		name=${interp##*/}
		if [ "$name" = env ]; then
			name=${arg%%[' 	']*}
			arg=$(trim "${arg#"$name"}")
		fi
		if in_prefix "$name"; then
			if [ -n "$arg" ]; then
				run "$PREFIX/bin/$name" "$arg" "$script" "$@"
			fi
			run "$PREFIX/bin/$name" "$script" "$@"
		fi
		;;
	esac
	# Any other interpreter, e.g. #!/system/bin/sh or a virtualenv's python: the kernel reads the
	# shebang as written.
	run "$script" "$@"
	;;
"$(printf '\177')ELF"*)
	run "$script" "$@"
	;;
esac

# No shebang: a shell script, as a terminal user would expect.
if in_prefix bash; then
	run "$PREFIX/bin/bash" "$script" "$@"
fi
run sh "$script" "$@"
