-- 压测用账号：应用通过宿主机 3306 连接
CREATE USER IF NOT EXISTS 'rpc'@'%' IDENTIFIED BY 'rpc123';
GRANT ALL ON seckill.* TO 'rpc'@'%';
FLUSH PRIVILEGES;
