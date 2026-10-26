package com.orderflow.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class AuthServiceApplicationTests {
    @Value("${jwt.secret}") String signingSecret;
    @Test
    void contextLoadsWithoutLoggingSigningSecrets(CapturedOutput output) {
        assertThat(output.getAll()).contains("Started AuthServiceApplicationTests");
        assertThat(output.getAll().contains(signingSecret))
                .withFailMessage("Auth startup must not log the JWT signing secret").isFalse();
    }
}
