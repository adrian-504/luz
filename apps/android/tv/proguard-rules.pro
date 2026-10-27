# R8 rules for Luz (ADR-0049). The libraries Luz uses (Compose, Media3, OkHttp, Coil, SQLite, kotlinx) ship their own.
#
# Shrink and optimise, but keep the names: a crash record then reads as the source does, and the source is public anyway.
-dontobfuscate
