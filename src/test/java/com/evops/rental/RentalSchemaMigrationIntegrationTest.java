package com.evops.rental;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租车域表结构版本管理（flyway-rental-1）集成测试：内存 H2 全量启动。
 * 覆盖：基线建表与存量结构一致、变更记录留档、升级可重跑、
 * 已执行脚本 checksum 校验生效、旧骨架版本表完成移交。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:rental-schema-migration;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
class RentalSchemaMigrationIntegrationTest {

    /** 移交 Flyway 的租车域 8 表。 */
    private static final String[] RENTAL_TABLES = {
            "T_VEHICLE", "T_RENTAL_ORDER", "T_RENTAL_HANDOVER", "T_RENTAL_ORDER_EVENT",
            "T_RENTAL_ORDER_TRANSITION", "T_RENTAL_ORDER_NAME_INDEX",
            "T_IDEMPOTENT_REQUEST", "T_HANDOVER_PHOTO"
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Flyway flyway;

    @Test
    void baselineCreatesRentalTablesConsistentWithExistingSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'",
                String.class);
        for (String table : RENTAL_TABLES) {
            assertTrue(tables.contains(table), "缺少租车域表 " + table);
        }
        // 关键演进结果与存量结构一致：归属网点列已收编进基线
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'T_RENTAL_ORDER' AND COLUMN_NAME = 'BRANCH_CODE'", Integer.class));
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'T_VEHICLE' AND COLUMN_NAME = 'BRANCH_CODE'", Integer.class));
        // 状态列已扩容到 32 且不再以 CHECK 枚举锁死（状态机配置驱动）
        assertEquals(Integer.valueOf(32), jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'T_RENTAL_ORDER' AND COLUMN_NAME = 'STATUS'", Integer.class));
        assertEquals(Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME = 'CK_RENTAL_ORDER_STATUS'", Integer.class));
        // 订单号唯一约束仍在（重复提交库层兜底）
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME = 'UK_RENTAL_ORDER_NO'", Integer.class));
    }

    @Test
    void migrationHistoryArchived() {
        // 变更记录留档：V1 基线逐条留痕（版本/脚本/类型/校验和/执行账号/耗时/成败）。
        // 注：Flyway 以引号小写建留档表，H2 大小写敏感，查询需带引号。
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" "
                        + "WHERE \"version\" = '1' AND \"script\" = 'V1__rental_baseline.sql' "
                        + "AND \"type\" = 'SQL' AND \"success\"",
                Integer.class));
        assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1' "
                        + "AND \"checksum\" IS NOT NULL AND \"installed_by\" IS NOT NULL "
                        + "AND \"installed_on\" IS NOT NULL AND \"execution_time\" >= 0",
                Integer.class));
    }

    @Test
    void upgradeIsReRunnableAndValidated() {
        // 升级可重跑：重复执行迁移为完全空转
        MigrateResult result = flyway.migrate();
        assertEquals(0, result.migrationsExecuted);
        // 已执行脚本不许改：validate-on-migrate 校验 checksum，当前应全部通过
        assertDoesNotThrow(() -> flyway.validate());
    }

    @Test
    void legacyVersionTableHandover() {
        // 旧骨架版本表不再登记租车域（留档以 flyway_schema_history 为准）
        assertEquals(Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM T_EVOPS_SCHEMA_VERSION WHERE VERSION_NO IN "
                        + "('rental-1', 'rental-name-search-1', 'rental-fsm-1', 'photo-1')",
                Integer.class));
        // 其余域照旧登记，两轨互不重叠
        assertEquals(Integer.valueOf(3), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM T_EVOPS_SCHEMA_VERSION WHERE VERSION_NO IN ('base-0', 'branch-1', 'recon-1')",
                Integer.class));
    }
}
