package com.qaqxianyuqaq.backend.repository.challenge;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 只记录本次写入回滚或封面替换产生的精确对象键，不扫描或清空桶。 */
@Mapper
public interface StorageCleanupMapper {
    @Insert("INSERT INTO storage_cleanup_jobs(storage_key) VALUES(#{key}) ON DUPLICATE KEY UPDATE storage_key=storage_key")
    void enqueue(String key);
    @Select("SELECT storage_key FROM storage_cleanup_jobs ORDER BY created_at LIMIT 100")
    List<String> pending();
    @Select("SELECT COUNT(*) FROM challenge_files WHERE storage_key=#{key}")
    int references(String key);
    @Delete("DELETE FROM storage_cleanup_jobs WHERE storage_key=#{key}")
    void complete(String key);
}
