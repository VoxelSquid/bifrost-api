package me.voxelsquid.bifrost.ai

class KeyManager(keys: List<String>) {

    data class Key(val key: String, var requestCounter: Int = 0, var quota: Boolean = false)
    private val apiKeys = keys.map { Key(it) }.toMutableList()

    fun getAvailableKey(): Key {
        return apiKeys.filter { !it.quota }.randomOrNull()
            ?: throw IllegalStateException("All API keys have exceeded quota. Please add new keys.")
    }

}