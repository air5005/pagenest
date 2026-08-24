package com.air5005.pagenest.speech.security

interface SpeechCredentialStore {
    suspend fun saveAzure(key: String, region: String)
    suspend fun loadAzure(): AzureCredentials?
    suspend fun clearAzure()
}

data class AzureCredentials(
    val key: String,
    val region: String,
)

object AzureRegionValidator {
    private val allowedRegion = Regex("^[a-z0-9-]{2,32}$")

    fun isValid(region: String): Boolean = allowedRegion.matches(region)

    fun requireValid(region: String) {
        require(isValid(region)) { "Invalid Azure region" }
    }
}
