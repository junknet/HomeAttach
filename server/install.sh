#!/bin/bash
# Install the HomeAttach server side into ~/.local/bin: the tsess scripts and
# the patched zmx binary. Builds zmx first when the binary is missing or the
# vendored source is newer.
#
#   server/install.sh            build if needed, then install
#   server/install.sh --build    force a rebuild
#
# Requires Zig 0.15.2 for the build step (ZIG=/path/to/zig to override; the
# default looks in ~/.local/toolchains/zig-x86_64-linux-0.15.2/zig, then PATH).
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bin_dir="${HOMEATTACH_BIN_DIR:-$HOME/.local/bin}"
zmx_src="$script_dir/zmx"
zmx_out="$zmx_src/zig-out/bin/zmx"

find_zig() {
    if [ -n "${ZIG:-}" ]; then
        printf '%s' "$ZIG"
        return
    fi
    local candidate="$HOME/.local/toolchains/zig-x86_64-linux-0.15.2/zig"
    if [ -x "$candidate" ]; then
        printf '%s' "$candidate"
        return
    fi
    command -v zig || {
        echo "install.sh: no zig found; install Zig 0.15.2 or set ZIG=" >&2
        exit 1
    }
}

need_build=false
[ "${1:-}" = "--build" ] && need_build=true
[ -x "$zmx_out" ] || need_build=true
if [ -x "$zmx_out" ] && [ -n "$(find "$zmx_src/src" -name '*.zig' -newer "$zmx_out" 2>/dev/null)" ]; then
    need_build=true
fi

if $need_build; then
    zig_bin=$(find_zig)
    version=$("$zig_bin" version)
    case "$version" in
        0.15.*) ;;
        *) echo "install.sh: zmx needs Zig 0.15.x, found $version" >&2; exit 1 ;;
    esac
    echo "building zmx with $zig_bin ($version)..."
    (cd "$zmx_src" && "$zig_bin" build -Doptimize=ReleaseSafe)
fi

mkdir -p "$bin_dir"
install -m 755 "$zmx_out" "$bin_dir/zmx"
for script in tsess tsess-attach tsess-auto tsess-focus tsess-kill tsess-list \
    tsess-release tsess-state tsess-watch; do
    install -m 755 "$script_dir/$script" "$bin_dir/$script"
done

# Konsole/yakuake profile: every tab started with it becomes a session.
konsole_dir="$HOME/.local/share/konsole"
if [ -d "$konsole_dir" ] && [ ! -e "$konsole_dir/HomeAttach.profile" ]; then
    sed "s|@HOMEATTACH_BIN_DIR@|$bin_dir|g" "$script_dir/HomeAttach.profile" \
        > "$konsole_dir/HomeAttach.profile"
    echo "installed Konsole profile: HomeAttach (select it as yakuake's default profile)"
fi

echo "installed to $bin_dir:"
echo "  zmx $("$bin_dir/zmx" version | head -1 | awk '{print $NF}')"
echo "  tsess tsess-attach tsess-auto tsess-focus tsess-kill tsess-list tsess-release tsess-state tsess-watch"
echo
echo "PC usage:   tsess <name>              named session in this tab"
echo "            tsess-auto                auto-named session (yakuake profile command)"
echo "Yakuake:    point a Konsole profile's Command at $bin_dir/tsess-auto"
echo "            and make it yakuake's default - every tab becomes a session"
echo "Phone:      install the HomeAttach app and point it at this machine"
