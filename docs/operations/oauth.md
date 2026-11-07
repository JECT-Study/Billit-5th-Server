# OAuth 제공자 연동

## 범위

`external/oauth`는 Google, Apple, Kakao의 인가 코드를 교환하고 제공자 사용자 식별자를 반환합니다. 로그인, 회원 생성, 서비스 토큰 발급과 로그아웃은 수행하지 않습니다. 인증 업무에서는 Port와 Adapter를 통해 호출합니다.

| 클라이언트 | 입력 | 신원 확인 |
| --- | --- | --- |
| `GoogleOAuthClient` | code, 선택 codeVerifier와 expectedNonce | ID token 서명, 발급자, audience, 만료와 subject 검증 |
| `AppleOAuthClient` | code, 선택 expectedNonce | ID token 서명, 발급자, audience, 만료와 subject 검증 |
| `KakaoOAuthClient` | code | access token으로 사용자 정보 API를 호출해 회원번호 확인 |

`providerUserId`는 제공자 내부 식별자입니다. 이메일로 계정을 식별하거나 서로 다른 제공자의 계정을 자동 연결하지 않습니다. 반환값에는 제공자 토큰과 프로필을 포함하지 않습니다.

Google과 Apple의 ID token은 Nimbus JOSE + JWT로 검증합니다. RS256만 허용하며 제공자별 JWKS에서 공개 키를 조회하고 캐시합니다. Spring Security의 OAuth 패키지는 사용하지 않습니다.

## 호출 계약

- OAuth 인증 요청의 state 생성, 저장과 검증은 호출하는 인증 흐름에서 담당합니다. 인가 코드만 전달받았다는 이유로 요청의 연관성이 검증되었다고 판단하지 않습니다.
- Google 인증 요청에서 PKCE를 사용하면 해당 요청의 codeVerifier를 전달합니다.
- Google과 Apple 인증 요청에 nonce를 사용하면 저장해 둔 기대값을 expectedNonce로 전달합니다. 사용자 입력을 그대로 기대값으로 사용하지 않습니다. Apple SDK가 nonce를 해시해 전송했다면 ID token에 들어갈 해시값을 전달합니다.
- client ID와 redirect URI는 서버 설정을 사용하며 인가 코드를 발급받은 앱과 요청에 일치해야 합니다. 현재는 제공자마다 한 개의 클라이언트 설정을 지원합니다.
- Apple의 native 앱 코드 교환에서는 redirect URI를 비워 둘 수 있습니다. 웹 인증에 사용했다면 동일한 URI를 설정합니다.
- 인가 코드는 일회용이므로 코드 교환 요청을 자동 재시도하지 않습니다.

## 설정

제공자별 `app.oauth.<provider>.enabled`의 기본값은 false입니다. 활성화한 제공자만 클라이언트와 검증기를 구성합니다. 구성 시 제공자 서버에 요청하지 않습니다.

아래 PROVIDER에는 GOOGLE, APPLE 또는 KAKAO를 사용합니다.

| 환경변수 | 의미 |
| --- | --- |
| `OAUTH_<PROVIDER>_ENABLED` | 연동 활성화 여부 |
| `OAUTH_<PROVIDER>_CLIENT_ID` | 코드가 발급된 OAuth 클라이언트 ID. Kakao는 REST API 키 |
| `OAUTH_<PROVIDER>_CLIENT_SECRET` | 클라이언트 인증 정보. Kakao는 미사용 시 빈 값 |
| `OAUTH_<PROVIDER>_REDIRECT_URI` | 인증 요청에 사용한 redirect URI |

Google과 Apple은 활성화 시 client ID와 client secret을 필수로 받습니다. Google과 Kakao는 redirect URI도 필수입니다. test 프로필에서는 환경변수를 읽지 않고 모든 제공자를 비활성화합니다.

Apple client secret은 개발자 개인 키로 생성한 서명 JWT를 주입합니다. 생성과 자동 갱신은 이 클라이언트의 범위에 포함하지 않으며, 운영 환경에서 만료 전에 교체합니다.

HTTP 연결 타임아웃은 2초, 응답 타임아웃은 5초입니다. Google과 Apple 공개 키 조회에도 같은 타임아웃을 적용합니다.

## 실패 계약

`OAuthClientException`은 제공자 응답 원문과 인가 코드, 토큰을 포함하지 않습니다.

| reason | 의미 |
| --- | --- |
| `INVALID_AUTHORIZATION` | invalid_grant, access_denied 또는 nonce 불일치 |
| `CONFIGURATION` | invalid_client 또는 unauthorized_client |
| `UNAVAILABLE` | 연결 실패, 타임아웃, HTTP 429 또는 5xx, 서명 검증용 공개 키 조회 실패 |
| `INVALID_RESPONSE` | 누락되거나 파싱할 수 없는 응답, ID token 검증 실패와 그 외 제공자 오류 |

HTTP 상태 코드는 응답을 받은 경우 statusCode로 제공합니다. 신원 검증에 실패하면 사용자 식별자를 반환하지 않습니다. HTTP 응답으로의 변환과 업무별 오류 해석은 auth가 담당합니다.

## 참고

- [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)
- [Apple token validation](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens)
- [Kakao Login REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api)
