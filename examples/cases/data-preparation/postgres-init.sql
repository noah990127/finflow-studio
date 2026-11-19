CREATE SCHEMA IF NOT EXISTS demo_examples;

CREATE TABLE IF NOT EXISTS demo_examples.sales_region_targets (
    region varchar(40) PRIMARY KEY,
    monthly_target_cny numeric(18, 2) NOT NULL,
    target_owner varchar(80) NOT NULL
);

TRUNCATE TABLE demo_examples.sales_region_targets;

INSERT INTO demo_examples.sales_region_targets(region, monthly_target_cny, target_owner)
SELECT '区域' || series,
       2400000 + series * 120000,
       '经营管理组' || series
FROM generate_series(0, 9) AS series;

GRANT USAGE ON SCHEMA demo_examples TO finflow;
GRANT SELECT ON demo_examples.sales_region_targets TO finflow;

COMMENT ON TABLE demo_examples.sales_region_targets IS '完全模拟的区域经营目标，仅用于 FinBTP Studio 数据加工演示。';
