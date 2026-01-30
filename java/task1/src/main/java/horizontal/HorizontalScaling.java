package horizontal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.ini4j.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ec2.model.*;
import utilities.Configuration;
import utilities.HttpRequest;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.regions.Region;


/**
 * Class for Task1 Solution.
 */
@Slf4j
public final class HorizontalScaling {
    /**
     * Project Tag value.
     */
    public static final String PROJECT_VALUE = "vm-scaling";

    /**
     * Configuration file.
     */
    private static final Configuration CONFIGURATION =
            new Configuration("horizontal-scaling-config.json");

    /**
     * Load Generator AMI.
     */
    private static final String LOAD_GENERATOR =
            CONFIGURATION.getString("load_generator_ami");

    /**
     * Web Service AMI.
     */
    private static final String WEB_SERVICE =
            CONFIGURATION.getString("web_service_ami");

    /**
     * Instance Type Name.
     */
    private static final String INSTANCE_TYPE =
            CONFIGURATION.getString("instance_type");

    /**
     * Web Service Security Group Name.
     */
    private static final String WEB_SERVICE_SECURITY_GROUP =
            "web-service-security-group";

    /**
     * Load Generator Security Group Name.
     */
    private static final String LG_SECURITY_GROUP =
            "lg-security-group";

    /**
     * HTTP Port.
     */
    private static final Integer HTTP_PORT = 80;

    /**
     * Launch Delay in milliseconds.
     */
    private static final long LAUNCH_DELAY = 100000;

    /**
     * RPS target to stop provisioning.
     */
    private static final float RPS_TARGET = 50;

    /**
     * Delay before retrying API call.
     */
    public static final int RETRY_DELAY_MILLIS = 100;

    /**
     * Logger.
     */
    private static Logger logger =
            LoggerFactory.getLogger(HorizontalScaling.class);

    /**
     * Private Constructor.
     */
    private HorizontalScaling() {
    }

    /**
     * Task1 main method.
     *
     * @param args No Args required
     * @throws Exception when something unpredictably goes wrong.
     */
    public static void main(final String[] args) throws Exception {
        // BIG PICTURE: Provision resources to achieve horizontal scalability
        //  - Create security groups for Load Generator and Web Service
        //  - Provision a Load Generator instance
        //  - Provision a Web Service instance
        //  - Register Web Service DNS with Load Generator
        //  - Add Web Service instances to Load Generator
        //  - Terminate resources

        AwsCredentialsProvider credentialsProvider =
                DefaultCredentialsProvider.builder().build();

        // Create an Amazon EC2 Client
        Ec2Client ec2 = Ec2Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider)
                .build();

        // Get the default VPC
        Vpc vpc = getDefaultVPC(ec2);

        // Create Security Groups in the default VPC
        String lgSecurityGroupId = getOrCreateHttpSecurityGroup(ec2, LG_SECURITY_GROUP, vpc.vpcId());
        String wsSecurityGroupId = getOrCreateHttpSecurityGroup(ec2, WEB_SERVICE_SECURITY_GROUP, vpc.vpcId());

        TagSpecification tagSpecification = TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(
                        Tag.builder()
                                .key("Name")
                                .value("LOAD_GENERATOR")
                                .build(),
                        Tag.builder()
                                .key("project")
                                .value(PROJECT_VALUE)
                                .build()
                )
                .build();
        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(LOAD_GENERATOR)
                .instanceType(INSTANCE_TYPE)
                .minCount(1)
                .maxCount(1)
                .securityGroupIds(lgSecurityGroupId)
                .tagSpecifications(tagSpecification)
                .build();

        RunInstancesResponse runResponse = ec2.runInstances(runRequest);

        String loadGenInstanceId = runResponse.instances().get(0).instanceId();
        log.info("Load generation instance ID: {}", loadGenInstanceId);

        DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                .instanceIds(loadGenInstanceId)
                .build();
        Instance loadGeneratorInstance = null;
        while (loadGeneratorInstance == null || !loadGeneratorInstance.state().name().equals(InstanceStateName.RUNNING)) {
            Thread.sleep(5000);
            DescribeInstancesResponse describeResponse = ec2.describeInstances(describeRequest);
            loadGeneratorInstance = describeResponse.reservations()
                    .get(0)
                    .instances()
                    .get(0);
            log.info("Load generation instance ID: {}", loadGeneratorInstance.instanceId());
        }
        String loadGeneratorDNS = loadGeneratorInstance.publicDnsName();

        TagSpecification wsTagSpecification = TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(
                        Tag.builder()
                                .key("Name")
                                .value("Web Service")
                                .build(),
                        Tag.builder()
                                .key("project")
                                .value(PROJECT_VALUE)
                                .build()
                )
                .build();

