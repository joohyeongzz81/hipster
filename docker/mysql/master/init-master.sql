-- 복제 전용 계정 생성 및 권한 부여
CREATE USER IF NOT EXISTS 'replicator'@'%' IDENTIFIED BY 'replicator123';
GRANT REPLICATION SLAVE ON *.* TO 'replicator'@'%';
FLUSH PRIVILEGES;
