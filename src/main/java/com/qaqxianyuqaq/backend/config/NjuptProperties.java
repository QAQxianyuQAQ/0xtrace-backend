package com.qaqxianyuqaq.backend.config;

import lombok.Getter;
import lombok.Setter;

/** 南邮认证参数；目标域名固定，禁止通过配置将学校密码转发至其他地址。 */
@Getter
@Setter
public class NjuptProperties {
    private boolean enabled = false;
    private String checkKey = "1629428467008";
    private String appId = "common";
}
