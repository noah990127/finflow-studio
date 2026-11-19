export type DatabaseSourceType = 'POSTGRESQL' | 'MYSQL' | 'OPENGAUSS' | 'GAUSS_DWS' | 'DUCKDB'

export type ParsedDatabaseConnection = {
  sourceType: DatabaseSourceType
  jdbcUrl: string
  host: string
  port: string
  username: string
  password: string
  database: string
  parameters: string
}

const schemeTypes: Record<string, DatabaseSourceType> = {
  postgres: 'POSTGRESQL',
  postgresql: 'POSTGRESQL',
  'postgresql+psycopg2': 'POSTGRESQL',
  mysql: 'MYSQL',
  'mysql+pymysql': 'MYSQL',
  opengauss: 'OPENGAUSS',
  duckdb: 'DUCKDB',
}

function decode(value: string) {
  try { return decodeURIComponent(value) } catch { return value }
}

export function defaultDatabasePort(sourceType: DatabaseSourceType) {
  if (sourceType === 'MYSQL') return '3306'
  if (sourceType === 'GAUSS_DWS') return '8000'
  if (sourceType === 'DUCKDB') return ''
  return '5432'
}

export function buildDatabaseJdbcUrl(fields: Pick<ParsedDatabaseConnection, 'sourceType' | 'host' | 'port' | 'database' | 'parameters'>) {
  if (fields.sourceType === 'DUCKDB') return `jdbc:duckdb:${fields.database.trim()}`
  const scheme = fields.sourceType === 'MYSQL' ? 'mysql' : fields.sourceType === 'OPENGAUSS' ? 'opengauss' : 'postgresql'
  const host = fields.host.trim()
  const port = fields.port.trim()
  const database = fields.database.trim().replace(/^\//, '')
  const parameters = fields.parameters.trim().replace(/^\?/, '')
  return `jdbc:${scheme}://${host}${port ? `:${port}` : ''}/${database}${parameters ? `?${parameters}` : ''}`
}

function parseKeyValue(value: string, fallback: DatabaseSourceType): ParsedDatabaseConnection | null {
  const entries = [...value.matchAll(/(?:^|\s)(host|port|dbname|database|user|username|password|pwd|sslmode)=([^\s]+)/gi)]
  if (!entries.length || !entries.some(item => item[1].toLowerCase() === 'host')) return null
  const fields = Object.fromEntries(entries.map(item => [item[1].toLowerCase(), item[2].replace(/^['"]|['"]$/g, '')]))
  const sourceType = fallback === 'MYSQL' || fallback === 'OPENGAUSS' || fallback === 'GAUSS_DWS' ? fallback : 'POSTGRESQL'
  const port = fields.port || defaultDatabasePort(sourceType)
  const database = fields.dbname || fields.database || ''
  const parameters = fields.sslmode ? `sslmode=${encodeURIComponent(fields.sslmode)}` : ''
  const parsed = {
    sourceType,
    host: fields.host,
    port,
    username: fields.user || fields.username || '',
    password: fields.password || fields.pwd || '',
    database,
    parameters,
  }
  return { ...parsed, jdbcUrl: buildDatabaseJdbcUrl(parsed) }
}

export function parseDatabaseConnection(raw: string, fallback: DatabaseSourceType = 'POSTGRESQL'): ParsedDatabaseConnection | null {
  const value = raw.trim()
  if (!value) return null
  const keyValue = parseKeyValue(value, fallback)
  if (keyValue) return keyValue

  if (/^jdbc:duckdb:/i.test(value)) {
    return { sourceType: 'DUCKDB', jdbcUrl: value, host: '', port: '', username: '', password: '', database: value.slice('jdbc:duckdb:'.length), parameters: '' }
  }

  const jdbc = value.toLowerCase().startsWith('jdbc:')
  const urlValue = jdbc ? value.slice(5) : value.includes('://') ? value : `${fallback === 'MYSQL' ? 'mysql' : fallback === 'OPENGAUSS' ? 'opengauss' : 'postgresql'}://${value}`
  try {
    const url = new URL(urlValue)
    const scheme = url.protocol.slice(0, -1).toLowerCase()
    let sourceType = schemeTypes[scheme]
    if (!sourceType) return null
    if (sourceType === 'POSTGRESQL' && fallback === 'GAUSS_DWS') sourceType = 'GAUSS_DWS'
    const username = decode(url.username || url.searchParams.get('user') || url.searchParams.get('username') || '')
    const password = decode(url.password || url.searchParams.get('password') || url.searchParams.get('pwd') || '')
    url.username = ''
    url.password = ''
    for (const key of ['user', 'username', 'password', 'pwd']) url.searchParams.delete(key)
    const database = decode(url.pathname.replace(/^\//, ''))
    const parsed = {
      sourceType,
      host: url.hostname,
      port: url.port || defaultDatabasePort(sourceType),
      username,
      password,
      database,
      parameters: url.searchParams.toString(),
    }
    return { ...parsed, jdbcUrl: buildDatabaseJdbcUrl(parsed) }
  } catch {
    return null
  }
}
