# Shell Hooks runner. Usage: sh cogo-hook-run.sh SCRIPT [ARG...]
#
# Runs SCRIPT with its arguments untouched. Android has no /usr/bin/env or /bin/bash, and the
# IDE's command service does not preload termux-exec, so the interpreter a shebang names is
# looked up in $PREFIX/bin, as termux-exec would do. POSIX sh only: this runs under Android's
# /system/bin/sh, not bash.

script=$1
shift

# The event's project, which for project-closed the IDE no longer has open.
if [ -n "${COGO_PROJECT_DIR:-}" ] && [ -d "$COGO_PROJECT_DIR" ]; then
	cd "$COGO_PROJECT_DIR" || exit 126
fi

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
	if [ "${interp##*/}" = env ]; then
		interp=${arg%%[' 	']*}
		arg=$(trim "${arg#"$interp"}")
	fi
	name=${interp##*/}
	if [ -n "$name" ] && [ -n "${PREFIX:-}" ] && [ -f "$PREFIX/bin/$name" ] && [ -x "$PREFIX/bin/$name" ]; then
		if [ -n "$arg" ]; then
			exec "$PREFIX/bin/$name" "$arg" "$script" "$@"
		fi
		exec "$PREFIX/bin/$name" "$script" "$@"
	fi
	# Not a Termux program: let the kernel read the shebang, e.g. #!/system/bin/sh.
	exec "$script" "$@"
	;;
"$(printf '\177')ELF"*)
	exec "$script" "$@"
	;;
esac

# No shebang: a shell script, as a terminal user would expect.
if [ -n "${PREFIX:-}" ] && [ -f "$PREFIX/bin/bash" ] && [ -x "$PREFIX/bin/bash" ]; then
	exec "$PREFIX/bin/bash" "$script" "$@"
fi
exec sh "$script" "$@"
