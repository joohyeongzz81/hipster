package com.hipster.global.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ISO 639-1 기반 언어 코드
 */
@Getter
@RequiredArgsConstructor
public enum Language {
    KO("Korean"),
    EN("English"),
    JA("Japanese"),
    ZH("Chinese"),
    ES("Spanish"),
    FR("French"),
    DE("German"),
    IT("Italian"),
    PT("Portuguese"),
    RU("Russian"),
    OTHER("Other");

    private final String displayName;
}
