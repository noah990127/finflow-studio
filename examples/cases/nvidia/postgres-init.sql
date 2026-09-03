CREATE SCHEMA IF NOT EXISTS demo_examples;

CREATE TABLE IF NOT EXISTS demo_examples.nvidia_quarterly_operating_metrics (
    fiscal_quarter varchar(12) PRIMARY KEY,
    revenue_usd_m numeric(16,2) NOT NULL,
    data_center_revenue_usd_m numeric(16,2) NOT NULL,
    gross_margin_pct numeric(8,2) NOT NULL,
    operating_income_usd_m numeric(16,2) NOT NULL,
    operating_cash_flow_usd_m numeric(16,2) NOT NULL,
    inventory_usd_m numeric(16,2) NOT NULL,
    source_note varchar(240) NOT NULL
);

TRUNCATE TABLE demo_examples.nvidia_quarterly_operating_metrics;
INSERT INTO demo_examples.nvidia_quarterly_operating_metrics VALUES
('FY2025-Q1',26044,22600,78.4,16909,15345,5864,'公开披露口径整理，用于演示'),
('FY2025-Q2',30040,26300,75.1,18642,14677,6675,'公开披露口径整理，用于演示'),
('FY2025-Q3',35082,30800,74.6,21869,17631,7065,'公开披露口径整理，用于演示'),
('FY2025-Q4',39331,35600,73.0,24034,16050,10080,'公开披露口径整理，用于演示'),
('FY2026-Q1',44062,39100,60.5,21380,27500,11270,'包含产品与监管变化影响的演示口径'),
('FY2026-Q2',46743,41100,72.4,28440,23900,12030,'公开披露口径整理，用于演示'),
('FY2026-Q3',57006,51200,73.4,36780,31000,13320,'公开披露口径整理，用于演示'),
('FY2026-Q4',68127,62100,75.0,44700,39000,14200,'FY2026 年度拆分演示值，需回到原始披露复核');

COMMENT ON TABLE demo_examples.nvidia_quarterly_operating_metrics IS 'FinBTP Studio 演示表，混合公开披露整理与明确标注的演示拆分。';
