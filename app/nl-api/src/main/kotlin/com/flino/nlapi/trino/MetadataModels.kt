package com.flino.nlapi.trino

data class TableSummary(val name: String, val type: String)

data class ColumnDescription(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val comment: String?,
    val ordinalPosition: Int,
)

/** A capped, offset-paginated slice of a larger listing -- see the build prompt's "wide tables / large schemas -> paginated responses, capped payload size". */
data class Page<T>(val items: List<T>, val offset: Int, val limit: Int, val total: Int) {
    val hasMore: Boolean get() = offset + items.size < total
}

fun <T> List<T>.toPage(offset: Int, limit: Int): Page<T> {
    val total = size
    val slice = if (offset >= total) emptyList() else subList(offset, minOf(offset + limit, total))
    return Page(slice, offset, limit, total)
}
