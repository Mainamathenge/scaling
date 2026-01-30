package autoscaling;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.CreateLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse;
import software.amazon.awssdk.services.ec2.model.DeleteLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateInstanceNetworkInterfaceSpecificationRequest;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesMonitoringEnabled;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.util.Arrays;
import java.util.List;

import static autoscaling.AutoScale.ELBASG_SECURITY_GROUP;
import static autoscaling.AutoScale.HTTP_PORT;
import static autoscaling.AutoScale.INSTANCE_TYPE;
import static autoscaling.AutoScale.LAUNCH_TEMPLATE_NAME;
import static autoscaling.AutoScale.PROJECT_VALUE;
import static autoscaling.AutoScale.TYPE_VALUE;
import static autoscaling.AutoScale.ROLE_VALUE;
import static autoscaling.AutoScale.EOL_VALUE;
import static autoscaling.AutoScale.WEB_SERVICE;


/**
 * Class to manage EC2 resources.
 */
@Slf4j
public final class Ec2 {
    /**
     * EC2 Tags List
     */
    private static final List<Tag> EC2_TAGS_LIST = Arrays.asList(
            Tag.builder().key("Project").value(PROJECT_VALUE).build(),
            Tag.builder().key("Type").value(TYPE_VALUE).build(),
            Tag.builder().key("Role").value(ROLE_VALUE).build(),
            Tag.builder().key("EOL").value(EOL_VALUE).build());

    /**
     * Unused default constructor.
     */
    private Ec2() {
    }

    /**
     * Launch an Ec2 Instance.
     *
     * @param ec2                EC2Client
     * @param tagSpecification   TagsSpecified to create instance
     * @param amiId              amiId
     * @param instanceType       Type of instance
     * @param securityGroupId    Security Group
     * @param detailedMonitoring With Detailed Monitoring Enabled
     * @return Instance object
     */
    public static Instance launchInstance(final Ec2Client ec2,
                                          final TagSpecification tagSpecification,
                                          final String amiId,
                                          final String instanceType,
                                          final String securityGroupId,
                                          final Boolean detailedMonitoring) throws InterruptedException {
        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(amiId)
                .instanceType(InstanceType.fromValue(instanceType))
                .minCount(1)
                .maxCount(1)
                .securityGroupIds(securityGroupId)
                .monitoring(RunInstancesMonitoringEnabled.builder()
                        .enabled(detailedMonitoring)
                        .build())
                .tagSpecifications(tagSpecification)
                .build();

        RunInstancesResponse runResponse = ec2.runInstances(runRequest);
        String instanceId = runResponse.instances().get(0).instanceId();
        log.info("Launched instance: {}", instanceId);

        // Wait for instance to be running and have a public DNS
        Instance instance = null;
        int maxAttempts = 60;
        int attempt = 0;

        while (attempt < maxAttempts) {
            DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();

            DescribeInstancesResponse describeResponse = ec2.describeInstances(describeRequest);
            instance = describeResponse.reservations().get(0).instances().get(0);

            String state = instance.state().nameAsString();
            String publicDns = instance.publicDnsName();

            if ("running".equals(state) && publicDns != null && !publicDns.isEmpty()) {
                log.info("Instance {} is running with DNS: {}", instanceId, publicDns);
                return instance;
            }

            log.info("Waiting for instance {} to be ready (state: {})...", instanceId, state);
            Thread.sleep(5000);
            attempt++;
        }

        throw new RuntimeException("Instance did not become ready in time: " + instanceId);
    }

