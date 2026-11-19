package com.finflow.studio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementCreatorFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Consumer;

@Configuration(proxyBeanMethods = false)
class DatabaseTimeConfiguration {
    @Bean
    JdbcClient jdbcClient(JdbcTemplate jdbc) {
        var named = new NamedParameterJdbcTemplate(jdbc) {
            @Override
            protected PreparedStatementCreator getPreparedStatementCreator(String sql, SqlParameterSource params,
                    Consumer<PreparedStatementCreatorFactory> customizer) {
                return super.getPreparedStatementCreator(sql, new TimestampParameters(params), customizer);
            }
        };
        return JdbcClient.create(named);
    }

    // pgJDBC cannot infer a SQL type from Instant. Keep the instant (including nanos) and
    // all metadata supplied by the caller, using JDBC's timestamp representation at the boundary.
    record TimestampParameters(SqlParameterSource source) implements SqlParameterSource {
        @Override public boolean hasValue(String name) { return source.hasValue(name); }
        @Override public Object getValue(String name) {
            var value = source.getValue(name);
            return value instanceof Instant instant ? Timestamp.from(instant) : value;
        }
        @Override public int getSqlType(String name) { return source.getSqlType(name); }
        @Override public String getTypeName(String name) { return source.getTypeName(name); }
        @Override public String[] getParameterNames() { return source.getParameterNames(); }
    }
}
