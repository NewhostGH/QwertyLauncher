#!/bin/sh
# Turns the hooks in .githooks/ on. Run once in every terminal you use git from
# (this shell, Code On The Go's terminal, a PC):  sh .githooks/install.sh
#
# Android's shared storage can't mark files executable, and git only runs executable
# hooks. So this writes tiny executable launchers into your home folder that run the
# real hooks from the repo with `sh`, and points git at them for this repo only.

set -e
# Found from this script's place, not with git: git won't open the repo until it's trusted below.
root=$(cd "$(dirname "$0")/.." && pwd)
shims="${XDG_DATA_HOME:-$HOME/.local/share}/git-hooks/qwertylauncher"
mkdir -p "$shims"

for hook in pre-commit commit-msg pre-merge-commit pre-push; do
    cat > "$shims/$hook" <<EOF
#!/bin/sh
exec sh "\$(git rev-parse --show-toplevel)/.githooks/$hook" "\$@"
EOF
    chmod +x "$shims/$hook"
done

# hooksPath goes in your own git config, scoped to this repo: the repo's own config is
# shared by every terminal that opens it, but each has its own home folder.
include="$shims/gitconfig"
printf '[core]\n\thooksPath = %s\n' "$shims" > "$include"

# The same folder can be reached by several paths on Android; cover each of them.
paths=$root
case $root in
    /mnt/sdcard/* | /sdcard/* | /storage/emulated/0/*)
        rest=${root#/mnt/sdcard/}; rest=${rest#/sdcard/}; rest=${rest#/storage/emulated/0/}
        paths="/mnt/sdcard/$rest /sdcard/$rest /storage/emulated/0/$rest" ;;
esac
for p in $paths; do
    git config --global "includeIf.gitdir:$p/.path" "$include"
    # Shared storage belongs to another user id, which git refuses to trust by default.
    git config --global --get-all safe.directory | grep -qxF "$p" || git config --global --add safe.directory "$p"
done

# main moves by fast-forward only.
git -C "$root" config branch.main.mergeoptions --ff-only

echo "Git hooks are on for $root (launchers in $shims)."
