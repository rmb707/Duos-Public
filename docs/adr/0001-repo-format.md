# 0001: Signed entry file with a hashed index

- **Status:** accepted, 2026-09-17
- **Context:** Folio 0.7.0 Market

## Context

The Market needs sources that anyone can host for free (usually GitHub Pages). Folio has to be able to trust what it downloads from them.

We looked at three formats:
- **Cydia and Sileo:** Debian `Release` plus `Packages`, with optional GPG.
- **F-Droid index-v2:** a signed entry file pointing at a hashed index, plus diffs.
- **Registries built on the GitHub API:** Obtainium calls the API for each app. It keeps hitting the 60-requests-an-hour limit for users without a token.

## Decision

- **A source is static files:**
  - `entry.json`, with a detached signature. It holds `timestamp`, `maxAge` and the index's path, size and sha256.
  - `index.json`, listing each package with its URL, size, sha256 and a copy of its manifest.
  - An optional signed `revoked.json`.
- **What Folio checks:** the signature, then the hashes. It rejects a `timestamp` older than the last one it accepted (rollback) and an expired `maxAge` (freeze).
- **Network use:** Folio never calls the GitHub REST API for each package.
- **Scope:** diffs, like F-Droid's, aren't in v1, because indexes are small. They can be added later without breaking the format.

## Consequences

- **Good:** works on any static host; one request per refresh (ETag) when nothing changed; protects against tampering, rollback, freeze and mix-and-match attacks; easy to generate in a GitHub Action.
- **Bad:** publishers must look after a signing key, and the template repo keeps it in Actions secrets. Key rotation needs the user's confirmation. Very large sources would benefit from diffs, which v1 doesn't have.
- **Not chosen:**
  - The Debian format, which needs a dpkg-style toolchain and GPG and doesn't fit JSON depictions.
  - TUF in full, with four roles, which is too heavy for one-person sources. We keep its checks against rollback and freeze.
