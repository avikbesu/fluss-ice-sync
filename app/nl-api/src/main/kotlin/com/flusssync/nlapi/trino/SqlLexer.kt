package com.flusssync.nlapi.trino

/**
 * A minimal lexer for exactly one job: [TrinoSqlValidator] needs to find a
 * statement's real leading keyword without being fooled by a `--`/`/* */`
 * comment, a quoted string/identifier that happens to contain SQL-looking
 * text, or a semicolon inside a string literal or nested parentheses. This
 * is deliberately not a full SQL parser -- it only tracks enough structure
 * (words, `(` `)` `,` `;`, and opaque string/quoted-identifier literals) to
 * do that safely.
 */
internal object SqlLexer {

    sealed interface Token
    data class Word(val text: String) : Token
    /** A `'...'` string literal or `"..."` quoted identifier -- content is opaque, never re-scanned for keywords. */
    data object Literal : Token
    data object LParen : Token
    data object RParen : Token
    data object Comma : Token
    data object Semicolon : Token
    /** Anything else (operators, numeric literals, stray punctuation) -- irrelevant to keyword/structure detection. */
    data object Other : Token

    fun tokenize(sql: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++

                // Line comment.
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    i += 2
                    while (i < n && sql[i] != '\n') i++
                }

                // Block comment (non-nested, matching Trino's own grammar).
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    i += 2
                    val end = sql.indexOf("*/", i)
                    i = if (end == -1) n else end + 2
                }

                // String literal: '...' with '' as an escaped quote.
                c == '\'' -> {
                    i++
                    while (i < n) {
                        if (sql[i] == '\'') {
                            if (i + 1 < n && sql[i + 1] == '\'') {
                                i += 2
                            } else {
                                i++
                                break
                            }
                        } else {
                            i++
                        }
                    }
                    tokens += Literal
                }

                // Quoted identifier: "..." with "" as an escaped quote.
                c == '"' -> {
                    i++
                    while (i < n) {
                        if (sql[i] == '"') {
                            if (i + 1 < n && sql[i + 1] == '"') {
                                i += 2
                            } else {
                                i++
                                break
                            }
                        } else {
                            i++
                        }
                    }
                    tokens += Literal
                }

                c == '(' -> {
                    tokens += LParen
                    i++
                }

                c == ')' -> {
                    tokens += RParen
                    i++
                }

                c == ',' -> {
                    tokens += Comma
                    i++
                }

                c == ';' -> {
                    tokens += Semicolon
                    i++
                }

                Character.isLetter(c) || c == '_' -> {
                    val start = i
                    while (i < n && (Character.isLetterOrDigit(sql[i]) || sql[i] == '_')) i++
                    tokens += Word(sql.substring(start, i))
                }

                else -> {
                    tokens += Other
                    i++
                }
            }
        }
        return tokens
    }

    /** Splits a token stream into statements at top-level (paren-depth 0) semicolons, dropping empty trailing segments. */
    fun splitStatements(tokens: List<Token>): List<List<Token>> {
        val statements = mutableListOf<List<Token>>()
        var current = mutableListOf<Token>()
        var depth = 0
        for (t in tokens) {
            when (t) {
                LParen -> {
                    depth++
                    current += t
                }
                RParen -> {
                    depth--
                    current += t
                }
                Semicolon -> {
                    if (depth == 0) {
                        if (current.isNotEmpty()) statements += current
                        current = mutableListOf()
                    } else {
                        current += t
                    }
                }
                else -> current += t
            }
        }
        if (current.isNotEmpty()) statements += current
        return statements
    }
}