    /**
     * Get or create a security group and allow all HTTP inbound traffic.
     *
     * @param ec2               EC2 Client
     * @param securityGroupName the name of the security group
     * @param vpcId             the ID of the VPC
     * @return ID of security group
     */
    public static String getOrCreateHttpSecurityGroup(final Ec2Client ec2,
                                                      final String securityGroupName,
                                                      final String vpcId) {
        // Check if security group already exists
        try {
            DescribeSecurityGroupsRequest describeRequest = DescribeSecurityGroupsRequest.builder()
                    .filters(
                            Filter.builder().name("group-name").values(securityGroupName).build(),
                            Filter.builder().name("vpc-id").values(vpcId).build())
                    .build();

            DescribeSecurityGroupsResponse describeResponse = ec2.describeSecurityGroups(describeRequest);

            if (!describeResponse.securityGroups().isEmpty()) {
                String existingGroupId = describeResponse.securityGroups().get(0).groupId();
                log.info("Security group already exists: {}", existingGroupId);
                return existingGroupId;
            }
        } catch (Exception e) {
            log.info("Security group not found, creating new one: {}", securityGroupName);
        }

        // Create new security group
        CreateSecurityGroupRequest createRequest = CreateSecurityGroupRequest.builder()
                .groupName(securityGroupName)
                .description("Security group for " + securityGroupName)
                .vpcId(vpcId)
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.SECURITY_GROUP)
                        .tags(EC2_TAGS_LIST)
                        .build())
                .build();

        CreateSecurityGroupResponse createResponse = ec2.createSecurityGroup(createRequest);
        String securityGroupId = createResponse.groupId();
        log.info("Created security group: {}", securityGroupId);

        // Authorize HTTP ingress traffic (port 80) from anywhere
        IpPermission httpPermission = IpPermission.builder()
                .ipProtocol("tcp")
                .fromPort(HTTP_PORT)
                .toPort(HTTP_PORT)
                .ipRanges(IpRange.builder()
                        .cidrIp("0.0.0.0/0")
                        .description("Allow HTTP from anywhere")
                        .build())
                .build();

        AuthorizeSecurityGroupIngressRequest ingressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(securityGroupId)
                .ipPermissions(httpPermission)
                .build();

        ec2.authorizeSecurityGroupIngress(ingressRequest);
        log.info("Authorized HTTP ingress for security group: {}", securityGroupId);

        return securityGroupId;
    }

    /**
     * Get the default VPC.
     * <p>
     * With EC2-Classic, your instances run in a single, flat network that you share with other customers.
     * With Amazon VPC, your instances run in a virtual private cloud (VPC) that's logically isolated to your AWS account.
     * <p>
     * The EC2-Classic platform was introduced in the original release of Amazon EC2.
     * If you created your AWS account after 2013-12-04, it does not support EC2-Classic,
     * so you must launch your Amazon EC2 instances in a VPC.
     * <p>
     * By default, when you launch an instance, AWS launches it into your default VPC.
     * Alternatively, you can create a non-default VPC and specify it when you launch an instance.
     *
     * @param ec2 EC2 Client
     * @return the default VPC object
     */
    public static Vpc getDefaultVPC(final Ec2Client ec2) {
        DescribeVpcsRequest request = DescribeVpcsRequest.builder()
                .filters(Filter.builder()
                        .name("is-default")
                        .values("true")
                        .build())
                .build();

        DescribeVpcsResponse response = ec2.describeVpcs(request);

        if (response.vpcs().isEmpty()) {
            throw new RuntimeException("No default VPC found");
        }

        Vpc defaultVpc = response.vpcs().get(0);
        log.info("Found default VPC: {}", defaultVpc.vpcId());
        return defaultVpc;
    }

    /**
     * Create launch template.
     *
     * @param ec2 Ec2 Client
     */
    static void createLaunchTemplate(final Ec2Client ec2) {
        // Get the default VPC to find the security group
        Vpc defaultVpc = getDefaultVPC(ec2);
        String securityGroupId = getOrCreateHttpSecurityGroup(ec2, ELBASG_SECURITY_GROUP, defaultVpc.vpcId());

        // Create launch template data
        RequestLaunchTemplateData launchTemplateData = RequestLaunchTemplateData.builder()
                .imageId(WEB_SERVICE)
                .instanceType(InstanceType.fromValue(INSTANCE_TYPE))
                .networkInterfaces(LaunchTemplateInstanceNetworkInterfaceSpecificationRequest.builder()
                        .deviceIndex(0)
                        .associatePublicIpAddress(true)
                        .groups(securityGroupId)
                        .build())
                .monitoring(software.amazon.awssdk.services.ec2.model.LaunchTemplatesMonitoringRequest.builder()
                        .enabled(true)
                        .build())
                .tagSpecifications(software.amazon.awssdk.services.ec2.model.LaunchTemplateTagSpecificationRequest.builder()
                        .resourceType(ResourceType.INSTANCE)
                        .tags(EC2_TAGS_LIST)
                        .build())
                .build();

        CreateLaunchTemplateRequest createRequest = CreateLaunchTemplateRequest.builder()
                .launchTemplateName(LAUNCH_TEMPLATE_NAME)
                .launchTemplateData(launchTemplateData)
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.LAUNCH_TEMPLATE)
                        .tags(EC2_TAGS_LIST)
                        .build())
                .build();

        ec2.createLaunchTemplate(createRequest);
        log.info("Created launch template: {}", LAUNCH_TEMPLATE_NAME);
    }

    /**
     * Terminate an Instance.
     *
     * @param ec2        Ec2 client
     * @param instanceId Instance Id to terminate
     */
    public static void terminateInstance(final Ec2Client ec2, final String instanceId) {
        TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();

        ec2.terminateInstances(terminateRequest);
        log.info("Terminated instance: {}", instanceId);
    }

    /**
     * Delete a Security group.
     *
     * @param ec2              ec2 client
     * @param securityGroupName security group name
     */
    public static void deleteSecurityGroup(final Ec2Client ec2,
                                           final String securityGroupName) {
        // First, find the security group ID by name
        DescribeSecurityGroupsRequest describeRequest = DescribeSecurityGroupsRequest.builder()
                .filters(Filter.builder()
                        .name("group-name")
                        .values(securityGroupName)
                        .build())
                .build();

        DescribeSecurityGroupsResponse describeResponse = ec2.describeSecurityGroups(describeRequest);

        if (describeResponse.securityGroups().isEmpty()) {
            log.warn("Security group not found: {}", securityGroupName);
            return;
        }

        String securityGroupId = describeResponse.securityGroups().get(0).groupId();

        DeleteSecurityGroupRequest deleteRequest = DeleteSecurityGroupRequest.builder()
                .groupId(securityGroupId)
                .build();

        ec2.deleteSecurityGroup(deleteRequest);
        log.info("Deleted security group: {} ({})", securityGroupName, securityGroupId);
    }

    /**
     * Delete launch template.
     *
     * @param ec2 Ec2 Client instance
     */
    public static void deleteLaunchTemplate(final Ec2Client ec2) {
        DeleteLaunchTemplateRequest deleteRequest = DeleteLaunchTemplateRequest.builder()
                .launchTemplateName(LAUNCH_TEMPLATE_NAME)
                .build();

        ec2.deleteLaunchTemplate(deleteRequest);
        log.info("Deleted launch template: {}", LAUNCH_TEMPLATE_NAME);
    }
}
