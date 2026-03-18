package com.example.popping.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class BCryptBenchmarkTest {

    private static final int SAMPLE_SIZE = 10;
    private static final String PASSWORD = "testPassword123!";

    @Test
    @DisplayName("BCrypt strength별 encode 평균 소요시간 측정")
    void bcryptEncodeBenchmark() {
        PasswordEncoder strength8 = new BCryptPasswordEncoder(8);
        PasswordEncoder strength10 = new BCryptPasswordEncoder(10);

        // 워밍업 (JIT 컴파일 유도)
        strength8.encode("warmup");
        strength10.encode("warmup");

        long avg8 = measureEncodeAvg(strength8);
        long avg10 = measureEncodeAvg(strength10);

        System.out.println("==============================");
        System.out.println(" BCrypt encode 평균 소요시간");
        System.out.println("------------------------------");
        System.out.printf(" strength=8  : %4dms%n", avg8);
        System.out.printf(" strength=10 : %4dms%n", avg10);
        System.out.printf(" 속도 차이   : %.1fx%n", (double) avg10 / avg8);
        System.out.println("==============================");
    }

    @Test
    @DisplayName("BCrypt strength별 matches 평균 소요시간 측정")
    void bcryptMatchesBenchmark() {
        PasswordEncoder strength8 = new BCryptPasswordEncoder(8);
        PasswordEncoder strength10 = new BCryptPasswordEncoder(10);

        String hash8 = strength8.encode(PASSWORD);
        String hash10 = strength10.encode(PASSWORD);

        // 워밍업
        strength8.matches("warmup", hash8);
        strength10.matches("warmup", hash10);

        long avg8 = measureMatchesAvg(strength8, hash8);
        long avg10 = measureMatchesAvg(strength10, hash10);

        System.out.println("==============================");
        System.out.println(" BCrypt matches 평균 소요시간");
        System.out.println("------------------------------");
        System.out.printf(" strength=8  : %4dms%n", avg8);
        System.out.printf(" strength=10 : %4dms%n", avg10);
        System.out.printf(" 속도 차이   : %.1fx%n", (double) avg10 / avg8);
        System.out.println("==============================");
    }

    private long measureEncodeAvg(PasswordEncoder encoder) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            encoder.encode(PASSWORD);
        }
        return (System.currentTimeMillis() - start) / SAMPLE_SIZE;
    }

    private long measureMatchesAvg(PasswordEncoder encoder, String hash) {
        long start = System.currentTimeMillis();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            encoder.matches(PASSWORD, hash);
        }
        return (System.currentTimeMillis() - start) / SAMPLE_SIZE;
    }
}