        RunInstancesRequest wsRunRequest = RunInstancesRequest.builder()
                .imageId(WEB_SERVICE)
                .instanceType(INSTANCE_TYPE)
                .minCount(1)
                .maxCount(1)
                .securityGroupIds(wsSecurityGroupId)
                .tagSpecifications(wsTagSpecification)
                .build();

        RunInstancesResponse wsRunResponse = ec2.runInstances(wsRunRequest);
        String webServiceInstanceId = wsRunResponse.instances().get(0).instanceId();
        log.info("Web Service instance launched with ID: {}", webServiceInstanceId);


        DescribeInstancesRequest wsDescribeRequest = DescribeInstancesRequest.builder()
                .instanceIds(webServiceInstanceId)
                .build();

        Instance webServiceInstance = null;
        while (webServiceInstance == null ||
                !webServiceInstance.state().name().equals(InstanceStateName.RUNNING)) {

            Thread.sleep(5000);

            DescribeInstancesResponse wsDescribeResponse = ec2.describeInstances(wsDescribeRequest);
            webServiceInstance = wsDescribeResponse.reservations()
                    .get(0)
                    .instances()
                    .get(0);

            log.info("Web Service instance state: {} ", webServiceInstance.state().name());
        }
        String webServiceDNS = webServiceInstance.publicDnsName();

        //Initialize test
        String response = initializeTest(loadGeneratorDNS, webServiceDNS);

        //Get TestID
        String testId = getTestId(response);

        //Save launch time
        Date lastLaunchTime = new Date();

