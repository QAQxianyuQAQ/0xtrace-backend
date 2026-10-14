package com.qaqxianyuqaq.backend.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.qaqxianyuqaq.backend.util.IdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 配置：注册分页插件，统一由分页拦截器改写 SQL。 */
@Configuration
public class MybatisPlusConfig {

    /** 分页插件使用 MySQL 方言（测试环境以 H2 的 MySQL 兼容模式运行）。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 自定义主键生成器。
     *
     * <p>业务主键统一使用带前缀的随机 ID（service 层显式赋值），此处仅覆盖框架默认实现，
     * 避免启动时初始化雪花算法所需的网卡地址。</p>
     */
    @Bean
    public IdentifierGenerator identifierGenerator() {
        // 框架要求返回 Number，这里用 36 进制随机串转换为长整型，仅作为兜底
        return entity -> Long.parseLong(IdGenerator.randomToken(12), 36);
    }
}
