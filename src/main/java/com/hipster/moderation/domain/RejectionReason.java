package com.hipster.moderation.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RejectionReason {
    INSUFFICIENT_SOURCE("불충분한 소스"),
    INCORRECT_INFORMATION("부정확한 정보"),
    DUPLICATE_SUBMISSION("중복 제출"),
    SPAM_ABUSE("스팸/악용"),
    DOES_NOT_MEET_GUIDELINES("가이드라인 위반"),
    REPURPOSING_ATTEMPT("재목적화 시도 - 금지!"),
    OTHER("기타");

    private final String description;
}
