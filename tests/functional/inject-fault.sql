-- 只允许在既有本机合成测试项目中临时安装，验证真实 S3 写入后的数据库失败补偿。
DELIMITER $$
CREATE TRIGGER tracequest_regression_upload BEFORE INSERT ON challenge_files FOR EACH ROW
BEGIN
 IF NEW.original_name LIKE 'TQ-FAIL.%' THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TraceQuest synthetic persistence failure';
 END IF;
END$$
DELIMITER ;
