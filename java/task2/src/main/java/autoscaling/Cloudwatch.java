package autoscaling;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.DeleteAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import static autoscaling.AutoScale.AUTO_SCALING_GROUP_NAME;
import static autoscaling.AutoScale.configuration;


/**
 * CloudWatch resources.
 */
@Slf4j
public final class Cloudwatch {

    /**
     * High CPU alarm name.
     */
    private static final String HIGH_CPU_ALARM_NAME =
            AUTO_SCALING_GROUP_NAME + "-high-cpu-alarm";

    /**
     * Low CPU alarm name.
     */
    private static final String LOW_CPU_ALARM_NAME =
            AUTO_SCALING_GROUP_NAME + "-low-cpu-alarm";

    /**
     * CPU metric namespace.
     */
    private static final String METRIC_NAMESPACE = "AWS/EC2";

    /**
     * CPU metric name.
     */
    private static final String METRIC_NAME = "CPUUtilization";
    /**
     * Sixty seconds.
     */
    private static final Integer ALARM_PERIOD =
            configuration.getInt("alarm_period");
    
    /**
     * CPU Lower Threshold.
     */
    private static final Double CPU_LOWER_THRESHOLD =
            configuration.getDouble("cpu_lower_threshold");
    
    /**
     * CPU Upper Threshold.
     */
    private static final Double CPU_UPPER_THRESHOLD =
            configuration.getDouble("cpu_upper_threshold");
    
    /**
     * Alarm Evaluation Period out.
     */
    public static final Integer ALARM_EVALUATION_PERIODS_SCALE_OUT =
            configuration.getInt("alarm_evaluation_periods_scale_out");
    
    /**
     * Alarm Evaluation Period in.
     */
    public static final Integer ALARM_EVALUATION_PERIODS_SCALE_IN =
            configuration.getInt("alarm_evaluation_periods_scale_in");

    /**
     * Unused constructor.
     */
    private Cloudwatch() {
    }

    /**
     * Create Scale out alarm.
     *
     * @param cloudWatch cloudWatch instance
     * @param policyArn  policy ARN
     */
    public static void createScaleOutAlarm(final CloudWatchClient cloudWatch,
                                           final String policyArn) {
        PutMetricAlarmRequest highCpuAlarmRequest = PutMetricAlarmRequest.builder()
                .alarmName(HIGH_CPU_ALARM_NAME)
                .alarmDescription("Trigger scale-out when CPU is high")
                .metricName(METRIC_NAME)
                .namespace(METRIC_NAMESPACE)
                .statistic(Statistic.AVERAGE)
                .period(ALARM_PERIOD)
                .evaluationPeriods(ALARM_EVALUATION_PERIODS_SCALE_OUT)
                .threshold(CPU_UPPER_THRESHOLD)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .dimensions(Dimension.builder()
                        .name("AutoScalingGroupName")
                        .value(AUTO_SCALING_GROUP_NAME)
                        .build())
                .alarmActions(policyArn)
                .build();

        cloudWatch.putMetricAlarm(highCpuAlarmRequest);
        log.info("High CPU alarm created: {}", HIGH_CPU_ALARM_NAME);
    }

    /**
     * Create ScaleIn Alarm.
     *
     * @param cloudWatch cloud watch instance
     * @param policyArn  policy Arn
     */
    public static void createScaleInAlarm(final CloudWatchClient cloudWatch,
                                          final String policyArn) {
        PutMetricAlarmRequest lowCpuAlarmRequest = PutMetricAlarmRequest.builder()
                .alarmName(LOW_CPU_ALARM_NAME)
                .alarmDescription("Trigger scale-in when CPU is low")
                .metricName(METRIC_NAME)
                .namespace(METRIC_NAMESPACE)
                .statistic(Statistic.AVERAGE)
                .period(ALARM_PERIOD)
                .evaluationPeriods(ALARM_EVALUATION_PERIODS_SCALE_IN)
                .threshold(CPU_LOWER_THRESHOLD)
                .comparisonOperator(ComparisonOperator.LESS_THAN_THRESHOLD)
                .dimensions(Dimension.builder()
                        .name("AutoScalingGroupName")
                        .value(AUTO_SCALING_GROUP_NAME)
                        .build())
                .alarmActions(policyArn)
                .build();

        cloudWatch.putMetricAlarm(lowCpuAlarmRequest);
        log.info("Low CPU alarm created: {}", LOW_CPU_ALARM_NAME);
    }

    /**
     * Delete the two above Alarms.
     *
     * @param cloudWatch cloud watch client
     */
    public static void deleteAlarms(final CloudWatchClient cloudWatch) {
        DeleteAlarmsRequest deleteAlarmsRequest = DeleteAlarmsRequest.builder()
                .alarmNames(HIGH_CPU_ALARM_NAME, LOW_CPU_ALARM_NAME)
                .build();

        cloudWatch.deleteAlarms(deleteAlarmsRequest);
        log.info("Deleted alarms: {}, {}", HIGH_CPU_ALARM_NAME, LOW_CPU_ALARM_NAME);
    }
}
