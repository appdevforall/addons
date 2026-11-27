# Shell Hooks runner. Usage: sh cogo-hook-run.sh SCRIPT [ARG...]
#
# Runs SCRIPT with its arguments untouched. Android has no /usr/bin/env or /bin/bash, and the
# IDE's command service does not preload termux-exec, so a shebang naming /bin, /usr/bin or
# /usr/local/bin is looked up in $PREFIX/bin, as termux-exec would do. Any other interpreter path
# runs as written. POSIX sh only: this runs under Android's /system/bin/sh, not bash.

script=$1
shift

# The event's project, which for project-closed the IDE no longer has open. With none, as for
# ide-started, or one whose folder is gone, HOME: given no folder, the IDE starts this shell in the
# last project opened, even one since closed, and that can be another project.
if [ -n "${COGO_PROJECT_DIR:-}" ] && [ -d "$COGO_PROJECT_DIR" ]; then
	cd "$COGO_PROJECT_DIR" || exit 126
else
	cd "${HOME:-/}" || exit 126
fi

# With COGO_TIMEOUT_SECONDS set and the Terminal's GNU timeout installed, the script runs under
# it: timeout puts the script in its own process group and stops the whole group at the limit
# (KILL 5 s after TERM), so programs the script started do not outlive it. timeout runs as a child,
# not in place of this shell: the IDE stops a hook (on disable, unload or update) by killing the
# process it started, and if that were timeout the script would run on with no limit. A watchdog
# notices this shell is gone and sends timeout TERM, which stops the whole group the same way.
# Without timeout, the IDE's own stop ends only the script's first process.
#
# The script's output goes to files and is printed once it ends. The IDE waits for its pipes to
# close, not for this shell to exit, so a job the script left running with a bare `&` would hold
# them open and the run would look hung until the limit. The files are deleted as soon as they are
# open, so a run that is stopped leaves none behind; its output is lost.
run() {
	if [ -n "${COGO_TIMEOUT_SECONDS:-}" ] && in_prefix timeout; then
		capture
		if [ -n "$captured" ]; then
			"$PREFIX/bin/timeout" -k 5 "$COGO_TIMEOUT_SECONDS" "$@" >&3 2>&4 3>&- 4>&- 5<&- 6<&- &
			child=$!
			exec 3>&- 4>&-
		else
			"$PREFIX/bin/timeout" -k 5 "$COGO_TIMEOUT_SECONDS" "$@" &
			child=$!
		fi
		# Its output goes nowhere, so it never keeps the IDE's pipes open. Redirected with exec inside
		# a subshell: dash and busybox, given `watch ... >/dev/null &`, keep a saved copy of the
		# original stdout open in the background job. ($$ in a subshell is still this wrapper.)
		(exec </dev/null >/dev/null 2>&1; watch "$$" "$child") &
		watchdog=$!
		wait "$child"
		status=$?
		kill "$watchdog" 2>/dev/null
		if [ -n "$captured" ]; then
			cat <&5
			cat <&6 >&2
		fi
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
# sleep it has already waited for, whose ID may belong to another process by then. Only a TERM
# that interrupts the wait leaves that sleep running, and only then is it stopped: a wait over 128
# can also mean the sleep itself was killed from outside and already reaped.
watch() {
	stop=
	trap 'stop=1' TERM
	while [ -z "$stop" ] && kill -0 "$1" 2>/dev/null; do
		sleep 1 &
		nap=$!
		wait "$nap"
		napped=$?
		if [ -n "$stop" ] && [ "$napped" -gt 128 ]; then
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

# Opens a file for the script's stdout on 3 and 5 (write, read) and one for its stderr on 4 and 6,
# then deletes both: the descriptors keep them. Sets captured only if that worked; without a
# writable TMPDIR the output goes straight to the IDE, as it did before.
capture() {
	captured=
	dir=${TMPDIR:-/tmp}
	out=$(mktemp -p "$dir" cogo-hook.XXXXXX 2>/dev/null) || return 0
	if ! err=$(mktemp -p "$dir" cogo-hook.XXXXXX 2>/dev/null); then
		rm -f "$out"
		return 0
	fi
	exec 3>"$out" 4>"$err" 5<"$out" 6<"$err"
	rm -f "$out" "$err"
	captured=1
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
		# With options, such as -S to split the rest into arguments, the Terminal's env runs it and
		# applies them, as under termux-exec.
		if [ "$name" = env ] && [ "${arg#-}" = "$arg" ]; then
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
