/** uq_manual_exchange_rate_record = 같은 여행에서 같은 등록자가 통화, 환전 날짜, 환전 수단과 환율이 모두 같은 기록을 두 번 등록할 수 없음 | 환율이 같아도 환전 날짜가 다르면 별개 기록 **/
ALTER TABLE manual_exchange_rate
    ADD CONSTRAINT uq_manual_exchange_rate_record UNIQUE (trip_id, created_by_trip_member_id, currency, exchange_date, exchange_method, rate);
