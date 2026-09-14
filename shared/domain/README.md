# shared:domain

**Phase 1.** Pure Kotlin, no I/O, no project dependencies.

Owns: entities and value types ([DOMAIN_MODEL.md](../../docs/DOMAIN_MODEL.md)), stable ID derivation (ADR-0017),
capability model and resolver, `DomainError`, playback state/event types and error taxonomy (ADR-0019),
`SensitiveUrl` / `Secret<T>` / `Redactor` and URL policy ([SECURITY.md](../../docs/SECURITY.md)), and the interfaces
implemented natively: `HttpTransport`, `SecretStore`, `Clock`, `Tracer`; pipeline contracts `SourceAdapter`,
`StreamingParser`, `Normalizer`.

Must not contain: parsing of any protocol, database access, logging of raw strings.