        //Monitor LOG file
        Ini ini = getIniUpdate(loadGeneratorDNS, testId);
        while (ini == null || !ini.containsKey("Test finished")) {
            ini = getIniUpdate(loadGeneratorDNS, testId);
            if (ini != null) {
                float currentRPS = getRPS(ini);
                log.info("Current RPS:{} " , currentRPS);
                Date currentTime = new Date();
                long timeSinceLastLaunch = currentTime.getTime() - lastLaunchTime.getTime();
                if (currentRPS < RPS_TARGET && timeSinceLastLaunch >= LAUNCH_DELAY) {
                    TagSpecification newWsTagSpec = TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(
                                    Tag.builder().key("Name").value("Web Service").build(),
                                    Tag.builder().key("project").value(PROJECT_VALUE).build()
                            )
                            .build();
                    RunInstancesRequest newWsRequest = RunInstancesRequest.builder()
                            .imageId(WEB_SERVICE)
                            .instanceType(INSTANCE_TYPE)
                            .minCount(1)
                            .maxCount(1)
                            .securityGroupIds(wsSecurityGroupId)
                            .tagSpecifications(newWsTagSpec)
                            .build();

                    RunInstancesResponse newWsResponse = ec2.runInstances(newWsRequest);
                    String newWsInstanceId = newWsResponse.instances().get(0).instanceId();
                    log.info("Adding one instance:{} " ,newWsInstanceId);

                    DescribeInstancesRequest newWsDescribeRequest = DescribeInstancesRequest.builder()
                            .instanceIds(newWsInstanceId)
                            .build();

                    Instance newWsInstance = null;
                    while (newWsInstance == null ||
                            !newWsInstance.state().name().equals(InstanceStateName.RUNNING)) {

                        Thread.sleep(5000);

                        DescribeInstancesResponse newWsDescribeResponse =
                                ec2.describeInstances(newWsDescribeRequest);
                        newWsInstance = newWsDescribeResponse.reservations()
                                .get(0)
                                .instances()
                                .get(0);

                        log.info("New Web Service state: {}" ,newWsInstance.state().name());
                    }
                    String newWebServiceDNS = newWsInstance.publicDnsName();
                    String addResponse = addWebServiceInstance(loadGeneratorDNS, newWebServiceDNS, testId);
                    lastLaunchTime = new Date();
                }
            }
            Thread.sleep(1000);
        }
        log.info("Load testing completed");
    }

    /**
     * Get the latest RPS.
     *
     * @param ini INI file object
     * @return RPS Value
     */
    private static float getRPS(final Ini ini) {
        float rps = 0;
        for (String key : ini.keySet()) {
            if (key.startsWith("Current rps")) {
                rps = Float.parseFloat(key.split("=")[1]);
            }
        }
        return rps;
    }

    /**
     * Get the latest test log.
     *
     * @param loadGeneratorDNS DNS Name of load generator
     * @param testId           TestID String
     * @return INI Object
     * @throws IOException on network failure
     */
    private static Ini getIniUpdate(final String loadGeneratorDNS,
                                    final String testId)
            throws IOException {
        String response = HttpRequest.sendGet(String.format(
                "http://%s/log?name=test.%s.log",
                loadGeneratorDNS,
                testId));
        File log = new File(testId + ".log");
        FileUtils.writeStringToFile(log, response, Charset.defaultCharset());
        return new Ini(log);
    }

    /**
     * Get ID of test.
     *
     * @param response Response containing LoadGenerator output
     * @return TestID string
     */
    private static String getTestId(final String response) {
        Pattern pattern = Pattern.compile("test\\.([0-9]*)\\.log");
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Initializes Load Generator Test.
     *
     * @param loadGeneratorDNS DNS Name of load generator
     * @param webServiceDNS    DNS Name of web service
     * @return response of initialization (contains test ID)
     */
    private static String initializeTest(final String loadGeneratorDNS,
                                         final String webServiceDNS) {
        String response = "";
        boolean launchWebServiceSuccess = false;
        while (!launchWebServiceSuccess) {
            try {
                response = HttpRequest.sendGet(String.format(
                        "http://%s/test/horizontal?dns=%s",
                        loadGeneratorDNS,
                        webServiceDNS));
                launchWebServiceSuccess = true;
            } catch (Exception e) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return response;
    }

    /**
     * Add a Web Service vm to Load Generator.
     *
     * @param loadGeneratorDNS DNS Name of Load Generator
     * @param webServiceDNS    DNS Name of Web Service
     * @param testId           the test ID
     * @return String response
     */
    private static String addWebServiceInstance(final String loadGeneratorDNS,
                                                final String webServiceDNS,
                                                final String testId) {
        String response = "";
        boolean launchWebServiceSuccess = false;
        while (!launchWebServiceSuccess) {
            try {
                response = HttpRequest.sendGet(String.format(
                        "http://%s/test/horizontal/add?dns=%s",
                        loadGeneratorDNS,
                        webServiceDNS));
                launchWebServiceSuccess = true;
            } catch (Exception e) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                    Ini ini = getIniUpdate(loadGeneratorDNS, testId);
                    if (ini.containsKey("Test finished")) {
                        launchWebServiceSuccess = true;
                        log.info("New WS is not added because the test already completed");
                    }
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            }
        }
        return response;
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
        //TODO: Remove the exception
        //TODO: Get the default VPC
        DescribeVpcsRequest request = DescribeVpcsRequest.builder().build();
        DescribeVpcsResponse response = ec2.describeVpcs(request);
        for (Vpc vpc : response.vpcs()) {
            if (vpc.isDefault() != null && vpc.isDefault()) {
                log.info("Found default VPC: {} " ,vpc.vpcId());
                return vpc;
            }
        }
        throw new UnsupportedOperationException();
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
        DescribeSecurityGroupsRequest describeRequest = DescribeSecurityGroupsRequest.builder()
                .filters(Filter.builder()
                                .name("group-name")
                                .values(securityGroupName)
                                .build(),
                        Filter.builder()
                                .name("vpc-id")
                                .values(vpcId)
                                .build())
                .build();

        try {
            DescribeSecurityGroupsResponse describeResponse = ec2.describeSecurityGroups(describeRequest);

            if (!describeResponse.securityGroups().isEmpty()) {
                String groupId = describeResponse.securityGroups().get(0).groupId();
                System.out.println("Found existing security group: " + groupId);
                return groupId;
            }
        } catch (Ec2Exception e) {
            System.out.println("Error checking for existing security group: " + e.getMessage());
        }

        CreateSecurityGroupRequest createRequest = CreateSecurityGroupRequest.builder()
                .groupName(securityGroupName)
                .description("Security group for HTTP traffic")
                .vpcId(vpcId)
                .build();

        CreateSecurityGroupResponse createResponse = ec2.createSecurityGroup(createRequest);
        String groupId = createResponse.groupId();
        System.out.println("Created new security group: " + groupId);

        IpPermission ipPermission = IpPermission.builder()
                .ipProtocol("tcp")
                .fromPort(80)
                .toPort(80)
                .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                .build();

        AuthorizeSecurityGroupIngressRequest ingressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(groupId)
                .ipPermissions(ipPermission)
                .build();

        ec2.authorizeSecurityGroupIngress(ingressRequest);
        System.out.println("Added HTTP inbound rule to security group");

        return groupId;
    }

    /**
     * Get instance object by ID.
     *
     * @param ec2        EC2 client instance
     * @param instanceId instance ID
     * @return Instance Object
     */
    public static Instance getInstance(final Ec2Client ec2,
                                       final String instanceId) {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();

        DescribeInstancesResponse response = ec2.describeInstances(request);

        if (!response.reservations().isEmpty() &&
                !response.reservations().get(0).instances().isEmpty()) {
            return response.reservations().get(0).instances().get(0);
        }

        throw new IllegalArgumentException("Instance not found: " + instanceId);
    }
}
