CREATE SCHEMA IF NOT EXISTS demo_examples;

CREATE TABLE IF NOT EXISTS demo_examples.nvidia_device_benchmarks (
    platform varchar(80) NOT NULL,
    workload varchar(80) NOT NULL,
    throughput_index numeric(8,2) NOT NULL,
    latency_index numeric(8,2) NOT NULL,
    energy_efficiency_index numeric(8,2) NOT NULL,
    software_readiness numeric(5,2) NOT NULL,
    deployment_readiness varchar(40) NOT NULL,
    scenario_note varchar(240) NOT NULL,
    PRIMARY KEY (platform, workload)
);

TRUNCATE TABLE demo_examples.nvidia_device_benchmarks;
INSERT INTO demo_examples.nvidia_device_benchmarks VALUES
('DGX Rubin NVL8','大模型训练',100,92,96,78,'规划验证','模拟指数；需以正式基准测试替换'),
('DGX GB200','大模型训练',88,86,82,94,'可采购','模拟指数；适合近期规模化训练'),
('RTX PRO Server','企业推理',78,84,77,92,'可采购','模拟指数；适合企业机房部署'),
('RTX PRO 6000','可视化与推理',72,80,70,95,'可采购','模拟指数；适合专业工作站'),
('Jetson AGX Thor','机器人与边缘AI',64,94,91,86,'可采购','模拟指数；侧重边缘实时推理'),
('GeForce RTX 5090','本地原型开发',58,76,62,97,'可采购','模拟指数；侧重低门槛开发验证');

COMMENT ON TABLE demo_examples.nvidia_device_benchmarks IS 'FinFlow 演示数据，所有性能指数均为模拟值，不代表 NVIDIA 官方基准。';
