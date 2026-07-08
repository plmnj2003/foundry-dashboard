CREATE TABLE IF NOT EXISTS customers (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    country VARCHAR(50),
    tier VARCHAR(20) CHECK (tier IN ('PLATINUM','GOLD','SILVER','BRONZE'))
);

CREATE TABLE IF NOT EXISTS products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    technology_node VARCHAR(20),
    wafer_size INTEGER,
    unit_price NUMERIC(12,2)
);

CREATE TABLE IF NOT EXISTS sales_orders (
    id SERIAL PRIMARY KEY,
    customer_id INTEGER REFERENCES customers(id),
    product_id INTEGER REFERENCES products(id),
    quantity INTEGER,
    unit_price NUMERIC(12,2),
    total_amount NUMERIC(14,2),
    order_date DATE,
    status VARCHAR(20) CHECK (status IN ('PENDING','IN_PRODUCTION','SHIPPED','DELIVERED','CANCELLED'))
);

CREATE TABLE IF NOT EXISTS production_lots (
    id SERIAL PRIMARY KEY,
    product_id INTEGER REFERENCES products(id),
    lot_number VARCHAR(50) UNIQUE,
    quantity INTEGER,
    start_date DATE,
    end_date DATE,
    status VARCHAR(20) CHECK (status IN ('QUEUED','IN_PROGRESS','COMPLETED','SCRAPPED')),
    yield_rate NUMERIC(5,2)
);

CREATE TABLE IF NOT EXISTS defect_records (
    id SERIAL PRIMARY KEY,
    lot_id INTEGER REFERENCES production_lots(id),
    defect_type VARCHAR(50),
    process_step VARCHAR(50),
    count INTEGER,
    severity VARCHAR(20) CHECK (severity IN ('CRITICAL','MAJOR','MINOR')),
    detected_at TIMESTAMP DEFAULT NOW()
);

-- Seed customers (ON CONFLICT DO NOTHING: 재시작해도 중복 없음)
INSERT INTO customers (name, country, tier) VALUES
('Samsung Semiconductor','South Korea','PLATINUM'),
('TSMC','Taiwan','PLATINUM'),
('Intel Foundry','USA','GOLD'),
('Qualcomm','USA','GOLD'),
('MediaTek','Taiwan','SILVER'),
('NXP Semiconductors','Netherlands','SILVER'),
('Infineon Technologies','Germany','BRONZE'),
('Renesas Electronics','Japan','BRONZE')
ON CONFLICT DO NOTHING;

INSERT INTO products (name, technology_node, wafer_size, unit_price) VALUES
('Logic 5nm','5nm',300,4500.00),
('Logic 7nm','7nm',300,3200.00),
('Logic 12nm','12nm',200,1800.00),
('DRAM 1z','1z-nm',300,2100.00),
('Power IC 180nm','180nm',200,450.00),
('RF Front-End 28nm','28nm',200,980.00),
('Mixed Signal 65nm','65nm',200,650.00),
('CMOS Image Sensor 90nm','90nm',300,1200.00)
ON CONFLICT DO NOTHING;

INSERT INTO sales_orders (customer_id, product_id, quantity, unit_price, total_amount, order_date, status) VALUES
(1,1,500,4500.00,2250000.00,'2024-01-05','DELIVERED'),
(2,1,1000,4500.00,4500000.00,'2024-01-10','DELIVERED'),
(3,2,300,3200.00,960000.00,'2024-01-15','SHIPPED'),
(4,6,800,980.00,784000.00,'2024-01-20','IN_PRODUCTION'),
(5,3,1200,1800.00,2160000.00,'2024-02-01','DELIVERED'),
(6,5,600,450.00,270000.00,'2024-02-05','SHIPPED'),
(7,5,400,450.00,180000.00,'2024-02-10','DELIVERED'),
(8,8,200,1200.00,240000.00,'2024-02-15','IN_PRODUCTION'),
(1,4,700,2100.00,1470000.00,'2024-03-01','IN_PRODUCTION'),
(2,2,500,3200.00,1600000.00,'2024-03-05','PENDING'),
(3,7,300,650.00,195000.00,'2024-03-10','PENDING'),
(4,3,900,1800.00,1620000.00,'2024-03-15','DELIVERED'),
(5,6,400,980.00,392000.00,'2024-04-01','SHIPPED'),
(6,7,250,650.00,162500.00,'2024-04-05','DELIVERED'),
(7,8,150,1200.00,180000.00,'2024-04-10','CANCELLED'),
(8,1,100,4500.00,450000.00,'2024-04-15','PENDING'),
(1,2,800,3200.00,2560000.00,'2024-05-01','DELIVERED'),
(2,4,600,2100.00,1260000.00,'2024-05-10','SHIPPED'),
(3,5,1000,450.00,450000.00,'2024-05-15','DELIVERED'),
(4,1,200,4500.00,900000.00,'2024-06-01','IN_PRODUCTION')
ON CONFLICT DO NOTHING;

