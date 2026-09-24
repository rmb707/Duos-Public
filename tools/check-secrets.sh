#!/usr/bin/env bash
# Fails if anything that looks like a private key is in the repository.
#
#   bash tools/check-secrets.sh
#
# .gitignore already covers *.pem, *.key and the keystores, but ignoring a file doesn't stop `git add -f`, a rename,
# or a key pasted into a document. This looks at what is actually tracked. Duos's supporter key and the key that
# signs a package source are the only two things that make Duos's signatures worth anything: with one of them,
# anyone can mint supporter codes or publish packages as Duos.
set -uo pipefail
cd "$(dirname "$0")/.."

# The header every PEM private key starts with, split so this file doesn't match itself.
needle="BEGIN .*PRIVATE KEY""-----"
found=0

while IFS= read -r file; do
  # The test fixtures hold public keys and signatures, which are meant to be public.
  case "$file" in
    */test/resources/*) continue ;;
  esac
  if LC_ALL=C grep -qE -- "$needle" "$file" 2>/dev/null; then
    echo "private key material in $file"
    found=1
  fi
done < <(git ls-files)

if [ "$found" -eq 0 ]; then
  echo "No private keys in the repository."
else
  echo
  echo "Remove it, and treat that key as leaked: mint a new one, put the new public half in the app, and say so in"
  echo "the release notes - every code or signature made with the old key has to stop being trusted."
  exit 1
fi
