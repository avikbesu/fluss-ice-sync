package com.flusssync.nlapi.web

import com.flusssync.nlapi.config.TrinoProperties
import com.flusssync.nlapi.trino.PreviewStreamer
import com.flusssync.nlapi.trino.TrinoMetadataService
import com.flusssync.nlapi.trino.toPage
import com.flusssync.nlapi.web.dto.DdlResponse
import com.flusssync.nlapi.web.dto.DescribeResponse
import com.flusssync.nlapi.web.dto.TablesResponse
import com.flusssync.nlapi.web.dto.describeResponse
import com.flusssync.nlapi.web.dto.tablesResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@RestController
class TableController(
    private val metadataService: TrinoMetadataService,
    private val previewStreamer: PreviewStreamer,
    private val pageParams: PageParams,
    private val trinoProperties: TrinoProperties,
) {
    /** GET /api/v1/catalogs/{catalog}/schemas/{schema}/tables */
    @GetMapping("/api/v1/catalogs/{catalog}/schemas/{schema}/tables")
    fun listTables(
        @PathVariable catalog: String,
        @PathVariable schema: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): TablesResponse {
        val (o, l) = pageParams.resolve(offset, limit)
        return tablesResponse(catalog, schema, metadataService.listTables(catalog, schema).toPage(o, l))
    }

    /** GET /api/v1/catalogs/{catalog}/schemas/{schema}/tables/{table} -- columns, types, nullability, comments. */
    @GetMapping("/api/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}")
    fun describeTable(
        @PathVariable catalog: String,
        @PathVariable schema: String,
        @PathVariable table: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): DescribeResponse {
        val (o, l) = pageParams.resolve(offset, limit)
        return describeResponse(catalog, schema, table, metadataService.describeTable(catalog, schema, table).toPage(o, l))
    }

    /** GET /api/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}/ddl */
    @GetMapping("/api/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}/ddl")
    fun tableDdl(
        @PathVariable catalog: String,
        @PathVariable schema: String,
        @PathVariable table: String,
    ): DdlResponse = DdlResponse(catalog, schema, table, metadataService.tableDdl(catalog, schema, table))

    /**
     * GET /api/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}/preview?limit=&timeoutSeconds=
     *
     * Existence is resolved synchronously first (a 404 has to be a real
     * HTTP 404, which is only possible before the streaming body -- and
     * its 200 status -- is committed); the row/byte caps and cancellation
     * behavior are documented on [PreviewStreamer].
     */
    @GetMapping("/api/v1/catalogs/{catalog}/schemas/{schema}/tables/{table}/preview", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun previewTable(
        @PathVariable catalog: String,
        @PathVariable schema: String,
        @PathVariable table: String,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) timeoutSeconds: Int?,
    ): ResponseEntity<StreamingResponseBody> {
        val pseudoTable = metadataService.resolvePreviewTable(catalog, schema, table)
        val effectiveLimit = limit ?: trinoProperties.preview.defaultRowLimit
        val effectiveTimeout = (timeoutSeconds ?: trinoProperties.query.defaultTimeoutSeconds)
            .coerceIn(1, trinoProperties.query.maxTimeoutSeconds)

        val body = StreamingResponseBody { out ->
            previewStreamer.stream(catalog, schema, pseudoTable, effectiveLimit, effectiveTimeout, out)
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body)
    }
}