INSERT INTO production_lots (product_id, lot_number, quantity, start_date, end_date, status, yield_rate) VALUES
(1,'LOT-5NM-001',500,'2024-01-06','2024-01-26','COMPLETED',94.2),
(1,'LOT-5NM-002',1000,'2024-01-11','2024-02-10','COMPLETED',96.8),
(2,'LOT-7NM-001',300,'2024-01-16','2024-01-31','COMPLETED',97.5),
(6,'LOT-RF-001',800,'2024-01-21','2024-02-05','COMPLETED',91.3),
(3,'LOT-12NM-001',1200,'2024-02-02','2024-02-16','COMPLETED',98.1),
(5,'LOT-PWR-001',600,'2024-02-06','2024-02-16','COMPLETED',99.2),
(5,'LOT-PWR-002',400,'2024-02-11','2024-02-21','COMPLETED',98.7),
(8,'LOT-CIS-001',200,'2024-02-16','2024-03-07','IN_PROGRESS',NULL),
(4,'LOT-DRAM-001',700,'2024-03-02','2024-03-22','IN_PROGRESS',NULL),
(2,'LOT-7NM-002',500,'2024-03-06','2024-03-26','QUEUED',NULL),
(7,'LOT-MS-001',300,'2024-03-11','2024-03-21','COMPLETED',95.3),
(3,'LOT-12NM-002',900,'2024-03-16','2024-03-30','COMPLETED',97.9),
(6,'LOT-RF-002',400,'2024-04-02','2024-04-17','COMPLETED',92.6),
(7,'LOT-MS-002',250,'2024-04-06','2024-04-16','COMPLETED',96.4),
(8,'LOT-CIS-002',150,'2024-04-11','2024-05-01','SCRAPPED',45.0),
(1,'LOT-5NM-003',100,'2024-04-16','2024-05-06','QUEUED',NULL),
(2,'LOT-7NM-003',800,'2024-05-02','2024-05-22','COMPLETED',95.7),
(4,'LOT-DRAM-002',600,'2024-05-11','2024-05-31','IN_PROGRESS',NULL),
(5,'LOT-PWR-003',1000,'2024-05-16','2024-05-26','COMPLETED',99.4),
(1,'LOT-5NM-004',200,'2024-06-02','2024-06-22','IN_PROGRESS',NULL)
ON CONFLICT (lot_number) DO NOTHING;

INSERT INTO defect_records (lot_id, defect_type, process_step, count, severity, detected_at) VALUES
(1,'Particle Contamination','Lithography',12,'MAJOR','2024-01-20 09:00:00'),
(1,'CD Variation','Etch',5,'MINOR','2024-01-22 14:00:00'),
(2,'Overlay Error','Lithography',8,'MAJOR','2024-02-01 10:00:00'),
(4,'Surface Roughness','CMP',20,'MINOR','2024-01-30 11:00:00'),
(4,'Metal Short','Metallization',3,'CRITICAL','2024-02-03 16:00:00'),
(5,'Doping Variation','Ion Implant',6,'MINOR','2024-02-10 09:00:00'),
(7,'Oxide Pinhole','Oxidation',2,'CRITICAL','2024-02-18 13:00:00'),
(11,'Threshold Voltage Shift','Ion Implant',9,'MAJOR','2024-03-18 10:00:00'),
(12,'Particle Contamination','Lithography',4,'MINOR','2024-03-25 15:00:00'),
(13,'CD Variation','Etch',7,'MINOR','2024-04-10 11:00:00'),
(15,'Multiple Defects','Various',85,'CRITICAL','2024-04-25 08:00:00'),
(15,'Particle Contamination','Clean',45,'CRITICAL','2024-04-26 09:00:00'),
(17,'Overlay Error','Lithography',11,'MAJOR','2024-05-15 14:00:00'),
(17,'Surface Roughness','CMP',8,'MINOR','2024-05-18 10:00:00'),
(19,'CD Variation','Etch',3,'MINOR','2024-05-22 11:00:00')
ON CONFLICT DO NOTHING;
