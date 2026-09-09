package com.flino.config.filestore

import com.flino.config.exception.InvalidConfigIdException
import java.util.UUID

/**
 * The one place a caller-supplied id string is turned into something safe
 * to build a filesystem path from. `UUID.fromString` is strict about its
 * input shape (36 characters, hex digits and hyphens in fixed positions)
 * and throws on anything else -- including every path-traversal shape
 * named in the build prompt (`../../etc/passwd`, an absolute path, a name
 * with a slash in it) -- so a single `UUID.fromString` call already closes
 * that off completely; there's no separate regex to keep in sync with it.
 *
 * Callers must use [ConfigId.filename]/[ConfigId.toString] of the
 * *parsed* [UUID] to build a path, never the raw input string -- this
 * guarantees the path component is always the canonical, re-serialized
 * form Java itself produced, not whatever bytes the caller sent, closing
 * off any residual encoding trick even if `fromString`'s own validation
 * ever had a gap.
 */
object ConfigId {

    fun parse(raw: String): UUID = try {
        UUID.fromString(raw)
    } catch (e: RuntimeException) {
        // UUID.fromString's documented failure mode is IllegalArgumentException,
        // but older/edge-case inputs have been known to surface other
        // unchecked exceptions (e.g. ArrayIndexOutOfBoundsException) from its
        // internal splitting logic -- caught broadly here rather than
        // narrowly, since this is a security boundary (path traversal), not
        // just input validation, and a stray exception type must never turn
        // into an uncaught 500 instead of a clean, deliberate rejection.
        throw InvalidConfigIdException(raw)
    }

    fun generate(): UUID = UUID.randomUUID()

    fun filename(id: UUID): String = "$id.yaml"
}
