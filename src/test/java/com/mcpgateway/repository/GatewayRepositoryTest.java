package com.mcpgateway.repository;

import com.mcpgateway.AbstractDataTest;
import com.mcpgateway.domain.Gateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayRepositoryTest extends AbstractDataTest {

    @Autowired
    private GatewayRepository repository;

    @Test
    @DisplayName("插入后可按 id 和 slug 读回，时间戳不因时区丢精度")
    void insertsAndReadsBack() {
        Gateway gateway = TestFixtures.gateway("alpha-" + System.nanoTime());

        this.repository.insert(gateway);

        Optional<Gateway> byId = this.repository.findById(gateway.id());
        assertThat(byId).isPresent();
        assertThat(byId.get().name()).isEqualTo(gateway.name());
        assertThat(byId.get().description()).isEqualTo(gateway.description());
        assertThat(byId.get().createdAt()).isEqualTo(gateway.createdAt());

        assertThat(this.repository.findBySlug(gateway.slug())).isPresent();
    }

    @Test
    @DisplayName("需求 6.1.2：slug 全局唯一，重复插入被数据库拒绝")
    void enforcesUniqueSlug() {
        String slug = "dup-" + System.nanoTime();
        this.repository.insert(TestFixtures.gateway(slug));

        assertThatThrownBy(() -> this.repository.insert(TestFixtures.gateway(slug)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("更新只改可编辑字段，不动令牌哈希")
    void updateLeavesTokenHashUntouched() {
        Gateway gateway = TestFixtures.gateway("upd-" + System.nanoTime());
        this.repository.insert(gateway);
        Instant later = Instant.now().truncatedTo(ChronoUnit.MICROS);

        int rows = this.repository.update(gateway.id(), "新名字", gateway.slug(), "新描述", later);

        assertThat(rows).isEqualTo(1);
        Gateway updated = this.repository.findById(gateway.id()).orElseThrow();
        assertThat(updated.name()).isEqualTo("新名字");
        assertThat(updated.description()).isEqualTo("新描述");
        assertThat(updated.accessTokenHash()).isEqualTo(gateway.accessTokenHash());
        assertThat(updated.updatedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("需求 FR-05.3：令牌轮换覆盖旧哈希")
    void rotatesAccessTokenHash() {
        Gateway gateway = TestFixtures.gateway("rot-" + System.nanoTime());
        this.repository.insert(gateway);

        this.repository.updateAccessTokenHash(gateway.id(), "new-hash", Instant.now().truncatedTo(ChronoUnit.MICROS));

        assertThat(this.repository.findById(gateway.id()).orElseThrow().accessTokenHash()).isEqualTo("new-hash");
    }

    @Test
    @DisplayName("slug 占用检查区分自身与他人")
    void checksSlugOwnership() {
        Gateway gateway = TestFixtures.gateway("own-" + System.nanoTime());
        this.repository.insert(gateway);

        assertThat(this.repository.existsBySlug(gateway.slug())).isTrue();
        // 自己占着自己的 slug 不算冲突，否则改名之外的更新都会被误判。
        assertThat(this.repository.existsBySlugAndIdNot(gateway.slug(), gateway.id())).isFalse();
        assertThat(this.repository.existsBySlugAndIdNot(gateway.slug(), TestFixtures.id())).isTrue();
    }

    @Test
    @DisplayName("删除不存在的网关返回 0，不抛异常")
    void deleteIsIdempotent() {
        assertThat(this.repository.deleteById(TestFixtures.id())).isZero();
    }
}
