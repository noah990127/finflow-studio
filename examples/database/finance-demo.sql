CREATE SCHEMA IF NOT EXISTS finance_demo;

CREATE TABLE IF NOT EXISTS finance_demo.business_units (
    unit_code varchar(20) PRIMARY KEY,
    unit_name varchar(80) NOT NULL,
    region varchar(40) NOT NULL,
    manager varchar(40) NOT NULL,
    annual_revenue_target numeric(18,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS finance_demo.monthly_operations (
    period varchar(7) NOT NULL,
    unit_code varchar(20) NOT NULL REFERENCES finance_demo.business_units(unit_code),
    revenue numeric(18,2) NOT NULL,
    operating_cost numeric(18,2) NOT NULL,
    gross_profit numeric(18,2) NOT NULL,
    order_count integer NOT NULL,
    active_customers integer NOT NULL,
    headcount integer NOT NULL,
    PRIMARY KEY (period, unit_code)
);

CREATE TABLE IF NOT EXISTS finance_demo.budget_actuals (
    fiscal_quarter varchar(7) NOT NULL,
    unit_code varchar(20) NOT NULL REFERENCES finance_demo.business_units(unit_code),
    budget_revenue numeric(18,2) NOT NULL,
    actual_revenue numeric(18,2) NOT NULL,
    budget_profit numeric(18,2) NOT NULL,
    actual_profit numeric(18,2) NOT NULL,
    forecast_revenue numeric(18,2) NOT NULL,
    variance_reason varchar(160) NOT NULL,
    PRIMARY KEY (fiscal_quarter, unit_code)
);

CREATE TABLE IF NOT EXISTS finance_demo.inventory_snapshot (
    snapshot_date date NOT NULL,
    sku varchar(30) NOT NULL,
    product_name varchar(100) NOT NULL,
    category varchar(50) NOT NULL,
    warehouse varchar(50) NOT NULL,
    quantity integer NOT NULL,
    unit_cost numeric(14,2) NOT NULL,
    inventory_value numeric(18,2) NOT NULL,
    aging_days integer NOT NULL,
    risk_level varchar(20) NOT NULL,
    PRIMARY KEY (snapshot_date, sku)
);

CREATE TABLE IF NOT EXISTS finance_demo.accounts_receivable (
    invoice_no varchar(30) PRIMARY KEY,
    customer_name varchar(100) NOT NULL,
    unit_code varchar(20) NOT NULL REFERENCES finance_demo.business_units(unit_code),
    invoice_date date NOT NULL,
    due_date date NOT NULL,
    invoice_amount numeric(18,2) NOT NULL,
    outstanding_amount numeric(18,2) NOT NULL,
    overdue_days integer NOT NULL,
    credit_rating varchar(10) NOT NULL,
    collection_status varchar(30) NOT NULL
);

CREATE TABLE IF NOT EXISTS finance_demo.cash_flow_forecast (
    period varchar(7) PRIMARY KEY,
    opening_balance numeric(18,2) NOT NULL,
    customer_receipts numeric(18,2) NOT NULL,
    supplier_payments numeric(18,2) NOT NULL,
    payroll_and_tax numeric(18,2) NOT NULL,
    capital_expenditure numeric(18,2) NOT NULL,
    financing_cash_flow numeric(18,2) NOT NULL,
    closing_balance numeric(18,2) NOT NULL,
    scenario varchar(30) NOT NULL
);

INSERT INTO finance_demo.business_units VALUES
    ('BU-NORTH', '北区工业客户事业部', '华北', '陈晨', 52000.00),
    ('BU-EAST', '东区数字化事业部', '华东', '林岚', 68000.00),
    ('BU-SOUTH', '南区新能源事业部', '华南', '周舟', 61000.00),
    ('BU-OVERSEAS', '海外解决方案事业部', '海外', '顾远', 45000.00)
ON CONFLICT DO NOTHING;

WITH months AS (
    SELECT generate_series(date '2026-01-01', date '2026-12-01', interval '1 month')::date AS month_start
), unit_parameters AS (
    SELECT * FROM (VALUES
        ('BU-NORTH', 3900.00::numeric, 0.73::numeric, 118, 76, 142),
        ('BU-EAST', 5200.00::numeric, 0.69::numeric, 146, 91, 168),
        ('BU-SOUTH', 4650.00::numeric, 0.71::numeric, 132, 83, 151),
        ('BU-OVERSEAS', 3250.00::numeric, 0.76::numeric, 82, 54, 109)
    ) AS p(unit_code, base_revenue, cost_ratio, base_orders, base_customers, base_headcount)
)
INSERT INTO finance_demo.monthly_operations
SELECT
    to_char(m.month_start, 'YYYY-MM'),
    p.unit_code,
    round((p.base_revenue * (1 + (extract(month FROM m.month_start) - 1) * 0.018)
        * CASE extract(month FROM m.month_start)::int WHEN 2 THEN 0.82 WHEN 6 THEN 1.12 WHEN 9 THEN 1.16 WHEN 12 THEN 1.28 ELSE 1 END)::numeric, 2) AS revenue,
    round((p.base_revenue * (1 + (extract(month FROM m.month_start) - 1) * 0.018)
        * CASE extract(month FROM m.month_start)::int WHEN 2 THEN 0.82 WHEN 6 THEN 1.12 WHEN 9 THEN 1.16 WHEN 12 THEN 1.28 ELSE 1 END
        * p.cost_ratio)::numeric, 2) AS operating_cost,
    round((p.base_revenue * (1 + (extract(month FROM m.month_start) - 1) * 0.018)
        * CASE extract(month FROM m.month_start)::int WHEN 2 THEN 0.82 WHEN 6 THEN 1.12 WHEN 9 THEN 1.16 WHEN 12 THEN 1.28 ELSE 1 END
        * (1 - p.cost_ratio))::numeric, 2) AS gross_profit,
    p.base_orders + extract(month FROM m.month_start)::int * 4,
    p.base_customers + extract(month FROM m.month_start)::int * 2,
    p.base_headcount + floor(extract(month FROM m.month_start) / 4)::int
FROM months m CROSS JOIN unit_parameters p
ON CONFLICT DO NOTHING;

WITH quarters AS (
    SELECT * FROM (VALUES ('2026-Q1', 0.96::numeric, '春节交付节奏影响'),
        ('2026-Q2', 1.03::numeric, '重点客户项目提前验收'),
        ('2026-Q3', 1.08::numeric, '新能源行业订单增长'),
        ('2026-Q4', 1.01::numeric, '海外交付周期有所延长'))
        AS q(fiscal_quarter, actual_factor, variance_reason)
)
INSERT INTO finance_demo.budget_actuals
SELECT q.fiscal_quarter, b.unit_code,
    round(b.annual_revenue_target / 4, 2),
    round(b.annual_revenue_target / 4 * q.actual_factor, 2),
    round(b.annual_revenue_target / 4 * 0.28, 2),
    round(b.annual_revenue_target / 4 * q.actual_factor * 0.27, 2),
    round(b.annual_revenue_target / 4 * (q.actual_factor + 0.02), 2),
    q.variance_reason
FROM quarters q CROSS JOIN finance_demo.business_units b
ON CONFLICT DO NOTHING;

INSERT INTO finance_demo.inventory_snapshot
SELECT date '2026-08-31', 'SKU-' || lpad(i::text, 4, '0'),
    (ARRAY['工业控制器','高压连接器','边缘计算模组','储能变流器','视觉检测组件'])[(i - 1) % 5 + 1] || ' ' || chr(64 + i),
    (ARRAY['自动化设备','电子组件','计算平台','新能源设备','质量检测'])[(i - 1) % 5 + 1],
    (ARRAY['上海一仓','苏州中心仓','深圳保税仓','天津备件仓'])[(i - 1) % 4 + 1],
    80 + i * 37, 120.00 + i * 48.50,
    round(((80 + i * 37) * (120.00 + i * 48.50))::numeric, 2),
    (ARRAY[18,35,62,95,128,181,247,366])[(i - 1) % 8 + 1],
    CASE WHEN i % 8 = 0 THEN '高' WHEN i % 6 = 0 THEN '中' ELSE '低' END
FROM generate_series(1, 24) AS i
ON CONFLICT DO NOTHING;

INSERT INTO finance_demo.accounts_receivable
SELECT 'INV-2026-' || lpad(i::text, 4, '0'),
    (ARRAY['华星装备集团','远海能源科技','东浦汽车电子','新辰数据中心','博源机器人','恒峰电气'])[(i - 1) % 6 + 1],
    (ARRAY['BU-NORTH','BU-EAST','BU-SOUTH','BU-OVERSEAS'])[(i - 1) % 4 + 1],
    date '2026-05-01' + i * 4,
    date '2026-05-31' + i * 4,
    180.00 + i * 43.50,
    round(((180.00 + i * 43.50) * CASE WHEN i % 5 = 0 THEN 0.25 ELSE 0.78 END)::numeric, 2),
    CASE WHEN i <= 8 THEN 0 WHEN i <= 15 THEN 18 + i ELSE 45 + i * 2 END,
    (ARRAY['A','A','B','A','B','C'])[(i - 1) % 6 + 1],
    CASE WHEN i <= 8 THEN '账期内' WHEN i % 5 = 0 THEN '已承诺付款' ELSE '跟进中' END
FROM generate_series(1, 24) AS i
ON CONFLICT DO NOTHING;

WITH cash_months AS (
    SELECT generate_series(1, 12) AS month_no
)
INSERT INTO finance_demo.cash_flow_forecast
SELECT '2026-' || lpad(month_no::text, 2, '0'),
    8600.00 + (month_no - 1) * 310.00,
    12800.00 + month_no * 420.00,
    7200.00 + month_no * 260.00,
    3100.00 + month_no * 45.00,
    CASE WHEN month_no IN (3, 7, 10) THEN 2600.00 ELSE 850.00 END,
    CASE WHEN month_no = 7 THEN 3000.00 WHEN month_no = 12 THEN -1200.00 ELSE 0.00 END,
    8600.00 + (month_no - 1) * 310.00 + 12800.00 + month_no * 420.00
        - 7200.00 - month_no * 260.00 - 3100.00 - month_no * 45.00
        - CASE WHEN month_no IN (3, 7, 10) THEN 2600.00 ELSE 850.00 END
        + CASE WHEN month_no = 7 THEN 3000.00 WHEN month_no = 12 THEN -1200.00 ELSE 0.00 END,
    CASE WHEN month_no <= 6 THEN '基准情景' ELSE '滚动预测' END
FROM cash_months
ON CONFLICT DO NOTHING;

CREATE OR REPLACE VIEW finance_demo.unit_performance_summary AS
SELECT o.unit_code, b.unit_name, b.region,
    round(sum(o.revenue), 2) AS year_revenue,
    round(sum(o.gross_profit), 2) AS year_gross_profit,
    round(sum(o.gross_profit) / nullif(sum(o.revenue), 0) * 100, 2) AS gross_margin_pct,
    round(sum(o.revenue) / b.annual_revenue_target * 100, 2) AS target_achievement_pct,
    sum(o.order_count) AS order_count,
    max(o.active_customers) AS active_customers
FROM finance_demo.monthly_operations o
JOIN finance_demo.business_units b ON b.unit_code = o.unit_code
GROUP BY o.unit_code, b.unit_name, b.region, b.annual_revenue_target;

COMMENT ON SCHEMA finance_demo IS '可用于经营分析、预算复盘和资金管理的关联示例数据';
COMMENT ON TABLE finance_demo.business_units IS '业务单元和年度经营目标';
COMMENT ON TABLE finance_demo.monthly_operations IS '各业务单元月度收入、成本、订单和人员数据';
COMMENT ON TABLE finance_demo.budget_actuals IS '季度预算、实际和滚动预测对比';
COMMENT ON TABLE finance_demo.inventory_snapshot IS '期末库存数量、金额、账龄和风险等级';
COMMENT ON TABLE finance_demo.accounts_receivable IS '客户应收余额、逾期情况和催收状态';
COMMENT ON TABLE finance_demo.cash_flow_forecast IS '未来十二个月现金流滚动预测';
COMMENT ON VIEW finance_demo.unit_performance_summary IS '按业务单元汇总的管理层经营指标';

GRANT USAGE ON SCHEMA finance_demo TO finflow;
GRANT SELECT ON ALL TABLES IN SCHEMA finance_demo TO finflow;
