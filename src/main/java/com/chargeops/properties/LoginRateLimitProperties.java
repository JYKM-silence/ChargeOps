package com.chargeops.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 登录限流参数。窗口按首次请求固定，不会被后续请求不断延长。 */
@Data
@Component
@ConfigurationProperties(prefix = "chargeops.login-rate-limit")
public class LoginRateLimitProperties {

    private boolean enabled = true;
    private int ipMaxAttempts = 30;
    private Duration ipWindow = Duration.ofMinutes(10);
    private int accountMaxFailures = 5;
    private Duration accountWindow = Duration.ofMinutes(15);
}
