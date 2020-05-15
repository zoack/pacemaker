package org.pacemaker.watchers.probes;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import org.pacemaker.proto.models.Error;
import org.pacemaker.proto.models.ProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

//TODO #resilience
public class TCPProbe implements Probe {

    private static final Logger logger = LoggerFactory.getLogger(TCPProbe.class);

    private String host;
    private int port;

    public TCPProbe(org.pacemaker.proto.models.Probe.TCPSocket socket) {
        host = socket.getHost();
        port = socket.getPort();
    }

    @Override
    public CompletableFuture<ProbeResult> probe() {
        return CompletableFuture.supplyAsync(() ->
        {
            ProbeResult.Builder result = ProbeResult.newBuilder();
            Instant time;
            logger.trace("[TCP PROBE] Testing if host is reachable, host={}:{}", host,port);
            try(Socket socket = new Socket(host, port)){
                //TODO #setup
                boolean reachable = socket.getInetAddress().isReachable(1000);
                time = Instant.now();
                if(reachable){
                    result.setResult(ProbeResult.Result.Success);
                }else{
                    result.setResult(ProbeResult.Result.Failure)
                            .setError(Error.newBuilder()
                            .setReason("TCP_CONNECTION_NOT_REACHABLE")
                            .setFatal(false)
                            .build());
                }

            } catch (SocketException e) {
                time = Instant.now();
                result.setResult(ProbeResult.Result.Failure)
                        .setError(Error.newBuilder()
                        .setReason("TIMEOUT")
                        .setData(Any.pack(StringValue.newBuilder().setValue(e.getMessage()).build()))
                        .setFatal(false));
            } catch (Exception e) {
                time = Instant.now();
                result.setResult(ProbeResult.Result.Failure)
                        .setError(Error.newBuilder()
                        .setReason("UNKNOWN_ERROR")
                        .setData(Any.pack(StringValue.newBuilder().setValue(e.getMessage()).build()))
                        .setFatal(false));
            }
            //TODO set timestamp
            Timestamp.Builder timestamp = Timestamp.newBuilder();
            timestamp.setSeconds(time.getEpochSecond())
                    .setNanos(time.getNano());
            return result
                    .setLastProbeTime(timestamp)
                    .build();
        });
    }
}
