package com.qaqxianyuqaq.backend.service.challenge.impl;
import com.qaqxianyuqaq.backend.config.TraceQuestProperties;
import com.qaqxianyuqaq.backend.repository.challenge.StorageCleanupMapper;
import com.qaqxianyuqaq.backend.util.MinioUtil;
import io.minio.MinioClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.scheduling.annotation.*;
/** 持久化精确清理任务，允许故障恢复后重试；不触碰仍有元数据引用的对象。 */
@Service @EnableScheduling
public class StorageCleanupService {
    private final StorageCleanupMapper mapper;
    private final MinioClient client;
    private final TraceQuestProperties properties;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StorageCleanupService.class);
    public StorageCleanupService(StorageCleanupMapper mapper,MinioClient client,TraceQuestProperties properties) {
        this.mapper=mapper;this.client=client;this.properties=properties;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void enqueueRollback(String key) {
        // 1. 业务事务已回滚，用独立事务保留待清理键以便恢复后重试。
        mapper.enqueue(key);
    }
    public void enqueueReplacement(String key) {
        // 1. 旧封面清理意图随新封面的数据库引用一起提交，回滚时不删除旧文件。
        mapper.enqueue(key);
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void retry() {
        // 1. 读取有限批次，引用检查防止错误清理仍在使用的对象。
        for(String key:mapper.pending()) {
            if(mapper.references(key)>0) { mapper.complete(key); continue; }
            try {
                // 2. 对象删除幂等，删除成功才移除清理任务；失败保留下一轮重试。
                MinioUtil.removeObject(client,properties.getMinio().getBucket(),key);
                mapper.complete(key);
            } catch(Exception error) { log.warn("对象清理待重试，key={}",key); }
        }
    }
}
