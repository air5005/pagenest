package com.air5005.pagenest.discovery.repository

import com.air5005.pagenest.discovery.model.CatalogRequest

interface DiscoveryCatalogRepository {
    suspend fun discover(request: CatalogRequest): DiscoveryResult
}
