package com.ject.module.expense.presentation

import com.ject.global.error.BusinessException
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ReceiptRecognitionResult
import com.ject.module.expense.usecase.port.ReceiptRecognitionPort
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import jakarta.servlet.ServletContext
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptRecognitionControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    @Autowired
    private lateinit var servletContext: ServletContext

    @MockitoBean
    private lateinit var receiptRecognitionPort: ReceiptRecognitionPort

    private val httpClient = HttpClient.newHttpClient()
    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("품목별 인식은 품목과 금액을 소수점 이하 2자리 문자열로 받고, 이미지는 Tomcat 임시 파일 없이 받아 인식 포트에 그대로 넘긴다") {
            // given
            val trip = createTrip()
            val image = JPEG + ByteArray(2 * 1024 * 1024)
            val received = mutableListOf<Pair<List<Byte>, String>>()
            val uploadFilesDuringRecognition = mutableListOf<List<String>>()
            doAnswer { invocation ->
                received.add((invocation.getArgument<ByteArray>(0)).toList() to invocation.getArgument(1))
                uploadFilesDuringRecognition.add(uploadTempFiles())
                ReceiptRecognitionResult(
                    name = "Sushi Bar",
                    paidDate = LocalDate.parse("2026-11-05"),
                    currency = ExpenseCurrency.USD,
                    originalAmount = BigDecimal("12.3"),
                    discountAmount = null,
                    items =
                        listOf(
                            ReceiptRecognitionResult.Item("Sushi", BigDecimal("10"), BigDecimal("1.5")),
                            ReceiptRecognitionResult.Item(null, BigDecimal("2.345"), BigDecimal("-1")),
                        ),
                )
            }.`when`(receiptRecognitionPort).recognize(anyImage(), anyString())

            // when
            val response = recognize(trip.tripId, accessToken(trip.memberMemberId), image, "image/jpeg", """{"splitType":"BY_ITEM"}""")

            // then
            assertSoftly {
                response.statusCode() shouldBe 200
                body(response) shouldBe
                    mapOf(
                        "data" to
                            mapOf(
                                "name" to "Sushi Bar",
                                "paidDate" to "2026-11-05",
                                "currency" to "USD",
                                "originalAmount" to "12.30",
                                "discountAmount" to null,
                                "items" to
                                    listOf(
                                        mapOf("name" to "Sushi", "amount" to "10.00", "discountAmount" to "1.50"),
                                        mapOf("name" to null, "amount" to null, "discountAmount" to null),
                                    ),
                            ),
                    )
                received shouldBe listOf(image.toList() to "image/jpeg")
                uploadFilesDuringRecognition shouldBe listOf(emptyList())
            }
        }

        test("전체 1/N 인식은 품목을 비우고 품목의 상품 할인을 전체 할인에 더하며, 받을 수 없는 금액은 null로 받는다") {
            // given
            val trip = createTrip()
            doReturn(
                ReceiptRecognitionResult(
                    name = "Cafe",
                    paidDate = null,
                    currency = ExpenseCurrency.KRW,
                    originalAmount = BigDecimal("10000000000"),
                    discountAmount = BigDecimal("100"),
                    items =
                        listOf(
                            ReceiptRecognitionResult.Item("Coffee", BigDecimal("5000"), BigDecimal("50")),
                            ReceiptRecognitionResult.Item("Cake", BigDecimal("6000"), null),
                        ),
                ),
            ).`when`(receiptRecognitionPort).recognize(anyImage(), anyString())

            // when
            val response = recognize(trip.tripId, accessToken(trip.memberMemberId), PNG, "image/png", """{"splitType":"EQUAL"}""")

            // then
            assertSoftly {
                response.statusCode() shouldBe 200
                body(response)["data"] shouldBe
                    mapOf(
                        "name" to "Cafe",
                        "paidDate" to null,
                        "currency" to "KRW",
                        "originalAmount" to null,
                        "discountAmount" to "150.00",
                        "items" to emptyList<Any>(),
                    )
            }
        }

        test("아무 값도 읽지 못하면 단일 값은 모두 null, items는 빈 배열인 결과를 200으로 받는다") {
            // given
            val trip = createTrip()
            doReturn(ReceiptRecognitionResult(null, null, null, null, null, emptyList()))
                .`when`(receiptRecognitionPort)
                .recognize(anyImage(), anyString())

            // when
            val response = recognize(trip.tripId, accessToken(trip.memberMemberId), WEBP, "image/webp", """{"splitType":"BY_ITEM"}""")

            // then
            assertSoftly {
                response.statusCode() shouldBe 200
                body(response)["data"] shouldBe
                    mapOf(
                        "name" to null,
                        "paidDate" to null,
                        "currency" to null,
                        "originalAmount" to null,
                        "discountAmount" to null,
                        "items" to emptyList<Any>(),
                    )
            }
        }

        test("받지 않는 형식이거나 내용이 요청한 형식과 다르거나 빈 이미지는 415 EXPENSE_RECEIPT_UNSUPPORTED이고 인식을 요청하지 않는다") {
            // given
            val trip = createTrip()
            val accessToken = accessToken(trip.memberMemberId)
            val images =
                mapOf(
                    "GIF" to Pair("GIF89a".toByteArray(), "image/gif"),
                    "JPEG로 보낸 PNG" to Pair(PNG, "image/jpeg"),
                    "AVIF로 만든 HEIC" to Pair(heic("avif"), "image/heic"),
                    "빈 이미지" to Pair(ByteArray(0), "image/jpeg"),
                )

            // when
            val responses =
                images.mapValues {
                    recognize(
                        trip.tripId,
                        accessToken,
                        it.value.first,
                        it.value.second,
                        """{"splitType":"EQUAL"}""",
                    )
                }

            // then
            assertSoftly {
                responses.mapValues { it.value.statusCode() } shouldBe images.mapValues { 415 }
                responses.mapValues { body(it.value) } shouldBe
                    images.mapValues { errorBody("EXPENSE_RECEIPT_UNSUPPORTED", "JPG, PNG, HEIC, WEBP 형식의 영수증 이미지를 선택해주세요.") }
                verify(receiptRecognitionPort, never()).recognize(anyImage(), anyString())
            }
        }

        test("미디어 타입의 대소문자는 무시하고 HEIC 이미지는 인식 포트에 image/heic로 넘긴다") {
            // given
            val trip = createTrip()
            val mediaTypes = mutableListOf<String>()
            doAnswer { invocation ->
                mediaTypes.add(invocation.getArgument(1))
                ReceiptRecognitionResult(null, null, null, null, null, emptyList())
            }.`when`(receiptRecognitionPort).recognize(anyImage(), anyString())

            // when
            val response = recognize(trip.tripId, accessToken(trip.memberMemberId), heic("heic"), "image/HEIC", """{"splitType":"EQUAL"}""")

            // then
            assertSoftly {
                response.statusCode() shouldBe 200
                mediaTypes shouldBe listOf("image/heic")
            }
        }

        test("15MB 이미지는 받고 15MB를 넘는 이미지는 413 EXPENSE_RECEIPT_TOO_LARGE로 거절한다") {
            // given
            val trip = createTrip()
            val accessToken = accessToken(trip.memberMemberId)
            doReturn(ReceiptRecognitionResult(null, null, null, null, null, emptyList()))
                .`when`(receiptRecognitionPort)
                .recognize(anyImage(), anyString())
            val limit = 15 * 1024 * 1024

            // when
            val atLimit =
                recognize(trip.tripId, accessToken, JPEG + ByteArray(limit - JPEG.size), "image/jpeg", """{"splitType":"EQUAL"}""")
            val overLimit =
                recognize(trip.tripId, accessToken, JPEG + ByteArray(limit - JPEG.size + 1), "image/jpeg", """{"splitType":"EQUAL"}""")

            // then
            assertSoftly {
                atLimit.statusCode() shouldBe 200
                overLimit.statusCode() shouldBe 413
                body(overLimit) shouldBe errorBody("EXPENSE_RECEIPT_TOO_LARGE", "15MB 이하의 영수증 이미지를 선택해주세요.")
            }
        }

        test("파트가 없거나 request가 올바르지 않으면 400, multipart 요청이 아니면 415 COMMON_UNSUPPORTED_MEDIA_TYPE을 받는다") {
            // given
            val trip = createTrip()
            val accessToken = accessToken(trip.memberMemberId)

            // when
            val responses =
                mapOf(
                    "image 파트 없음" to recognize(trip.tripId, accessToken, null, null, """{"splitType":"EQUAL"}"""),
                    "request 파트 없음" to recognize(trip.tripId, accessToken, JPEG, "image/jpeg", null),
                    "허용하지 않는 분배 방식" to recognize(trip.tripId, accessToken, JPEG, "image/jpeg", """{"splitType":"ITEM"}"""),
                    "정의하지 않은 필드" to recognize(trip.tripId, accessToken, JPEG, "image/jpeg", """{"splitType":"EQUAL","memo":"x"}"""),
                )
            val notMultipart =
                httpClient.send(
                    HttpRequest
                        .newBuilder(uri(trip.tripId))
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"splitType":"EQUAL"}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

            // then
            assertSoftly {
                responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 400 }
                responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "COMMON_INVALID_REQUEST" }
                notMultipart.statusCode() shouldBe 415
                body(notMultipart)["code"] shouldBe "COMMON_UNSUPPORTED_MEDIA_TYPE"
            }
        }

        test("여행이 없으면 404, 참여 중인 회원이 아니면 403, 확정 전 여행이면 409 EXPENSE_TRIP_NOT_CONFIRMED를 받는다") {
            // given
            val trip = createTrip()
            val unconfirmedTrip = createTrip(status = "UNCONFIRMED")
            val outsiderMemberId = createMember()

            // when
            val notFound = recognize(Long.MAX_VALUE, accessToken(trip.memberMemberId), JPEG, "image/jpeg", """{"splitType":"EQUAL"}""")
            val forbidden = recognize(trip.tripId, accessToken(outsiderMemberId), JPEG, "image/jpeg", """{"splitType":"EQUAL"}""")
            val unconfirmed =
                recognize(
                    unconfirmedTrip.tripId,
                    accessToken(unconfirmedTrip.memberMemberId),
                    JPEG,
                    "image/jpeg",
                    """{"splitType":"EQUAL"}""",
                )

            // then
            assertSoftly {
                notFound.statusCode() shouldBe 404
                body(notFound)["code"] shouldBe "COMMON_NOT_FOUND"
                forbidden.statusCode() shouldBe 403
                body(forbidden)["code"] shouldBe "COMMON_FORBIDDEN"
                unconfirmed.statusCode() shouldBe 409
                body(unconfirmed) shouldBe errorBody("EXPENSE_TRIP_NOT_CONFIRMED", "여행을 확정한 뒤에 지출을 등록할 수 있습니다.")
                verify(receiptRecognitionPort, never()).recognize(anyImage(), anyString())
            }
        }

        test("인식 포트가 영수증이 아니라고 하면 400 EXPENSE_NOT_RECEIPT, 인식할 수 없다고 하면 503 EXPENSE_RECOGNITION_UNAVAILABLE과 안내 문구를 받는다") {
            // given
            val trip = createTrip()
            val accessToken = accessToken(trip.memberMemberId)
            val failures = listOf(ExpenseErrorCode.NOT_RECEIPT, ExpenseErrorCode.RECOGNITION_UNAVAILABLE)

            // when
            val responses =
                failures.map {
                    doThrow(BusinessException(it)).`when`(receiptRecognitionPort).recognize(anyImage(), anyString())
                    recognize(trip.tripId, accessToken, PNG, "image/png", """{"splitType":"EQUAL"}""")
                }

            // then
            assertSoftly {
                responses.map { it.statusCode() } shouldBe listOf(400, 503)
                responses.map { body(it) } shouldBe
                    listOf(
                        errorBody("EXPENSE_NOT_RECEIPT", "영수증을 찾지 못했습니다. 영수증 사진을 다시 선택해주세요."),
                        errorBody("EXPENSE_RECOGNITION_UNAVAILABLE", "영수증 인식을 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
                    )
            }
        }
    }

    /** Mockito의 `any()`는 `null`을 반환해 Kotlin의 null 검사에 걸리므로 빈 배열로 바꿔 넘깁니다. 매처는 그대로 기록됩니다. */
    private fun anyImage(): ByteArray = any(ByteArray::class.java) ?: ByteArray(0)

    /** Tomcat이 업로드를 디스크로 받을 때 임시 디렉터리에 만드는 `upload_*.tmp` 파일입니다. */
    private fun uploadTempFiles(): List<String> {
        val tempDir = servletContext.getAttribute(ServletContext.TEMPDIR) as File
        return tempDir
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("upload_") }
            .map { it.name }
            .toList()
    }

    /** [image]나 [requestJson]이 `null`이면 그 파트를 보내지 않습니다. */
    private fun recognize(
        tripId: Long,
        token: String,
        image: ByteArray?,
        imageType: String?,
        requestJson: String?,
    ): HttpResponse<String> {
        val boundary = "receipt-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream()
        if (image != null) {
            body.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"receipt\"\r\nContent-Type: $imageType\r\n\r\n"
                    .toByteArray(),
            )
            body.write(image)
            body.write("\r\n".toByteArray())
        }
        if (requestJson != null) {
            body.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n$requestJson\r\n"
                    .toByteArray(),
            )
        }
        body.write("--$boundary--\r\n".toByteArray())
        return httpClient.send(
            HttpRequest
                .newBuilder(uri(tripId))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun uri(tripId: Long): URI =
        URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}/api/v1/trips/$tripId/receipt-recognitions")

    private fun heic(brand: String): ByteArray = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".toByteArray() + ByteArray(16)

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    private fun errorBody(
        code: String,
        message: String,
    ): Map<String, Any?> = mapOf("code" to code, "message" to message, "errors" to null)

    private fun accessToken(memberId: Long): String = authTokenCreator.createMemberTokens(memberId).accessToken

    private fun createTrip(status: String = "CONFIRMED"): TripFixture {
        val key = UUID.randomUUID()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 3, ?::trip_status, ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    status,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(tripId)
        val memberMemberId = createMember()
        jdbc.update(
            "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                "VALUES (?, ?, '민지', 'ADMIN', 'MEMBER', now(), now())",
            tripId,
            memberMemberId,
        )
        return TripFixture(tripId, memberMemberId)
    }

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    private data class TripFixture(
        val tripId: Long,
        val memberMemberId: Long,
    )

    companion object {
        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        private val WEBP = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray()
    }
}
