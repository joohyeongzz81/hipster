package com.hipster.chart.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@Document(indexName = "chart_scores")
public class ChartDocument {

    @Id
    private Long releaseId;

    @Field(type = FieldType.Double)
    private Double bayesianScore;

    @Field(type = FieldType.Boolean)
    private Boolean isEsoteric;

    @Field(type = FieldType.Keyword)
    private String releaseType;

    @Field(type = FieldType.Integer)
    private Integer releaseYear;

    @Field(type = FieldType.Long)
    private Long locationId;

    // ES는 배열을 기본 지원하므로 List로 선언. 매핑 타입은 단일 요소의 타입(Integer)으로 지정
    @Field(type = FieldType.Integer)
    private List<Integer> genreIds;

    // isPrimary=true인 genreId만 별도로 추출하여 저장할 필드
    @Field(type = FieldType.Integer)
    private List<Integer> isPrimaryGenreIds;

    @Field(type = FieldType.Integer)
    private List<Integer> descriptorIds;

    // 정확히 일치하는 검색을 위해 Keyword 타입으로 지정
    @Field(type = FieldType.Keyword)
    private List<String> languages;
}