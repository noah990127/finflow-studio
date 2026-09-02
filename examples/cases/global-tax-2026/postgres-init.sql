CREATE SCHEMA IF NOT EXISTS demo_examples;

CREATE TABLE IF NOT EXISTS demo_examples.global_tax_entity_exposure (
    entity_code varchar(20) PRIMARY KEY,
    region varchar(40) NOT NULL,
    jurisdiction varchar(80) NOT NULL,
    revenue_usd_m numeric(16,2) NOT NULL,
    covered_tax_usd_m numeric(16,2) NOT NULL,
    pillar_two_scope boolean NOT NULL,
    cbam_import_usd_m numeric(16,2) NOT NULL,
    vida_transaction_count bigint NOT NULL,
    data_completeness_pct numeric(8,2) NOT NULL,
    owner varchar(80) NOT NULL
);

TRUNCATE TABLE demo_examples.global_tax_entity_exposure;
INSERT INTO demo_examples.global_tax_entity_exposure VALUES
('EU-DE-01','欧洲','德国',980,118,true,42,680000,94,'欧洲税务'),
('EU-NL-01','欧洲','荷兰',720,61,true,18,510000,88,'欧洲税务'),
('EU-PL-01','欧洲','波兰',430,24,true,55,290000,79,'欧洲税务'),
('US-CA-01','北美','美国',1680,250,true,0,0,96,'美洲税务'),
('SG-01','亚太','新加坡',860,72,true,0,150000,83,'亚太税务'),
('CN-01','亚太','中国',1140,142,true,0,320000,91,'亚太税务'),
('BR-01','拉美','巴西',390,52,false,0,210000,74,'拉美税务'),
('AE-01','中东','阿联酋',310,25,true,0,68000,68,'中东税务');

COMMENT ON TABLE demo_examples.global_tax_entity_exposure IS '完全模拟的跨国企业税务暴露数据，仅用于 FinFlow 产品演示。';
