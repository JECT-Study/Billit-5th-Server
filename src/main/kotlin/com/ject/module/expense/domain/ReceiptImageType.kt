package com.ject.module.expense.domain

/** 영수증 인식에 받는 이미지 형식입니다. 요청한 미디어 타입과 파일 앞부분의 형식 표시가 모두 맞아야 그 형식으로 봅니다. */
enum class ReceiptImageType(
    val mediaType: String,
) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    HEIC("image/heic"),
    WEBP("image/webp"),
    ;

    private fun matches(bytes: ByteArray): Boolean =
        when (this) {
            JPEG -> bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            PNG -> bytes.ascii(0, 8) == "\u0089PNG\r\n\u001A\n"
            HEIC -> bytes.ascii(4, 8) == "ftyp" && bytes.ascii(8, 12) in HEIC_BRANDS
            WEBP -> bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 12) == "WEBP"
        }

    companion object {
        /** HEIF 컨테이너 중 HEVC 이미지를 담는 주 브랜드입니다. AVIF(`avif`)는 받지 않습니다. */
        private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

        /** [mediaType]이 받는 형식이고 [bytes]의 형식 표시가 그 형식과 같으면 그 형식을, 아니면 `null`을 반환합니다. 미디어 타입의 매개변수와 대소문자는 무시합니다. */
        fun of(
            mediaType: String?,
            bytes: ByteArray,
        ): ReceiptImageType? {
            val requested = mediaType?.substringBefore(';')?.trim() ?: return null
            return entries.find { it.mediaType.equals(requested, ignoreCase = true) }?.takeIf { it.matches(bytes) }
        }

        private fun ByteArray.ascii(
            from: Int,
            to: Int,
        ): String? = if (size >= to) String(this, from, to - from, Charsets.ISO_8859_1) else null
    }
}
