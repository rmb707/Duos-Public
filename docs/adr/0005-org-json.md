# 0005: Keep org.json for Market parsing

- **Status:** accepted, 2026-09-17

## Context

Folio already parses themes, layout backups, the roadmap and Software Update responses with `org.json`, using hand-written strict validators (`LayoutBackup.kt`). The Market adds manifests, depictions, indexes and entry files.

## Decision

- **Parsing:** keep `org.json`, with explicit, strict readers and size caps, following the `LayoutBackup` validator style.
- **A strict JSON check first (`JsonGuard`):** Android's `org.json` is lenient (comments, unquoted keys, single quotes,
  trailing text) and silently keeps the last of two duplicate keys, which is a way to smuggle a value past a reviewer or a
  validator. Every file is checked as strict JSON (RFC 8259) before `org.json` sees it, with a 32-level nesting cap so a
  hostile file can't overflow `org.json`'s recursive reader.
- **Schemas:** the JSON Schemas in `docs/sdk/schema/v1/` are the contract. Parser tests check that valid and invalid samples give the same answer in Folio and in the schemas.

## Consequences

- **Good:** no new dependency or compiler plugin; the parsing style matches the rest of the codebase; each field has explicit fallbacks, which keeps malformed input from crashing Folio.
- **Good:** Folio, `folio-pkg` and the schemas all answer the same way, whichever `org.json` build is in use.
- **Bad:** more hand-written code than kotlinx.serialization would need, and the schema and the parser can drift apart.
  `SchemaConformanceTest` (schema-versus-parser on thousands of mutated samples, plus an enum drift check) and the Jazzer
  targets in `ParserFuzzTest` cover that. Both use test-only dependencies: `json-schema-validator` (Apache-2.0),
  `jazzer-junit` (Apache-2.0) and JUnit 5 (EPL-2.0); nothing new ships in the app.
