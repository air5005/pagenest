package com.air5005.pagenest.discovery.ui

import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference

fun discoveryBook(key: String, title: String) = OnlineBook(
    key, title, listOf("Author"), null, listOf("en"), emptyList(), null, 1, null, null,
    RightsStatus.PUBLIC_DOMAIN, listOf(SourceReference("gutendex", key)),
    listOf(OnlineAcquisition("gutendex", OnlineBookFormat.EPUB,
        "https://files.example/$key.epub", AcquisitionAccess.FREE_FULL, 20)),
)
