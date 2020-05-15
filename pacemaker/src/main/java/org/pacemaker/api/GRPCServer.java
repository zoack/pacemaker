package org.pacemaker.api;

import org.pacemaker.manager.DeploymentManager;
import org.pacemaker.proto.models.Variable;
import org.pacemaker.secrets.SecretsConnector;
import org.pacemaker.services.DeploymentService;
import org.pacemaker.services.FrameworkService;
import org.pacemaker.manager.FrameworkManager;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.pacemaker.services.SecretsService;
import org.pacemaker.variables.VariablesConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

public class GRPCServer implements Closeable, Runnable {
    private static final Logger logger = LoggerFactory.getLogger("GRPCServer");
    private final FrameworkManager frameworkManager;
    private final DeploymentManager deploymentManager;
    private final SecretsConnector secretsConnector;
    private final VariablesConnector variablesConnector;

    private Server server;
    private int port;

    //TODO #setup
    public GRPCServer(FrameworkManager frameworkManager,
                      DeploymentManager deploymentManager,
                      SecretsConnector secretsConnector,
                      VariablesConnector variablesConnector,
                      int port) {
        this.frameworkManager = frameworkManager;
        this.deploymentManager = deploymentManager;
        this.secretsConnector = secretsConnector;
        this.variablesConnector = variablesConnector;
        this.port = port;
    }

    public void run(){
        try {
            logger.info("Starting GRPC server...");
            server = ServerBuilder.forPort(port)
                .addService(new DeploymentService(deploymentManager))
                .addService(new FrameworkService(frameworkManager))
                .addService(new SecretsService(secretsConnector))
                .build()
                .start();

        logger.info("GRPC server started, listening on port={}", port);
            server.awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }catch (IOException e){
            logger.error("Error on server",e);
        }
    }

    @Override
    public void close() {
        logger.info("Shutting down GRPC server...");
        if(server != null) server.shutdown();
    }

}
