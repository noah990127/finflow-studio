package com.finflow.studio.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTimeConfigurationTest {
    @Test
    void bindsInstantWithoutChangingPrecisionOrOtherParameterTypes() {
        var instant = Instant.parse("2026-11-19T01:02:03.123456789Z");
        var ids = List.of("a", "b");
        var params = new MapSqlParameterSource().addValue("at", instant)
                .addValue("ids", ids).addValue("optional", null, Types.VARCHAR, "varchar");
        var bound = new DatabaseTimeConfiguration.TimestampParameters(params);
        assertThat(((Timestamp) bound.getValue("at")).toInstant()).isEqualTo(instant);
        assertThat(bound.getValue("ids")).isSameAs(ids);
        assertThat(bound.getValue("optional")).isNull();
        assertThat(bound.getSqlType("optional")).isEqualTo(Types.VARCHAR);
        assertThat(bound.getTypeName("optional")).isEqualTo("varchar");
        assertThat(bound.getParameterNames()).containsExactly("at", "ids", "optional");
        assertThat(bound.hasValue("absent")).isFalse();
    }
}
