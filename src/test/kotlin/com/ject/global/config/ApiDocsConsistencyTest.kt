package com.ject.global.config

import com.ject.global.error.ErrorCode
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.yaml.snakeyaml.Yaml

class ApiDocsConsistencyTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    init {
        test("Controller에 등록된 API와 도메인 명세의 경로·메서드가 일치한다") {
            // given
            val documented =
                loadSpecs("classpath:api-docs/domains/*.yml")
                    .flatMap { spec ->
                        spec.paths.flatMap { (path, pathItem) ->
                            pathItem.keys.filter { it in OPERATION_KEYS }.map { "${it.uppercase()} $path" }
                        }
                    }.toSet()

            // when
            val implemented =
                handlerMapping.handlerMethods
                    .filterValues { it.beanType.packageName.startsWith(BASE_PACKAGE) }
                    .keys
                    .flatMap { mapping ->
                        // HTTP 메서드를 지정하지 않은 매핑은 `*`로 남겨 명세에 없는 API로 드러나게 합니다.
                        val methods =
                            mapping.methodsCondition.methods
                                .map { it.name }
                                .ifEmpty { listOf("*") }
                        mapping.patternValues.flatMap { path -> methods.map { "$it $path" } }
                    }.toSet()

            // then
            assertSoftly {
                withClue("명세에 없는 API") { (implemented - documented).sorted() shouldBe emptyList() }
                withClue("Controller에 없는 API") { (documented - implemented).sorted() shouldBe emptyList() }
            }
        }

        test("명세의 오류 예제와 서버의 오류 코드·메시지가 일치한다") {
            // given
            val scanner =
                ClassPathScanningCandidateComponentProvider(false).apply {
                    addIncludeFilter(AssignableTypeFilter(ErrorCode::class.java))
                }
            val declared =
                scanner
                    .findCandidateComponents(BASE_PACKAGE)
                    .flatMap { candidate ->
                        val type = Class.forName(candidate.beanClassName)
                        checkNotNull(type.enumConstants) { "${type.name}은 enum이 아니어서 오류 코드를 읽을 수 없습니다." }
                            .map { (it as ErrorCode).let { errorCode -> "${errorCode.code}: ${errorCode.message}" } }
                    }.toSet()

            // when
            val documented =
                loadSpecs("classpath:api-docs/**/*.yml")
                    .flatMap { spec -> errorExamples(spec.content) }
                    .toSet()

            // then
            assertSoftly {
                withClue("서버 정의와 다른 오류 예제") { (documented - declared).sorted() shouldBe emptyList() }
                withClue("명세에 예제가 없거나 다른 오류 코드") { (declared - documented).sorted() shouldBe emptyList() }
            }
        }

        test("루트 명세가 도메인 명세의 모든 경로를 참조한다") {
            // given
            val expected =
                loadSpecs("classpath:api-docs/domains/*.yml")
                    .flatMap { spec ->
                        spec.paths.keys.map { path ->
                            "$path -> ./domains/${spec.name}#/paths/${path.replace("~", "~0").replace("/", "~1")}"
                        }
                    }.toSet()

            // when
            val referenced =
                loadSpecs("classpath:api-docs/openapi.yml")
                    .flatMap { spec -> spec.paths.map { (path, pathItem) -> "$path -> ${pathItem["\$ref"]}" } }
                    .toSet()

            // then
            assertSoftly {
                withClue("루트 명세에 없거나 참조가 다른 경로") { (expected - referenced).sorted() shouldBe emptyList() }
                withClue("도메인 명세에 없는 루트 경로") { (referenced - expected).sorted() shouldBe emptyList() }
            }
        }
    }

    private fun loadSpecs(locationPattern: String): List<ApiSpecFile> =
        PathMatchingResourcePatternResolver().getResources(locationPattern).map { resource ->
            ApiSpecFile(name = checkNotNull(resource.filename), content = resource.inputStream.use { Yaml().load(it) })
        }

    // 오류 응답은 공통 ErrorResponse 스키마를 참조하므로, 그 미디어 타입의 예제를 모두 오류 예제로 봅니다.
    private fun errorExamples(node: Any?): List<String> =
        when (node) {
            is Map<*, *> -> {
                val schemaRef = (node["schema"] as? Map<*, *>)?.get("\$ref") as? String
                val examples = node["examples"] as? Map<*, *>
                if (schemaRef != null && schemaRef.endsWith(ERROR_RESPONSE_SCHEMA) && examples != null) {
                    examples.values.map { example ->
                        val value = (example as? Map<*, *>)?.get("value") as? Map<*, *>
                        "${value?.get("code")}: ${value?.get("message")}"
                    }
                } else {
                    node.values.flatMap { errorExamples(it) }
                }
            }

            is List<*> -> {
                node.flatMap { errorExamples(it) }
            }

            else -> {
                emptyList()
            }
        }

    companion object {
        private const val BASE_PACKAGE = "com.ject"
        private val OPERATION_KEYS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
        private const val ERROR_RESPONSE_SCHEMA = "#/schemas/ErrorResponse"
    }

    private class ApiSpecFile(
        val name: String,
        val content: Map<String, Any?>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val paths: Map<String, Map<String, Any?>>
            get() = (content["paths"] as Map<String, Map<String, Any?>>?).orEmpty()
    }
}
