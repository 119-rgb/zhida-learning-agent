package com.zhida.agent.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class ModelCostService {

    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");
    private static final BigDecimal FLASH_OFF_PEAK_INPUT = new BigDecimal("0.15");
    private static final BigDecimal FLASH_OFF_PEAK_OUTPUT = new BigDecimal("0.60");
    private static final BigDecimal FLASH_PEAK_INPUT = new BigDecimal("0.30");
    private static final BigDecimal FLASH_PEAK_OUTPUT = new BigDecimal("1.20");
    private static final BigDecimal PRO_OFF_PEAK_INPUT = new BigDecimal("0.66");
    private static final BigDecimal PRO_OFF_PEAK_OUTPUT = new BigDecimal("1.98");
    private static final BigDecimal PRO_PEAK_INPUT = new BigDecimal("1.32");
    private static final BigDecimal PRO_PEAK_OUTPUT = new BigDecimal("3.96");
    private static final String PRICING_VERSION = "deepseek-2026-09";
    private static final String PRICING_BASIS = "cache_miss_upper_bound";

    private final BigDecimal warningThresholdUsd;

    public ModelCostService(@Value("${zhida.cost.warning-usd:0.01}") BigDecimal warningThresholdUsd) {
        this.warningThresholdUsd = Objects.requireNonNull(warningThresholdUsd, "warningThresholdUsd");
        if (warningThresholdUsd.signum() < 0) {
            throw new IllegalArgumentException("warningThresholdUsd must not be negative");
        }
    }

    public CostEstimate estimate(List<UsageSample> usages) {
        BigDecimal total = BigDecimal.ZERO;
        int pricedCalls = 0;
        for (UsageSample usage : usages) {
            if (!hasPricingInputs(usage)) {
                continue;
            }
            boolean peak = isPeak(usage.recordedAt());
            Price price = priceFor(usage.model(), peak);
            if (price == null) {
                continue;
            }
            total = total.add(cost(usage.promptTokens(), price.input()))
                    .add(cost(usage.completionTokens(), price.output()));
            pricedCalls++;
        }
        boolean warning = warningThresholdUsd.signum() > 0
                && total.compareTo(warningThresholdUsd) >= 0;
        return new CostEstimate(
                total,
                pricedCalls == usages.size(),
                pricedCalls,
                usages.size(),
                warning,
                PRICING_VERSION,
                PRICING_BASIS
        );
    }

    private boolean hasPricingInputs(UsageSample usage) {
        return usage != null
                && usage.model() != null
                && usage.recordedAt() != null
                && usage.promptTokens() != null
                && usage.promptTokens() >= 0
                && usage.completionTokens() != null
                && usage.completionTokens() >= 0;
    }

    private Price priceFor(String model, boolean peak) {
        return switch (model.strip().toLowerCase(Locale.ROOT)) {
            case "deepseek-flash", "deepseek-v4-flash" -> peak
                    ? new Price(FLASH_PEAK_INPUT, FLASH_PEAK_OUTPUT)
                    : new Price(FLASH_OFF_PEAK_INPUT, FLASH_OFF_PEAK_OUTPUT);
            case "deepseek-v4-pro" -> peak
                    ? new Price(PRO_PEAK_INPUT, PRO_PEAK_OUTPUT)
                    : new Price(PRO_OFF_PEAK_INPUT, PRO_OFF_PEAK_OUTPUT);
            default -> null;
        };
    }

    private boolean isPeak(Instant recordedAt) {
        if (recordedAt == null) {
            return false;
        }
        var utc = recordedAt.atZone(ZoneOffset.UTC);
        DayOfWeek day = utc.getDayOfWeek();
        int hour = utc.getHour();
        boolean weekday = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
        return weekday && ((hour >= 1 && hour < 4) || (hour >= 6 && hour < 10));
    }

    private BigDecimal cost(int tokens, BigDecimal ratePerMillion) {
        return BigDecimal.valueOf(tokens).multiply(ratePerMillion).divide(TOKENS_PER_MILLION);
    }

    public record UsageSample(String model, Integer promptTokens, Integer completionTokens, Instant recordedAt) {
    }

    public record CostEstimate(
            BigDecimal estimatedMaxCostUsd,
            boolean complete,
            int pricedCalls,
            int totalCalls,
            boolean warning,
            String pricingVersion,
            String basis
    ) {
    }

    private record Price(BigDecimal input, BigDecimal output) {
    }
}
