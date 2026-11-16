ALTER TABLE member
    ADD COLUMN social_display_name VARCHAR(100) /** 소셜 제공자가 준 표시 이름 | 가입 화면의 닉네임 초기값 | 구글 name, 카카오 nickname, 애플 최초 로그인 fullName | NULL = 제공자가 주지 않음 **/;
