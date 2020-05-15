package org.pacemaker.client;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.Any;
import com.google.protobuf.FloatValue;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.pacemaker.proto.models.*;
import org.pacemaker.proto.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PaceMakerClient {
    private static final Logger logger = LoggerFactory.getLogger("ApolloClient");

    private final ManagedChannel channel;

    private final DeploymentServiceGrpc.DeploymentServiceBlockingStub blockingStub;

    private final DeploymentServiceGrpc.DeploymentServiceFutureStub futureStub;

    private final DeploymentServiceGrpc.DeploymentServiceStub asyncStub;


    private final FrameworkServiceGrpc.FrameworkServiceBlockingStub frameworkBlockingStub;


    private ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /** Construct client for accessing RouteGuide server at {@code host:port}. */
    public PaceMakerClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext());
    }

    /** Construct client for accessing RouteGuide server using the existing channel. */
    private PaceMakerClient(ManagedChannelBuilder<?> channelBuilder) {
        channel = channelBuilder.build();
        blockingStub = DeploymentServiceGrpc.newBlockingStub(channel);
        futureStub = DeploymentServiceGrpc.newFutureStub(channel);
        asyncStub = DeploymentServiceGrpc.newStub(channel);
        frameworkBlockingStub = FrameworkServiceGrpc.newBlockingStub(channel);
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }


    /**
     * Blocking unary call example.  Calls getFeature and prints the response.
     */
    public DeploymentResponse deploy(URL file) {
        try {
            //DeploymentRequest deployment = DeploymentRequest.parseFrom(Files.newInputStream(Paths.get(file.toURI()), StandardOpenOption.READ));


            String containerID = "ae7bf0f3-8911-46ac-b183-e603b48f31df";
            String taskName = "odfe";
            String containerName = "node1";
           /* DeploymentRequest deploymentRequest = DeploymentRequest
                    .newBuilder()
                        .addDeployments(Deployment.newBuilder()
                            .setId("9fb285aa-64e2-49f4-956a-b127b9e35abd")
                            .setPrincipal("principalo")
                            .addRoles("devo")
                            .setStrategy(Deployment.DeploymentStrategy.PARALLEL)
                            .setFailureStrategy(Deployment.FailureStrategy.FAIL_FAST)
                            .addTasks(Task.newBuilder()
                                    .setId(UUID.randomUUID().toString())
                                    .setName(taskName)
                                    .addNetworks("pacemaker")
                                    .setRole("devo")
                                    .setDesiredState(TaskStatus.State.Running)
                                    .setStatus(TaskStatus.newBuilder())
                                    .setWorkload(Task.Workload.STATEFUL)
                                    .setRetryPolicy(RetryPolicy.newBuilder()
                                            .setMaxAttempts(5)
                                            .setMultiplier(2)
                                            .setIntervalInMs(5000))
                                    .addContainers(Container.newBuilder()
                                            .setId(containerID)
                                            .setName(containerName)
                                            .setInternalName(taskName + "-" + containerName + "-" + containerID.substring(0,8))
                                            .setImage("amazon/opendistro-for-elasticsearch:1.6.0")
                                            .setStartUpProbe(Probe.newBuilder().setTcpSocket(Probe.TCPSocket.newBuilder().setPort(9300)))*/
                                       /*     .addVariables(Variable.newBuilder()
                                                    .setKey("cluster.name")
                                                    .setValue("odfe-cluster"))
                                            .addVariables(Variable.newBuilder()
                                                    .setKey("node.name")
                                                    .setValue("odfe-node1"))
                                            .addVariables(Variable.newBuilder()
                                                    .setKey("discovery.seed_hosts")
                                                    .setValue("odfe-node1,odfe-node2"))
                                            .addVariables(Variable.newBuilder()
                                                    .setKey("cluster.initial_master_nodes")
                                                    .setValue("odfe-node1,odfe-node2"))
                                            .addVariables(Variable.newBuilder()
                                                    .setKey("bootstrap.memory_lock")
                                                    .setValue("true"))
                                            .addVariables(Variable.newBuilder()
                                                    .setKey("ES_JAVA_OPTS")
                                                    .setValue("-Xms512m -Xmx512m"))*/
                                          /*  .addVariables(Variable.newBuilder()
                                                    .setKey("discovery.type")
                                                    .setValue("single-node")
                                            )
                                            .setResources(
                                                    Resources.newBuilder()
                                                            .setCpus(2.0f)
                                                            .setMemory(2048.0f)
                                                            .addPorts(Port.newBuilder().setPort(9200))
                                                            .addPorts(Port.newBuilder().setPort(9600))
                                                           .addDisks(Disk.newBuilder()
                                                                    .setId(UUID.randomUUID().toString())
                                                                    .setSize(100)
                                                                    .setPersistent(true)
                                                                    .setType(Disk.DiskType.Path)
                                                                    .setVolume(Volume.newBuilder().setDestinationPath("/usr/share/elasticsearch/data"))))
                                            .setForce(true))
                                    .setExtension(Any.pack(MesosTask.newBuilder().setDocker(true).build())))
                            .build()
                    ).build();*/

            DeploymentRequest deploymentRequest = DeploymentRequest
                    .newBuilder()
                    .addDeployments(Deployment.newBuilder()
                            .setId("9fb285aa-64e2-49f4-956a-b127b9e35abd")
                            .setPrincipal("principalo")
                            .addRoles("devo")
                            .setStrategy(Deployment.DeploymentStrategy.PARALLEL)
                            .setFailureStrategy(Deployment.FailureStrategy.FAIL_FAST)

                            .addTasks(Task.newBuilder()
                                    .setId(UUID.randomUUID().toString())
                                    .setName(taskName)
                                    .addNetworks("pacemaker")
                                    .setRole("devo")
                                    .setDesiredState(TaskStatus.State.Running)
                                    .setStatus(TaskStatus.newBuilder()
                                            .build())
                                    .setWorkload(Task.Workload.STATEFUL)
                                    .setRetryPolicy(RetryPolicy.newBuilder()
                                            .setMaxAttempts(5)
                                            .setMultiplier(2)
                                            .setIntervalInMs(5000)
                                            .build())
                                    .addContainers(Container.newBuilder()
                                            .setId(containerID)
                                            .setName(containerName)
                                            .setInternalName(taskName + "-" + containerName + "-" + containerID.substring(0,8))
                                            .setImage("library/redis")
                                            .setStartUpProbe(Probe.newBuilder()
                                                    .setTcpSocket(Probe.TCPSocket.newBuilder()
                                                            .setPort(6379)
                                                            .build())
                                                    .build())
                                            .addVariables(Variable.newBuilder()
                                                    .setKey("KEY")
                                                    .setValue("VALUE"))
                                            .setResources(
                                                    Resources.newBuilder()
                                                            .setCpus(1.0f)
                                                            .setMemory(128)
                                                            .addPorts(Port.newBuilder()
                                                                    .setPort(9000)
                                                                    .build())
                                                            .addDisks(Disk.newBuilder()
                                                                    .setId(UUID.randomUUID().toString())
                                                                    .setSize(100)
                                                                    .setPersistent(true)
                                                                    .setType(Disk.DiskType.Path)
                                                                    .setVolume(Volume.newBuilder().setDestinationPath("edu2").build())
                                                                    .build())

                                                            .build())
                                            .setForce(true)
                                            .build())
                                    .setExtension(Any.pack(MesosTask.newBuilder()
                                            .setDocker(true)
                                            /*.setExecutorResources(
                                                    Resources.newBuilder()
                                                            .setCpus(0.5f)
                                                            .setMemory(64.0f)
                                                            .addDisks(Disk.newBuilder().setSize(100.0f).build())
                                                            .build()
                                            )*/.build()))
                                    .build())
                            .build()
                    ).build();
            return blockingStub.deploy(deploymentRequest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public DeploymentResponse stopDeployment(String id) {
        try {
            return blockingStub.stop(IDsRequest.newBuilder().addIds(id).build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public DeploymentResponse startDeployment(String id) {
        try {
            return blockingStub.start(IDsRequest.newBuilder().addIds(id).build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public FrameworkResponse create(URL path) {

        FrameworkCreateRequest.Builder builder = FrameworkCreateRequest.newBuilder()
                .addFrameworks(
                FrameworkInfo.newBuilder()
                        .setName("pacemaker")
                        .addRole("devo")
                        .setPrincipal("principalo")
                        .setId("7c496b8a-1f87-4981-85a5-13a28090f105")
                        .setDomain("mesos")
                        .setUser("root")
                        //.setDesiredState(FrameworkStatus.State.Active)
                        .setRetryPolicy(RetryPolicy.newBuilder()
                                .setMaxAttempts(5)
                                .build())
                        .setExtension(Any.pack(
                                MesosFrameworkInfo.newBuilder()
                                        //.setMaster("192.168.122.41:5050")
                                        .setMaster("10.200.130.11:5050")
                                        .setFailoverTimeout(FloatValue.newBuilder().setValue(100000.0f).build())
                                        .addAllCapabilities(Arrays.asList("SHARED_RESOURCES","RESERVATION_REFINEMENT","MULTI_ROLE"))
                                        .build()))
                        .build()
        );

        return frameworkBlockingStub.create(builder.build());

    }

    public FrameworkResponse stop(Collection<String> frameworkIDs) {
        return frameworkBlockingStub.stop(FrameworkIDsRequest.newBuilder()
                .addAllFrameworkIds(frameworkIDs)
                .build());
    }

    public FrameworkResponse start(Collection<String> frameworkIDs) {
        return frameworkBlockingStub.start(FrameworkIDsRequest.newBuilder()
                .addAllFrameworkIds(frameworkIDs)
                .build());
    }

    public static void main(String[] args) throws MalformedURLException, InterruptedException {
        logger.info("Starting pacemaker client...");
        String ip = "localhost";
        int port = 8080;
        logger.info("Client will be connected to server {}:{}",ip,port);
        PaceMakerClient client = new PaceMakerClient(ip, port);
        ///InputStream resourceAsStream = ApolloClient.class.getResourceAsStream("redis.yml");
       // client.create(new URL("file:///home/etarascon/Develop/home/etarascon/Develop/Workspace/stratio/frameworks/pacemaker/pacemaker/src/main/resources/log4j2.properties"));
       // client.start(Collections.singleton("7c496b8a-1f87-4981-85a5-13a28090f105"));
        //client.stop(Collections.singleton("7c496b8a-1f87-4981-85a5-13a28090f105"));
       // Thread.sleep(4000);
       // client.deploy(new URL("file:///home/etarascon/Develop/Workspace/stratio/frameworks/pacemaker/pacemaker-client/src/main/resources/redis.yml"));
        //Thread.sleep(3000);
        client.stopDeployment("9fb285aa-64e2-49f4-956a-b127b9e35abd");
        //client.startDeployment("9fb285aa-64e2-49f4-956a-b127b9e35abd");
    }
}
