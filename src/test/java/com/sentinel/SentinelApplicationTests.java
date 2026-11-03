package com.sentinel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Guards the skeleton: the application context must start with no external services or keys. */
@SpringBootTest
class SentinelApplicationTests {

    @Test
    void contextLoads() {
    }
}
