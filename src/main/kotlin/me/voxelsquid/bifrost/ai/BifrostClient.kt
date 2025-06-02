package me.voxelsquid.bifrost.ai

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import me.voxelsquid.bifrost.ai.KeyManager.Key
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class BifrostClient(
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=",
    private val keyManager: KeyManager,
    private val plugin: JavaPlugin,
    private val gson: Gson = Gson(),
    private val maxRetries: Int = plugin.config.getInt("max-retries")
) {

    private val proxyHost = plugin.config.getString("proxy.host")!!
    private val proxyPort = plugin.config.getInt("proxy.port")
    private val proxyType = Proxy.Type.valueOf(plugin.config.getString("proxy.type")!!)
    private val proxy     = Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort))
    private val username  = plugin.config.getString("proxy.user")!!
    private val password  = plugin.config.getString("proxy.pass")!!

    private var proxyAuthenticator: Authenticator = object : Authenticator {
        @Throws(IOException::class)
        override fun authenticate(route: Route?, response: Response): Request {
            val credential = Credentials.basic(username, password)
            return response.request.newBuilder()
                .addHeader("Proxy-Authorization", credential)
                .build()
        }
    }

    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).apply {
        if (plugin.config.getString("proxy.host") != "PROXY_HOST") {
            plugin.logger.info("Proxy usage in config.yml detected. When sending requests, a proxy will be used.")
            proxy(proxy).proxyAuthenticator(proxyAuthenticator)
        }
    }.build()

    private val lang  = plugin.config.getString("language")!!
    private val rules = "[Rules: `Use $lang language.`, `Do not use \" character.`] "
    private val temp  = plugin.config.getInt("temperature")

    fun <T : Any> sendRequest(
        prompt: String,
        responseType: KClass<T>,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        this.sendRequestWithRetry(rules + prompt, responseType, onSuccess, onFailure, retries = maxRetries)
    }

    fun translate(file: File, onSuccess: (YamlConfiguration) -> Unit) {

        val prompt      = "Translate YAML file below to $lang, keep the keys and special symbols (like §) and DO NOT translate placeholders. Wrap result as ```yaml```. \n```yaml\n${file.readText()}\n```"
        val key         = keyManager.getAvailableKey()
        val requestBody = createJsonRequest(prompt.replace("\"", "\\\"")).toRequestBody("application/json".toMediaTypeOrNull())
        val request     = Request.Builder().url("$baseUrl${key.key}").post(requestBody).build()

        fun findYaml(yaml: String): String {
            val regex = """```yaml([\s\S]*?)```""".toRegex()
            return regex.find(yaml)?.groups?.get(1)?.value?.trim() ?: run {
                plugin.logger.severe(yaml)
                throw NullPointerException("Can't find yaml pattern during generative localization task.")
            }
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                plugin.logger.warning("Request failed: ${e.message}")
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    translate(file, onSuccess)
                }, 200L)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        response.body?.let { body ->

                            val result = body.string()
                            val cleanedData = try {
                                unescapeString(findYaml(result))
                            } catch (exception: NullPointerException) {
                                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                                    plugin.logger.info("Another attempt at generative translation... If it doesn't go away, turn off generative translation and report to the developer.")
                                    translate(file, onSuccess)
                                }, 200L)
                                return
                            }

                            try {
                                onSuccess(YamlConfiguration.loadConfiguration(StringReader(cleanedData)))
                            } catch (exception: Exception) {
                                plugin.logger.warning("Failed to translate language.yml: ${exception.message}")
                            }

                        } ?: translate(file, onSuccess)
                    } else {
                        plugin.server.scheduler.runTaskLater(plugin, { _ ->
                            translate(file, onSuccess)
                        }, 200L)
                    }
                }
            }
        })
    }

    private fun <T : Any> sendRequestWithRetry(
        prompt: String,
        responseType: KClass<T>,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit,
        retries: Int
    ) {
        if (retries <= 0) {
            onFailure(IllegalStateException("Max retries reached for prompt: $prompt"))
            return
        }

        val key = try {
            keyManager.getAvailableKey()
        } catch (e: IllegalStateException) {
            onFailure(e)
            return
        }

        val requestBody = createJsonRequest(escapeJsonString(prompt)).toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("$baseUrl${key.key}")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    sendRequestWithRetry(prompt, responseType, onSuccess, onFailure, retries - 1)
                }, 100L)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        response.body?.let { body ->
                            val rawResponse = body.string()
                            try {
                                val cleanedJson = repairJson(rawResponse)
                                val parsedResponse = gson.fromJson(cleanedJson, responseType.java)
                                onSuccess(parsedResponse)
                            } catch (e: JsonSyntaxException) {
                                // Попытка извлечь текст как резервный вариант
                                val fallbackText = extractTextFallback(rawResponse)
                                if (fallbackText != null && responseType == String::class) {
                                    @Suppress("UNCHECKED_CAST")
                                    onSuccess(fallbackText as T)
                                } else {
                                    plugin.server.scheduler.runTaskLater(plugin, { _ ->
                                        sendRequestWithRetry(prompt, responseType, onSuccess, onFailure, retries - 1)
                                    }, 100L)
                                }
                            } catch (e: Exception) {
                                plugin.logger.severe("Unexpected error: ${e.message}")
                                onFailure(e)
                            }
                        } ?: onFailure(IllegalStateException("Empty response body"))
                    } else {
                        handleFailedResponse(response, key, onFailure)
                        plugin.server.scheduler.runTaskLater(plugin, { _ ->
                            sendRequestWithRetry(prompt, responseType, onSuccess, onFailure, retries - 1)
                        }, 100L)
                    }
                }
            }
        })
    }

    private fun escapeJsonString(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun unescapeString(input: String): String {
        // Заменяем \\+n на \n и \\+" на "
        return input
            .replace(Regex("""\\+n"""), "\n")
            .replace(Regex("""\\+""""), "\"")
    }

    private fun createJsonRequest(prompt: String): String {
        return """{
            "contents": [{
                "parts": [{
                    "text": "$prompt"
                }]
            }],
            "safetySettings": [{
                "category": "7",
                "threshold": "4"
            }],
            "generationConfig": {
                "responseMimeType": "application/json",
                "temperature": $temp
            }
        }""".trimIndent()
    }

    private fun repairJson(response: String): String {

        // Извлечение JSON из ответа
        val jsonRegex = """\{[^{}]*}""".toRegex()
        var json = jsonRegex.find(response)?.value ?: response

        // Основная очистка
        json = json
            .replace("```json\n", "")
            .replace("```", "")
            .replace("\\\"", "\"")
            .replace("\\n", "")
            .replace("\\\n", "")
            .replace(Regex("\\s{2,}"), " ")
            .replace(Regex("\\.{3}(?=\\S)"), "..." + " ")
            .replace("…", "...")

        return json
    }

    // Извлечение текстовой части из ответа как резервный вариант
    private fun extractTextFallback(response: String): String? {
        val textRegex = """text:\s*"(.*?)"(?=\s*[,}])""".toRegex()
        return textRegex.find(response)?.groups?.get(1)?.value
    }

    private fun handleFailedResponse(response: Response, key: Key, onFailure: (Throwable) -> Unit) {
        response.body?.string()?.let { reason ->
            if (reason.lowercase().contains("quota")) {
                key.quota = true
                plugin.logger.info("API key quota exceeded: ${key.key}.")
                onFailure(IllegalStateException("API key quota exceeded: ${key.key}."))
            } else {
                plugin.logger.warning("Request failed: ${response.code}, $reason")
                onFailure(IllegalStateException("Request failed: ${response.code}, $reason"))
            }
        } ?: onFailure(IllegalStateException("Empty response body."))
    }

}

data class ResponseData(val candidates: List<Candidate>, val usageMetadata: UsageMetadata)
data class Candidate(val content: Content, val finishReason: String, val index: Int, val safetyRatings: List<SafetyRating>)
data class Content(val parts: List<Part>, val role: String)
data class Part(val text: String)
data class SafetyRating(val category: String, val probability: String)
data class UsageMetadata(val promptTokenCount: Int, val candidatesTokenCount: Int, val totalTokenCount: Int)