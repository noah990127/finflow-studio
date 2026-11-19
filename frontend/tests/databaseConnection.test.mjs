import test from 'node:test'
import assert from 'node:assert/strict'
import { buildDatabaseJdbcUrl, parseDatabaseConnection } from '../src/domain/databaseConnection.ts'

test('parses a PostgreSQL URL and removes embedded credentials', () => {
  const result = parseDatabaseConnection('postgresql://finance_user:p%40ss@db.example.com:5432/budget?sslmode=require')
  assert.deepEqual(result, {
    sourceType: 'POSTGRESQL',
    jdbcUrl: 'jdbc:postgresql://db.example.com:5432/budget?sslmode=require',
    host: 'db.example.com',
    port: '5432',
    username: 'finance_user',
    password: 'p@ss',
    database: 'budget',
    parameters: 'sslmode=require',
  })
})

test('parses JDBC query credentials without retaining the password in the URL', () => {
  const result = parseDatabaseConnection('jdbc:mysql://mysql.example.com:3306/ops?user=reader&password=secret&useSSL=true')
  assert.equal(result?.sourceType, 'MYSQL')
  assert.equal(result?.username, 'reader')
  assert.equal(result?.password, 'secret')
  assert.equal(result?.jdbcUrl, 'jdbc:mysql://mysql.example.com:3306/ops?useSSL=true')
})

test('parses libpq key value connection strings', () => {
  const result = parseDatabaseConnection('host=pg.internal port=5433 dbname=forecast user=analyst password=hello sslmode=require')
  assert.equal(result?.jdbcUrl, 'jdbc:postgresql://pg.internal:5433/forecast?sslmode=require')
  assert.equal(result?.username, 'analyst')
  assert.equal(result?.password, 'hello')
})

test('uses the selected DWS type for PostgreSQL-compatible addresses', () => {
  const result = parseDatabaseConnection('dws.internal:8000/finance', 'GAUSS_DWS')
  assert.equal(result?.sourceType, 'GAUSS_DWS')
  assert.equal(result?.jdbcUrl, 'jdbc:postgresql://dws.internal:8000/finance')
})

test('rebuilds the JDBC URL from user-confirmed fields', () => {
  assert.equal(buildDatabaseJdbcUrl({
    sourceType: 'MYSQL', host: 'mysql.internal', port: '3307', database: 'sales', parameters: 'useSSL=true',
  }), 'jdbc:mysql://mysql.internal:3307/sales?useSSL=true')
})
