package com.flino.nlapi.web

import com.flino.nlapi.config.TrinoProperties
import org.springframework.stereotype.Component

/** Clamps client-supplied `offset`/`limit` query params against configured bounds -- see TrinoProperties.Listing. */
@Component
class PageParams(private val trinoProperties: TrinoProperties) {
    fun resolve(offset: Int?, limit: Int?): Pair<Int, Int> {
        val resolvedOffset = (offset ?: 0).coerceAtLeast(0)
        val resolvedLimit = (limit ?: trinoProperties.listing.defaultPageSize)
            .coerceIn(1, trinoProperties.listing.maxPageSize)
        return resolvedOffset to resolvedLimit
    }
}
