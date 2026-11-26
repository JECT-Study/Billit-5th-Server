package com.ject.external.gemini

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.util.Base64

/**
 * Gemini API의 `models/{model}:generateContent`로 이미지 한 장과 지시문을 보내 JSON 스키마에 맞춘 응답을 받습니다.
 *
 * - API 키는 `x-goog-api-key` 헤더로, 이미지는 base64 inline data로 보냅니다. 응답 형식은 `generationConfig.responseFormat.text`에 JSON 스키마로 지정합니다.
 * - 대화 기록을 서버에 남기는 Interactions API 대신 상태를 남기지 않는 `generateContent`를 씁니다. 검색 그라운딩 같은 도구는 쓰지 않습니다.
 * - 연결과 응답 타임아웃은 [GeminiProperties]를 따르고 재시도하지 않습니다.
 */
class GeminiClient(
    private val properties: GeminiProperties,
) {
    private val restClient =
        RestClient
            .builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()).apply {
                    setReadTimeout(properties.readTimeout)
                },
            ).build()

    /** 생성을 끝까지 마친 첫 후보의 텍스트(사고 과정 제외)를 반환합니다. 실패는 [GeminiException]으로 던집니다. */
    fun generateJson(
        prompt: String,
        image: ByteArray,
        mediaType: String,
        schema: Map<String, Any>,
    ): String {
        val body =
            mapOf(
                "contents" to
                    listOf(
                        mapOf(
                            "parts" to
                                listOf(
                                    mapOf(
                                        "inlineData" to mapOf("mimeType" to mediaType, "data" to Base64.getEncoder().encodeToString(image)),
                                    ),
                                    mapOf("text" to prompt),
                                ),
                        ),
                    ),
                "generationConfig" to
                    mapOf("responseFormat" to mapOf("text" to mapOf("mimeType" to "APPLICATION_JSON", "schema" to schema))),
            )
        val response =
            try {
                restClient
                    .post()
                    .uri("/v1beta/models/{model}:generateContent", properties.model)
                    .header("x-goog-api-key", properties.apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GenerateContentResponse::class.java)
            } catch (exception: ResourceAccessException) {
                val timeout =
                    generateSequence<Throwable>(exception) { it.cause }.any {
                        it is HttpTimeoutException ||
                            it is SocketTimeoutException
                    }
                throw GeminiException(if (timeout) GeminiFailureReason.TIMEOUT else GeminiFailureReason.CONNECTION, cause = exception)
            } catch (exception: RestClientResponseException) {
                throw GeminiException(GeminiFailureReason.HTTP_ERROR, exception.statusCode.value(), exception)
            } catch (exception: RestClientException) {
                throw GeminiException(GeminiFailureReason.INVALID_RESPONSE, cause = exception)
            }

        val candidate = response?.candidates?.firstOrNull()?.takeIf { it.finishReason == FINISH_REASON_STOP }
        val text =
            candidate
                ?.content
                ?.parts
                .orEmpty()
                .filter { it.thought != true }
                .mapNotNull { it.text }
                .joinToString("")
        if (text.isBlank()) {
            throw GeminiException(GeminiFailureReason.INVALID_RESPONSE)
        }
        return text
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GenerateContentResponse(
        val candidates: List<Candidate>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Candidate(
        val content: Content?,
        val finishReason: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Content(
        val parts: List<Part>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Part(
        val text: String?,
        val thought: Boolean?,
    )

    companion object {
        private const val FINISH_REASON_STOP = "STOP"
    }
}
