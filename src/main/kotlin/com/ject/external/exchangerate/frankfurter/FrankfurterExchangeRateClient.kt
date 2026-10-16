package com.ject.external.exchangerate.frankfurter

import com.ject.external.exchangerate.ExchangeRateClient
import com.ject.external.exchangerate.ExchangeRateData
import com.ject.external.exchangerate.ExchangeRateException
import com.ject.external.exchangerate.ExchangeRateFailureReason
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

class FrankfurterExchangeRateClient(
    private val restClient: RestClient,
    private val source: String,
    private val clock: Clock,
) : ExchangeRateClient {
    override fun fetch(
        base: String,
        quotes: Set<String>,
        date: LocalDate,
    ): List<ExchangeRateData> {
        requireCurrency(base)
        require(quotes.isNotEmpty()) { "대상 통화를 하나 이상 지정해야 합니다." }
        quotes.forEach { requireCurrency(it) }
        require(!date.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) { "환율 조회 날짜는 오늘 이후일 수 없습니다." }

        val rows =
            try {
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/v2/providers/{source}/rates")
                            .queryParam("base", base)
                            .queryParam("quotes", quotes.sorted().joinToString(","))
                            .queryParam("date", date)
                            .build(source.lowercase(Locale.ROOT))
                    }.accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Array<FrankfurterRate?>::class.java)
            } catch (exception: ResourceAccessException) {
                val timeout =
                    generateSequence<Throwable>(exception) { it.cause }
                        .any { it is HttpTimeoutException || it is SocketTimeoutException }
                val reason = if (timeout) ExchangeRateFailureReason.TIMEOUT else ExchangeRateFailureReason.CONNECTION
                throw ExchangeRateException(reason, cause = exception)
            } catch (exception: RestClientResponseException) {
                throw ExchangeRateException(ExchangeRateFailureReason.HTTP_ERROR, exception.statusCode.value(), exception)
            } catch (exception: RestClientException) {
                throw ExchangeRateException(ExchangeRateFailureReason.INVALID_RESPONSE, cause = exception)
            } ?: throw ExchangeRateException(ExchangeRateFailureReason.INVALID_RESPONSE)

        val result = mutableListOf<ExchangeRateData>()
        val seen = mutableSetOf<String>()
        for (nullableRow in rows) {
            val row = nullableRow ?: invalidResponse("환율 응답 항목이 null입니다.")
            val rateDate = row.date ?: invalidResponse("환율 기준일이 누락되었습니다.")
            val quote = row.quote ?: invalidResponse("대상 통화가 누락되었습니다.")
            val rate = row.rate ?: invalidResponse("환율 값이 누락되었습니다.")

            if (row.base != base) {
                invalidResponse("응답의 기준 통화가 요청과 다릅니다.")
            }
            if (quote !in quotes) {
                invalidResponse("요청하지 않은 대상 통화가 응답에 포함되어 있습니다.")
            }
            if (rateDate.isAfter(date)) {
                invalidResponse("환율 기준일이 요청 날짜보다 미래입니다.")
            }
            if (rate.signum() <= 0) {
                invalidResponse("환율은 0보다 커야 합니다.")
            }
            if (quote == base && rate.compareTo(BigDecimal.ONE) != 0) {
                invalidResponse("기준 통화와 대상 통화가 같으면 환율은 1이어야 합니다.")
            }
            if (!seen.add(quote)) {
                invalidResponse("응답에 대상 통화가 중복되어 있습니다.")
            }

            result.add(ExchangeRateData(date = rateDate, base = base, quote = quote, rate = rate, source = source))
        }
        return result.sortedWith(compareBy(ExchangeRateData::date, ExchangeRateData::quote))
    }

    private fun invalidResponse(message: String): Nothing =
        throw ExchangeRateException(ExchangeRateFailureReason.INVALID_RESPONSE, message = message)

    private fun requireCurrency(code: String) {
        require(code.matches(Regex("[A-Z]{3}"))) { "통화는 ISO 4217 대문자 코드여야 합니다." }
        Currency.getInstance(code)
    }

    private data class FrankfurterRate(
        val date: LocalDate?,
        val base: String?,
        val quote: String?,
        val rate: BigDecimal?,
    )
}
