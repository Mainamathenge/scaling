package autoscaling;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateListenerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerSchemeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;

import static autoscaling.AutoScale.AUTO_SCALING_TARGET_GROUP;
import static autoscaling.AutoScale.EOL_VALUE;
import static autoscaling.AutoScale.HTTP_PORT;
import static autoscaling.AutoScale.LOAD_BALANCER_NAME;
import static autoscaling.AutoScale.PROJECT_VALUE;
import static autoscaling.AutoScale.ROLE_VALUE;
import static autoscaling.AutoScale.TYPE_VALUE;


/**
 * ELB resources class.
 */
@Slf4j
public final class Elb {
    /**
     * ELB Tags.
     */
    public static final List<Tag> ELB_TAGS_LIST = Arrays.asList(
            Tag.builder().key("Project").value(PROJECT_VALUE).build(),
            Tag.builder().key("Type").value(TYPE_VALUE).build(),
            Tag.builder().key("Role").value(ROLE_VALUE).build(),
            Tag.builder().key("EOL").value(EOL_VALUE).build());

    /**
     * Unused default constructor.
     */
    private Elb() {
    }

    /**
     * Create a target group.
     *
     * @param elb elb client
     * @param ec2 ec2 client
     * @return target group instance
     */
    public static TargetGroup createTargetGroup(
            final ElasticLoadBalancingV2Client elb,
            final Ec2Client ec2) {
        // Get the default VPC
        Vpc defaultVpc = Ec2.getDefaultVPC(ec2);

        CreateTargetGroupRequest createRequest = CreateTargetGroupRequest.builder()
                .name(AUTO_SCALING_TARGET_GROUP)
                .protocol(ProtocolEnum.HTTP)
                .port(HTTP_PORT)
                .vpcId(defaultVpc.vpcId())
                .targetType(TargetTypeEnum.INSTANCE)
                .healthCheckProtocol(ProtocolEnum.HTTP)
                .healthCheckPath("/")
                .healthCheckIntervalSeconds(30)
                .healthCheckTimeoutSeconds(5)
                .healthyThresholdCount(2)
                .unhealthyThresholdCount(2)
                .tags(ELB_TAGS_LIST)
                .build();

        CreateTargetGroupResponse response = elb.createTargetGroup(createRequest);
        TargetGroup targetGroup = response.targetGroups().get(0);
        log.info("Created target group: {} (ARN: {})", targetGroup.targetGroupName(), targetGroup.targetGroupArn());

        return targetGroup;
    }

    /**
     * create a load balancer.
     *
     * @param elb             ELB client
     * @param ec2             EC2 client
     * @param securityGroupId Security group ID
     * @param targetGroupArn  target group ARN
     * @return Load balancer instance
     */
    public static LoadBalancer createLoadBalancer(
            final ElasticLoadBalancingV2Client elb,
            final Ec2Client ec2,
            final String securityGroupId,
            final String targetGroupArn) {
        // Get the default VPC
        Vpc defaultVpc = Ec2.getDefaultVPC(ec2);

        // Get all subnets in the VPC (need at least 2 AZs for ALB)
        DescribeSubnetsRequest subnetsRequest = DescribeSubnetsRequest.builder()
                .filters(Filter.builder()
                        .name("vpc-id")
                        .values(defaultVpc.vpcId())
                        .build())
                .build();

        DescribeSubnetsResponse subnetsResponse = ec2.describeSubnets(subnetsRequest);
        List<String> subnetIds = subnetsResponse.subnets().stream()
                .map(Subnet::subnetId)
                .collect(Collectors.toList());

        if (subnetIds.size() < 2) {
            throw new RuntimeException("ALB requires at least 2 subnets in different AZs");
        }

        log.info("Using subnets: {}", subnetIds);

        // Create the Application Load Balancer
        CreateLoadBalancerRequest createLbRequest = CreateLoadBalancerRequest.builder()
                .name(LOAD_BALANCER_NAME)
                .subnets(subnetIds)
                .securityGroups(securityGroupId)
                .scheme(LoadBalancerSchemeEnum.INTERNET_FACING)
                .type(LoadBalancerTypeEnum.APPLICATION)
                .tags(ELB_TAGS_LIST)
                .build();

        CreateLoadBalancerResponse lbResponse = elb.createLoadBalancer(createLbRequest);
        LoadBalancer loadBalancer = lbResponse.loadBalancers().get(0);
        log.info("Created load balancer: {} (DNS: {})", loadBalancer.loadBalancerName(), loadBalancer.dnsName());

        // Create a listener for the load balancer
        CreateListenerRequest listenerRequest = CreateListenerRequest.builder()
                .loadBalancerArn(loadBalancer.loadBalancerArn())
                .protocol(ProtocolEnum.HTTP)
                .port(HTTP_PORT)
                .defaultActions(Action.builder()
                        .type(ActionTypeEnum.FORWARD)
                        .targetGroupArn(targetGroupArn)
                        .build())
                .build();

        elb.createListener(listenerRequest);
        log.info("Created listener for load balancer on port {}", HTTP_PORT);

        return loadBalancer;
    }

    /**
     * Delete a load balancer.
     *
     * @param elb             LoadBalancing client
     * @param loadBalancerArn load balancer ARN
     */
    public static void deleteLoadBalancer(final ElasticLoadBalancingV2Client elb,
                                          final String loadBalancerArn) {
        DeleteLoadBalancerRequest deleteRequest = DeleteLoadBalancerRequest.builder()
                .loadBalancerArn(loadBalancerArn)
                .build();

        elb.deleteLoadBalancer(deleteRequest);
        log.info("Deleted load balancer: {}", loadBalancerArn);
    }

    /**
     * Delete Target Group.
     *
     * @param elb            ELB Client
     * @param targetGroupArn target Group ARN
     */
    public static void deleteTargetGroup(final ElasticLoadBalancingV2Client elb,
                                         final String targetGroupArn) {
        DeleteTargetGroupRequest deleteRequest = DeleteTargetGroupRequest.builder()
                .targetGroupArn(targetGroupArn)
                .build();

        elb.deleteTargetGroup(deleteRequest);
        log.info("Deleted target group: {}", targetGroupArn);
    }
}
