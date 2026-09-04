package com.mcpgateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录限速。用可控时钟而不是真的等 5 分钟 —— 那样的测试没人愿意留着。
 */
class LoginThrottleTest {

    /** 能被拨动的时钟。 */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-09-03T00:00:00Z");

        @Override
        public Instant instant() {
            return this.now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        void advance(Duration amount) {
            this.now = this.now.plus(amount);
        }
    }

    @Test
    @DisplayName("失败次数没到阈值就不锁")
    void staysOpenBelowTheThreshold() {
        LoginThrottle throttle = new LoginThrottle(new MovableClock());

        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("10.0.0.1");
        }

        assertThat(throttle.isLocked("10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("到阈值即锁定，锁定期满后自动放行")
    void locksAtThresholdAndExpires() {
        MovableClock clock = new MovableClock();
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.recordFailure("10.0.0.1");
        }
        assertThat(throttle.isLocked("10.0.0.1")).isTrue();

        clock.advance(LoginThrottle.LOCKOUT.plusSeconds(1));
        assertThat(throttle.isLocked("10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("按来源计数：一个地址被锁不牵连其他地址")
    void locksPerSource() {
        LoginThrottle throttle = new LoginThrottle(new MovableClock());

        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.recordFailure("10.0.0.1");
        }

        assertThat(throttle.isLocked("10.0.0.1")).isTrue();
        assertThat(throttle.isLocked("10.0.0.2")).isFalse();
    }

    @Test
    @DisplayName("登录成功立刻清零，偶发的几次手滑不会累积成锁定")
    void successClearsTheCounter() {
        LoginThrottle throttle = new LoginThrottle(new MovableClock());

        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.recordFailure("10.0.0.1");
        }
        throttle.recordSuccess("10.0.0.1");
        throttle.recordFailure("10.0.0.1");

        assertThat(throttle.isLocked("10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("失败之间隔得够久就不算连续，计数从头开始")
    void failuresOutsideTheWindowDoNotAccumulate() {
        MovableClock clock = new MovableClock();
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            throttle.recordFailure("10.0.0.1");
            clock.advance(LoginThrottle.LOCKOUT.plusSeconds(1));
        }

        assertThat(throttle.isLocked("10.0.0.1")).isFalse();
    }
}
