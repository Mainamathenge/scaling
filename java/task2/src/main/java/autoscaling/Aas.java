package autoscaling;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.*;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Vpc;

import static autoscaling.AutoScale.EOL_VALUE;
import static autoscaling.AutoScale.PROJECT_VALUE;
import static autoscaling.AutoScale.ROLE_VALUE;
import static autoscaling.AutoScale.TYPE_VALUE;
import static autoscaling.AutoScale.configuration;


/**
 * Amazon AutoScaling resource class.
 */
@Slf4j
public final class Aas {


    /**
     * Max size of ASG.
     */
    private static final Integer MAX_SIZE_ASG =
            configuration.getInt("asg_max_size");
    
    /**
     * Min size of ASG.
     */
    private static final Integer MIN_SIZE_ASG =
            configuration.getInt("asg_min_size");

    /**
     * Health Check grace period.
     */
    private static final Integer HEALTH_CHECK_GRACE_PERIOD =
            configuration.getInt("health_check_grace_period");
    
    /**
     * Cool down period Scale In.
     */
    private static final Integer COOLDOWN_PERIOD_SCALEIN =
            configuration.getInt("cool_down_period_scale_in");

    /**
     * Cool down period Scale Out.
     */
    private static final Integer COOLDOWN_PERIOD_SCALEOUT =
            configuration.getInt("cool_down_period_scale_out");

    /**
     * Number of instances to scale out by.
     */
    private static final Integer SCALING_OUT_ADJUSTMENT =
            configuration.getInt("scale_out_adjustment");
    
    /**
     * Number of instances to scale in by.
     */
    private static final Integer SCALING_IN_ADJUSTMENT =
            configuration.getInt("scale_in_adjustment");

    /**
     * ASG Cool down period in seconds.
     */
    private static final Integer COOLDOWN_PERIOD_ASG =
            configuration.getInt("asg_default_cool_down_period");

    /**
     * AAS Tags List with propagation enabled for instances.
     */
    private static final List<Tag> AAS_TAGS_LIST = Arrays.asList(
            Tag.builder().key("Project").value(PROJECT_VALUE).propagateAtLaunch(true).build(),
            Tag.builder().key("Type").value(TYPE_VALUE).propagateAtLaunch(true).build(),
            Tag.builder().key("Role").value(ROLE_VALUE).propagateAtLaunch(true).build(),
            Tag.builder().key("EOL").value(EOL_VALUE).propagateAtLaunch(true).build());

    /**
     * ASG name from configuration.
     */
    private static final String ASG_NAME =
            configuration.getString("auto_scaling_group_name");

    /**
     * Launch template name from configuration.
     */
    private static final String LAUNCH_TEMPLATE_NAME =
            configuration.getString("launch_template_name");

    /**
     * CPU upper threshold percentage.
     */
    private static final Double CPU_UPPER_THRESHOLD =
            configuration.getDouble("cpu_upper_threshold");

    /**
     * CPU lower threshold percentage.
     */
    private static final Double CPU_LOWER_THRESHOLD =
            configuration.getDouble("cpu_lower_threshold");

    /**
     * Alarm period in seconds.
     */
    private static final Integer ALARM_PERIOD =
            configuration.getInt("alarm_period");

    /**
     * Alarm evaluation periods for scale out.
     */
    private static final Integer EVAL_PERIODS_SCALE_OUT =
            configuration.getInt("alarm_evaluation_periods_scale_out");

    /**
     * Alarm evaluation periods for scale in.
     */
    private static final Integer EVAL_PERIODS_SCALE_IN =
            configuration.getInt("alarm_evaluation_periods_scale_in");

    /**
     * Scale-out policy name.
     */
    private static final String SCALE_OUT_POLICY_NAME =
            ASG_NAME + "-scale-out-policy";

    /**
     * Scale-in policy name.
     */
    private static final String SCALE_IN_POLICY_NAME =
            ASG_NAME + "-scale-in-policy";

    /**
     * High CPU alarm name.
     */
    private static final String HIGH_CPU_ALARM_NAME =
            ASG_NAME + "-high-cpu-alarm";

    /**
     * Low CPU alarm name.
     */
    private static final String LOW_CPU_ALARM_NAME =
            ASG_NAME + "-low-cpu-alarm";

    /**
     * CPU metric namespace.
     */
    private static final String METRIC_NAMESPACE = "AWS/EC2";

    /**
     * CPU metric name.
     */
    private static final String METRIC_NAME = "CPUUtilization";

    /**
     * Unused constructor.
     */
    private Aas() {
    }

