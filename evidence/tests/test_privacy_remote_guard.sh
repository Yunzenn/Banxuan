#!/bin/sh
# Local-only empty-tree fixture; no remote requests or real personal identities.
set -eu
hook="$(pwd)/.githooks/pre-push"
fixture=$(mktemp -d .tools/privacy-remote-guard-XXXXXX)
cd "$fixture"
git init -q
tree=$(git mktree </dev/null)
export GIT_AUTHOR_NAME=yunzenn GIT_COMMITTER_NAME=yunzenn
export GIT_AUTHOR_EMAIL=209125778+Yunzenn@users.noreply.github.com
export GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
base=$(git commit-tree "$tree" -m baseline)
published=$(GIT_COMMITTER_NAME=GitHub GIT_COMMITTER_EMAIL=noreply@github.com git commit-tree "$tree" -p "$base" -m published-squash)
good=$(git commit-tree "$tree" -p "$published" -m private-new-commit)
bad=$(GIT_AUTHOR_EMAIL=synthetic-test@example.invalid git commit-tree "$tree" -p "$published" -m rejected-new-commit)
git update-ref refs/remotes/origin/main "$published"
git update-ref refs/remotes/origin/feature "$base"
git update-ref refs/remotes/unrelated/feature "$bad"
zero=0000000000000000000000000000000000000000
check() {
    name=$1; local_oid=$2; remote_oid=$3; expected=$4
    if printf 'refs/heads/feature %s refs/heads/feature %s\n' "$local_oid" "$remote_oid" | sh "$hook" origin local-test; then
        actual=allow
    else
        actual=block
    fi
    test "$actual" = "$expected" || { echo "FAIL $name: $actual"; exit 1; }
    echo "PASS $name: $actual"
}
check published-main-merge "$good" "$base" allow
check new-branch-private "$good" "$zero" allow
check unpublished-bad-existing-ref "$bad" "$base" block
check unpublished-bad-new-ref "$bad" "$zero" block
echo '4/4 local privacy-guard cases passed; unrelated remote did not confer trust.'
