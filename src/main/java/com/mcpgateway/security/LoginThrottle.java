package com.mcpgateway.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限速。
 *
 * 加登录的直接后果是允许把管理端绑到内网地址，也就把登录接口暴露给了一个可以持续尝试的网络。
 * BCrypt 本身约 100ms 已经是一道减速带，但单靠它，一个并发脚本一晚上仍能试出相当的量。
 *
 * 锁定期间**不比对口令**：先查锁再验，否则攻击者可以通过响应时间区分"锁定了但口令对"
 * 和"锁定了且口令不对"，限速就成了摆设。
 *
 * 状态放在内存里。单机单进程部署（需求 14），重启即清空 —— 这是可以接受的：重启需要
 * 宿主机权限，能重启的人本来就不需要爆破口令。
 */
@Component
public class LoginThrottle {

    /** 连续失败多少次后锁定。 */
    static final int MAX_FAILURES = 5;

    /** 锁定时长，同时也是失败计数的滑动窗口。 */
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    /**
     * 计数表的容量上限。
     *
     * 按来源 IP 计数意味着攻击者可以用伪造的来源撑大这张表。上限到了就整体清空 ——
     * 丢掉计数不会放行任何一次口令比对，但能保证这张表不会把堆吃光。
     */
    private static final int MAX_TRACKED_SOURCES = 10_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param source 来源标识，通常是客户端 IP
     * @return true 表示当前处于锁定期，调用方必须在**比对口令之前**就返回失败
     */
    public boolean isLocked(String source) {
        Attempts current = this.attempts.get(key(source));
        return current != null && current.isLocked(Instant.now(this.clock));
    }

    /** 登录失败时调用。 */
    public void recordFailure(String source) {
        Instant now = Instant.now(this.clock);
        if (this.attempts.size() >= MAX_TRACKED_SOURCES) {
            this.attempts.clear();
        }
        this.attempts.compute(key(source), (ignored, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                return new Attempts(1, now);
            }
            return new Attempts(existing.count() + 1, now);
        });
    }

    /** 登录成功时调用，立刻清掉该来源的计数。 */
    public void recordSuccess(String source) {
        this.attempts.remove(key(source));
    }

    private static String key(String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }

    /**
     * @param count      窗口内的连续失败次数
     * @param lastFailure 最后一次失败的时刻
     */
    private record Attempts(int count, Instant lastFailure) {

        boolean isExpired(Instant now) {
            return this.lastFailure.plus(LOCKOUT).isBefore(now);
        }

        boolean isLocked(Instant now) {
            return this.count >= MAX_FAILURES && !isExpired(now);
        }
    }
}