    /**
     * Create auto scaling group.
     * Create and attach Cloud Watch Policies.
     *
     * @param aas            AAS Client
     * @param cloudWatch     CloudWatch client
     * @param ec2            EC2 client
     * @param targetGroupArn target group arn
     */
    public static void createAutoScalingGroup(final AutoScalingClient aas,
                                              final CloudWatchClient cloudWatch,
                                              final Ec2Client ec2,
                                              final String targetGroupArn) {
        // Get the default VPC and its subnets
        Vpc defaultVpc = Ec2.getDefaultVPC(ec2);
        DescribeSubnetsRequest subnetsRequest = DescribeSubnetsRequest.builder()
                .filters(Filter.builder()
                        .name("vpc-id")
                        .values(defaultVpc.vpcId())
                        .build())
                .build();

        DescribeSubnetsResponse subnetsResponse = ec2.describeSubnets(subnetsRequest);
        String vpcZoneIdentifier = subnetsResponse.subnets().stream()
                .map(Subnet::subnetId)
                .collect(Collectors.joining(","));

        log.info("Using subnets for ASG: {}", vpcZoneIdentifier);

        CreateAutoScalingGroupRequest createAutoScalingGroupRequest = CreateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(ASG_NAME)
                .launchTemplate(LaunchTemplateSpecification.builder()
                        .launchTemplateName(LAUNCH_TEMPLATE_NAME)
                        .version("$Latest")
                        .build()
                )
                .minSize(MIN_SIZE_ASG)
                .maxSize(MAX_SIZE_ASG)
                .desiredCapacity(MIN_SIZE_ASG)
                .defaultCooldown(COOLDOWN_PERIOD_ASG)
                .healthCheckGracePeriod(HEALTH_CHECK_GRACE_PERIOD)
                .healthCheckType("ELB")
                .vpcZoneIdentifier(vpcZoneIdentifier)
                .targetGroupARNs(targetGroupArn)
                .tags(AAS_TAGS_LIST)
                .build();
        aas.createAutoScalingGroup(createAutoScalingGroupRequest);
        log.info("Created Auto Scaling Group: {}", ASG_NAME);

        // scale-out policy
        PutScalingPolicyRequest scaleOutPolicyRequest =
                PutScalingPolicyRequest.builder()
                        .autoScalingGroupName(ASG_NAME)
                        .policyName(SCALE_OUT_POLICY_NAME)
                        .policyType("SimpleScaling")
                        .adjustmentType("ChangeInCapacity")
                        .scalingAdjustment(SCALING_OUT_ADJUSTMENT)
                        .cooldown(COOLDOWN_PERIOD_SCALEOUT)
                        .build();

        PutScalingPolicyResponse scaleOutResponse =
                aas.putScalingPolicy(scaleOutPolicyRequest);
        String scaleOutPolicyArn = scaleOutResponse.policyARN();
        log.info("Scale Out Policy Arn: {}", scaleOutPolicyArn);

        // Scale-in policy
        PutScalingPolicyRequest scaleInPolicyRequest =
                PutScalingPolicyRequest.builder()
                        .autoScalingGroupName(ASG_NAME)
                        .policyName(SCALE_IN_POLICY_NAME)
                        .policyType("SimpleScaling")
                        .adjustmentType("ChangeInCapacity")
                        .scalingAdjustment(SCALING_IN_ADJUSTMENT)
                        .cooldown(COOLDOWN_PERIOD_SCALEIN)
                        .build();

        PutScalingPolicyResponse scaleInResponse =
                aas.putScalingPolicy(scaleInPolicyRequest);
        String scaleInPolicyArn = scaleInResponse.policyARN();
        log.info("Scale In Policy Arn: {}", scaleInPolicyArn);

        // High alarm cpu alarm
        PutMetricAlarmRequest highCpuAlarmRequest =
                PutMetricAlarmRequest.builder()
                        .alarmName(HIGH_CPU_ALARM_NAME)
                        .alarmDescription("Trigger scale-out when CPU is high")
                        .metricName(METRIC_NAME)
                        .namespace(METRIC_NAMESPACE)
                        .statistic(Statistic.AVERAGE)
                        .period(ALARM_PERIOD)
                        .evaluationPeriods(EVAL_PERIODS_SCALE_OUT)
                        .threshold(CPU_UPPER_THRESHOLD)
                        .comparisonOperator(
                                ComparisonOperator.GREATER_THAN_THRESHOLD)
                        .dimensions(Dimension.builder()
                                .name("AutoScalingGroupName")
                                .value(ASG_NAME)
                                .build())
                        .alarmActions(scaleOutPolicyArn)
                        .build();
        cloudWatch.putMetricAlarm(highCpuAlarmRequest);
        log.info("High CPU alarm created: : {}", scaleOutPolicyArn);

        // Low alarm scale-in
        PutMetricAlarmRequest lowCpuAlarmRequest =
                PutMetricAlarmRequest.builder()
                        .alarmName(LOW_CPU_ALARM_NAME)
                        .alarmDescription("Trigger scale-in when CPU is low")
                        .metricName(METRIC_NAME)
                        .namespace(METRIC_NAMESPACE)
                        .statistic(Statistic.AVERAGE)
                        .period(ALARM_PERIOD)
                        .evaluationPeriods(EVAL_PERIODS_SCALE_IN)
                        .threshold(CPU_LOWER_THRESHOLD)
                        .comparisonOperator(
                                ComparisonOperator.LESS_THAN_THRESHOLD)
                        .dimensions(Dimension.builder()
                                .name("AutoScalingGroupName")
                                .value(ASG_NAME)
                                .build())
                        .alarmActions(scaleInPolicyArn)
                        .build();

        cloudWatch.putMetricAlarm(lowCpuAlarmRequest);
        log.info("Low CPU alarm created: {}", scaleInPolicyArn);
    }

    /**
     * Terminate auto scaling group.
     *
     * @param aas AAS client
     */
    public static void terminateAutoScalingGroup(final AutoScalingClient aas) {
        // Delete the Auto Scaling group
        DeleteAutoScalingGroupRequest deleteRequest =
                DeleteAutoScalingGroupRequest.builder()
                        .autoScalingGroupName(ASG_NAME)
                        .forceDelete(true)
                        .build();

        aas.deleteAutoScalingGroup(deleteRequest);
        log.info("Auto Scaling Group deleted: {} " , ASG_NAME);
    }
}
