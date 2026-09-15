package com.zhida.agent.observability;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCostServiceTest {

    @Test
    void estimatesOffPeakFlashCostUsingCacheMissUpperBound() {
        var service = new ModelCostService(new BigDecimal("1.00"));

        var estimate = service.estimate(List.of(new ModelCostService.UsageSample(
                "deepseek-flash",
                1_000_000,
                1_000_000,
                Instant.parse("2026-09-13T12:00:00Z")
        )));

        assertThat(estimate.estimatedMaxCostUsd()).isEqualByComparingTo("0.75");
        assertThat(estimate.complete()).isTrue();
        assertThat(estimate.pricedCalls()).isEqualTo(1);
    }

    @Test
    void appliesWeekdayPeakRatesAndWarnsAtTheConfiguredThreshold() {
        var service = new ModelCostService(new BigDecimal("1.50"));

        var estimate = service.estimate(List.of(new ModelCostService.UsageSample(
                "deepseek-flash",
                1_000_000,
                1_000_000,
                Instant.parse("2026-09-14T01:30:00Z")
        )));

        assertThat(estimate.estimatedMaxCostUsd()).isEqualByComparingTo("1.50");
        assertThat(estimate.warning()).isTrue();
    }

    @Test
    void supportsCurrentProAndLegacyFlashPricing() {
        var service = new ModelCostService(new BigDecimal("10.00"));
        Instant offPeak = Instant.parse("2026-09-13T12:00:00Z");

        var pro = service.estimate(List.of(new ModelCostService.UsageSample(
                "deepseek-v4-pro", 1_000_000, 1_000_000, offPeak
        )));
        var legacyFlash = service.estimate(List.of(new ModelCostService.UsageSample(
                "deepseek-v4-flash", 1_000_000, 1_000_000, offPeak
        )));

        assertThat(pro.estimatedMaxCostUsd()).isEqualByComparingTo("2.64");
        assertThat(legacyFlash.estimatedMaxCostUsd()).isEqualByComparingTo("0.75");
    }

    @Test
    void marksEstimateIncompleteWhenPricingInputsAreUnknown() {
        var service = new ModelCostService(new BigDecimal("10.00"));

        var estimate = service.estimate(List.of(
                new ModelCostService.UsageSample(
                        "deepseek-flash", 1_000_000, 0,
                        Instant.parse("2026-09-13T12:00:00Z")
                ),
                new ModelCostService.UsageSample("deepseek-chat", 1_000_000, 1_000_000, null),
                new ModelCostService.UsageSample("deepseek-flash", 1_000_000, 1_000_000, null)
        ));

        assertThat(estimate.estimatedMaxCostUsd()).isEqualByComparingTo("0.15");
        assertThat(estimate.complete()).isFalse();
        assertThat(estimate.pricedCalls()).isEqualTo(1);
        assertThat(estimate.totalCalls()).isEqualTo(3);
        assertThat(estimate.pricingVersion()).isEqualTo("deepseek-2026-09");
        assertThat(estimate.basis()).isEqualTo("cache_miss_upper_bound");
    }
}
