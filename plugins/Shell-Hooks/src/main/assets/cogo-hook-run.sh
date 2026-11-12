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
# (KILL 5 s after TERM), so programs the script started do not outlive it. timeout runs as a child,
# not in place of this shell: the IDE stops a hook (on disable, unload or update) by killing the
# process it started, and if that were timeout the script would run on with no limit. A watchdog
# notices this shell is gone and sends timeout TERM, which stops the whole group the same way.
# Without timeout, the IDE's own stop ends only the script's first process.
run() {
	if [ -n "${COGO_TIMEOUT_SECONDS:-}" ] && in_prefix timeout; then
		"$PREFIX/bin/timeout" -k 5 "$COGO_TIMEOUT_SECONDS" "$@" &
		child=$!
		# Its output goes nowhere, so it never keeps the IDE's pipes open. Redirected with exec inside
		# a subshell: dash and busybox, given `watch ... >/dev/null &`, keep a saved copy of the
		# original stdout open in the background job. ($$ in a subshell is still this wrapper.)
		(exec </dev/null >/dev/null 2>&1; watch "$$" "$child") &
		watchdog=$!
		wait "$child"
		status=$?
		kill "$watchdog" 2>/dev/null
		exit "$status"
	fi
	# Without timeout the script would share the IDE's process group, so one that signals its own
	# group (`trap 'kill 0' EXIT`) would signal Code on the Go. setsid gives it a session and group
	# of its own; as this shell does not lead its group, setsid does not fork, and the script keeps
	# this process ID, which the IDE stops it by. Not when run in the Terminal (input from a
	# terminal): there the script stays in the Terminal's foreground group, so Ctrl-C reaches it.
	if [ ! -t 0 ] && shares_group; then
		if in_prefix setsid; then
			exec "$PREFIX/bin/setsid" "$@"
		fi
		if command -v setsid >/dev/null 2>&1; then
			exec setsid "$@"
		fi
	fi
	exec "$@"
}

# True when this shell is in a process group it does not lead, such as the IDE's.
shares_group() {
	[ -r "/proc/$$/stat" ] || return 1
	IFS= read -r stat < "/proc/$$/stat" || return 1
	# Fields after the command name, which may itself hold spaces or parentheses: state, ppid, pgrp.
	set -- ${stat##*") "}
	[ "${3:-}" != "$$" ]
}

# Waits while process $1 (this wrapper) lives, then sends TERM to $2 (timeout). The wrapper sends
# it TERM when the script ends normally; the trap only notes that, so the loop never signals a
# sleep it has already waited for, whose ID may belong to another process by then. A TERM that
# interrupts the wait makes it return over 128 with that sleep still running, and it is stopped.
watch() {
	stop=
	trap 'stop=1' TERM
	while [ -z "$stop" ] && kill -0 "$1" 2>/dev/null; do
		sleep 1 &
		nap=$!
		wait "$nap"
		if [ $? -gt 128 ]; then
			kill "$nap" 2>/dev/null
		fi
	done
	[ -z "$stop" ] || exit 0
	# Only while $2 is still timeout: if timeout ended in the last second, its process ID may
	# already be another process's.
	if [ -r "/proc/$2/comm" ]; then
		IFS= read -r comm < "/proc/$2/comm" || comm=
		[ "$comm" = timeout ] || exit 0
	fi
	kill -s TERM "$2" 2>/dev/null
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
