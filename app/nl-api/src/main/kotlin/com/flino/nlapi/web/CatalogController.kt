package com.flino.nlapi.web

import com.flino.nlapi.trino.TrinoMetadataService
import com.flino.nlapi.trino.toPage
import com.flino.nlapi.web.dto.CatalogsResponse
import com.flino.nlapi.web.dto.SchemasResponse
import com.flino.nlapi.web.dto.catalogsResponse
import com.flino.nlapi.web.dto.schemasResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CatalogController(
    private val metadataService: TrinoMetadataService,
    private val pageParams: PageParams,
) {
    /** GET /api/v1/catalogs -- every catalog the configured nl-api-read-role can see. */
    @GetMapping("/api/v1/catalogs")
    fun listCatalogs(
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): CatalogsResponse {
        val (o, l) = pageParams.resolve(offset, limit)
        return catalogsResponse(metadataService.listCatalogs().toPage(o, l))
    }

    /** GET /api/v1/catalogs/{catalog}/schemas -- Trino's own `information_schema` is filtered out, see TrinoMetadataService. */
    @GetMapping("/api/v1/catalogs/{catalog}/schemas")
    fun listSchemas(
        @PathVariable catalog: String,
        @RequestParam(required = false) offset: Int?,
        @RequestParam(required = false) limit: Int?,
    ): SchemasResponse {
        val (o, l) = pageParams.resolve(offset, limit)
        return schemasResponse(catalog, metadataService.listSchemas(catalog).toPage(o, l))
    }
}
