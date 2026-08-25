package com.air5005.pagenest.discovery.source

import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.SourceBookDetails
import com.air5005.pagenest.discovery.model.SourceReference

interface OnlineCatalogSource {
    val id: String

    suspend fun browse(request: CatalogRequest): CatalogPage

    suspend fun details(reference: SourceReference): SourceBookDetails?
}
