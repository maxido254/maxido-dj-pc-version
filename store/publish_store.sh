#!/usr/bin/env bash
# Runs in GitHub Actions from the repo root: rebuilds store/catalog.json + store/files/ from store/items/ and commits the result.
set -e
BR="${GITHUB_REF_NAME:-main}"
BASE="https://raw.githubusercontent.com/${GITHUB_REPOSITORY}/${BR}/store"
python3 store/build_catalog.py store "$BASE"
git config user.name "maxido-store-bot"
git config user.email "maxido-store-bot@users.noreply.github.com"
git add store
if git diff --cached --quiet; then
  echo "Store unchanged."
else
  git commit -q -m "Update store catalog"
  git pull --rebase --autostash -q origin "$BR" || true
  git push -q origin "HEAD:$BR"
  echo "Store published."
fi
