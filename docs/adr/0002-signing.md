# 0002: Sign sources with ECDSA P-256, pinned on first use

- **Status:** accepted, 2026-09-17 (Phase 2)

## Context

A source is static files on someone else's web host. Folio has to know that the list it reads is the one the publisher
made, that it isn't an older list being replayed, and that a host or a network in between can't swap a package for
another one. There are no accounts, and there's no Folio server to ask.

Ed25519 is the obvious modern choice: small keys, small signatures, no parameter choices to get wrong. But Android only
has it from API 33 (through Conscrypt), and Folio's minSdk is 31, so a third of the versions Folio supports couldn't
check a signature without bundling a crypto library.

## Decision

- **Algorithm:** ECDSA P-256 with SHA-256 (`SHA256withECDSA`), which every supported Android version has. Keys are
  X.509/SPKI, published as base64 in `key.pub`; signatures are base64 DER in a detached `<file>.sig`.
- **Signed over exact bytes.** Folio checks the signature against the bytes it downloaded, before parsing them, so there
  is no canonical form to argue about and nothing is read from an unverified file.
- **What's signed:** `entry.json` (which pins the index's path, sha256 and size) and `revoked.json`. The index and the
  packages are covered by the hashes those files pin, so only two signatures are needed however large a source grows.
- **Trust on first use, then pinned.** Adding a source shows its key fingerprint (SHA-256 of the key, in groups of
  four). Folio pins the key, and a source that later signs with a different key stops working until the user confirms
  the new fingerprint, which also resets the rollback floor. The Folio source's key ships in the app.
- **Room to change:** `entry.json` names a `keyId`, so a later format version can add an algorithm field and move to
  Ed25519 once minSdk is 33, without breaking older clients.

## Consequences

- **Good:** one code path on every supported version, no crypto dependency, and no signing key in the repository (CI
  signs at publish time).
- **Good:** a stolen key is contained by the revocation list, and key rotation is visible to the user rather than silent.
- **Bad:** ECDSA signatures are bigger than Ed25519's and need a random nonce per signature; signing happens in CI on a
  normal JVM, where that's well covered.
- **Bad:** trust on first use means the first fetch is the weak moment. The fingerprint is shown then, the official key
  is built in, and a source's key can't change afterwards without the user being asked.
