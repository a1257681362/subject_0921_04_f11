package com.evops.rental;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移失败定位验证（flyway-rental-1）：脚本执行失败时，报错必须带脚本名与出错行号，
 * 交接后新人可直接定位到脚本行。不经 Spring，直接用 Flyway API 跑一个故意写错的脚本目录
 * （src/test/resources/db/migration-broken，与正式目录 db/migration 互不影响）。
 */
class FlywayFailureLineTest {

    @Test
    void brokenScriptFailurePinpointsScriptAndLine() {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:flyway-broken;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration-broken")
                .load();
        FlywayException ex = assertThrows(FlywayException.class, flyway::migrate);
        String message = String.valueOf(ex.getMessage());
        assertTrue(message.contains("V1__broken.sql"), "报错应含脚本名：\n" + message);
        // 坏语句位于 V1__broken.sql 第 5 行
        assertTrue(Pattern.compile("Line\\s*:\\s*5").matcher(message).find(),
                "报错应含出错行号 5：\n" + message);
    }

    @Test
    void executedScriptTamperingFailsChecksumValidation() {
        String url = "jdbc:h2:mem:flyway-checksum;DB_CLOSE_DELAY=-1";
        // 1) 先用正式脚本正常迁移（V1 留档，含 checksum）
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // 2) 同一库换上同名不同内容的脚本（模拟已执行脚本被回改）：
        //    db/migration-checksum/V1__rental_baseline.sql 与正式脚本仅注释不同
        Flyway tampered = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration-checksum")
                .load();
        FlywayException ex = assertThrows(FlywayException.class, tampered::validate);
        String message = String.valueOf(ex.getMessage()).toLowerCase();
        assertTrue(message.contains("validate failed"), "应报校验失败：\n" + message);
        assertTrue(message.contains("checksum"), "应指明 checksum 不符：\n" + message);
    }
}
