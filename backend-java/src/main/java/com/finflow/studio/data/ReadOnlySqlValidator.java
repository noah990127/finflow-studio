package com.finflow.studio.data;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

@Component
public class ReadOnlySqlValidator {

    public String validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("查询语句不能为空");
        }
        if (sql.length() > 200_000) {
            throw new IllegalArgumentException("查询语句过长");
        }
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql).getStatements();
            if (statements.size() != 1 || !(statements.getFirst() instanceof Select)) {
                throw new IllegalArgumentException("只允许执行单条只读查询");
            }
            return sql.trim();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法识别查询语句，请检查 SQL 语法", exception);
        }
    }
}
