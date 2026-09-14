# shared:search

**Phase 2 onward (ranking complete by Phase 8).** Depends on `domain`.

Owns: text normalization for search (Unicode, diacritics, tokenization), FTS query construction rules, ranking
ladder from spec §12.3 (exact > prefix > token > normalized > alias/metadata > fuzzy > provider fallback), result
grouping. Search never touches the network.
