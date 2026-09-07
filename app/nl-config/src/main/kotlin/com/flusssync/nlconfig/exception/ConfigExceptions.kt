package com.flusssync.nlconfig.exception

/** Base type for every error this service turns into a structured JSON response instead of a raw filesystem exception. */
sealed class NlConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A path parameter that isn't a well-formed UUID -- covers both an honest typo and a path-traversal attempt (`../../etc/passwd`) identically. */
class InvalidConfigIdException(val rawId: String) :
    NlConfigException("'$rawId' is not a valid config id (expected a UUID)")

class ConfigNotFoundException(val id: String) : NlConfigException("No config found with id '$id'")

class DuplicateConfigNameException(val name: String) :
    NlConfigException("A config named '$name' already exists")

/** Field-level validation failures (length caps, column count, blank required fields) -- carried as a map so the UI wizard can show inline errors per field. */
class ConfigValidationException(val fieldErrors: Map<String, String>) :
    NlConfigException("Config failed validation: ${fieldErrors.keys.joinToString(", ")}")

/** The config file on disk for a given id exists but can't be parsed -- e.g. left partially written by an unclean shutdown before atomic-write protection existed, or corrupted out-of-band. */
class CorruptConfigFileException(val id: String, cause: Throwable) :
    NlConfigException("Config file for id '$id' exists but could not be read", cause)

/** Disk full, volume not writable, directory missing and uncreatable, etc. -- always a clear 5xx, never a silent no-op. */
class ConfigStorageException(message: String, cause: Throwable? = null) : NlConfigException(message, cause)
