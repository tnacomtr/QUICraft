# SPDX-License-Identifier: GPL-3.0-or-later
#
# Shared build provenance for the bench scripts. A build is dirty when tracked changes are staged
# or unstaged; untracked files do not change the compiled artifacts. Sourced, not executed.

# True when the index or the working tree differs from HEAD.
git_dirty() { # repo root
    local root="$1"
    ! git -C "$root" diff --quiet 2>/dev/null || ! git -C "$root" diff --cached --quiet 2>/dev/null
}

# Abbreviated HEAD, with " (dirty)" when tracked changes are staged or unstaged.
git_state() { # repo root
    local root="$1" state
    state="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo none)"
    if [[ "$state" != none ]] && git_dirty "$root"; then
        state="$state (dirty)"
    fi
    printf '%s' "$state"
}

# Full HEAD, with a "-dirty" suffix when tracked changes are staged or unstaged.
git_build_commit() { # repo root
    local root="$1" commit
    commit="$(git -C "$root" rev-parse HEAD 2>/dev/null || echo unknown)"
    if [[ "$commit" != unknown ]] && git_dirty "$root"; then
        commit="$commit-dirty"
    fi
    printf '%s' "$commit"
}
