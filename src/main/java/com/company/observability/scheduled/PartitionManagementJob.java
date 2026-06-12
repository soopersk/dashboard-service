package com.company.observability.scheduled;

import com.company.observability.util.MdcContextUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.company.observability.util.ObservabilityConstants.*;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.partitions.management.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PartitionManagementJob {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    private final AtomicLong totalRowsGauge = new AtomicLong(0L);
    private final AtomicLong dailyRowsGauge = new AtomicLong(0L);
    private final AtomicLong monthlyRowsGauge = new AtomicLong(0L);
    private final AtomicLong partitionCountGauge = new AtomicLong(0L);

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge(PARTITION_ROWS_TOTAL, totalRowsGauge);
        meterRegistry.gauge(PARTITION_ROWS_DAILY, dailyRowsGauge);
        meterRegistry.gauge(PARTITION_ROWS_MONTHLY, monthlyRowsGauge);
        meterRegistry.gauge(PARTITION_COUNT, partitionCountGauge);
    }

    @Scheduled(cron = "${observability.partitions.monitoring.cron:0 0 6 * * *}")
    public void monitorPartitionHealth() {
        Map<String, String> snapshot = MdcContextUtil.setJobContext("partition-monitor");

        try {
            log.info("event=partition.monitor outcome=success phase=initiated");

            List<Map<String, Object>> stats = jdbcTemplate.queryForList(
                    "SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 30",
                    EmptySqlParameterSource.INSTANCE
            );

            long totalRows = 0;
            long dailyRows = 0;
            long monthlyRows = 0;

            for (Map<String, Object> stat : stats) {
                Long rowCount = ((Number) stat.get("row_count")).longValue();
                Long daily = ((Number) stat.get("daily_runs")).longValue();
                Long monthly = ((Number) stat.get("monthly_runs")).longValue();

                totalRows += rowCount;
                dailyRows += daily;
                monthlyRows += monthly;

                log.debug("event=partition.monitor.detail partition={} rows={} daily={} monthly={} size={}",
                        stat.get("partition_name"), rowCount, daily, monthly, stat.get("total_size"));
            }

            totalRowsGauge.set(totalRows);
            dailyRowsGauge.set(dailyRows);
            monthlyRowsGauge.set(monthlyRows);
            partitionCountGauge.set(stats.size());

            log.info("event=partition.monitor outcome=success partitions={} totalRows={} dailyRows={} monthlyRows={}",
                    stats.size(), totalRows, dailyRows, monthlyRows);

        } catch (Exception e) {
            log.error("event=partition.monitor outcome=failure", e);
            meterRegistry.counter(PARTITION_MONITOR_FAILURE).increment();
        } finally {
            MdcContextUtil.restoreContext(snapshot);
        }
    }
}
