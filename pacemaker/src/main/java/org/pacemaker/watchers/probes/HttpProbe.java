package org.pacemaker.watchers.probes;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import org.pacemaker.proto.models.Error;
import org.pacemaker.proto.models.ProbeResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HttpProbe implements Probe {

    private String host;
    private int port;
    private String path;
    private String protocol;
    private String method;
    private Map<String,String> headers;

    public HttpProbe(org.pacemaker.proto.models.Probe.HttpRequest request) {
        host = request.getHost();
        method = request.getMethod();
        protocol = request.getProtocol();
        port = request.getPort();
        path = request.getPath();
        headers = request.getHeadersMap();
    }


    @Override
    public CompletableFuture<ProbeResult> probe() {
        return CompletableFuture.supplyAsync(() ->{
            ProbeResult.Builder result = ProbeResult.newBuilder();
            Instant time;
            try {
                URL url = new URL(protocol + "://" + host + ":" + port + "/" + path);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod(method);
                headers.forEach(con::setRequestProperty);
                try(InputStream ignore = con.getInputStream()){
                    int responseCode = con.getResponseCode();
                    time = Instant.now();
                    if(responseCode >= 200 && responseCode < 300){
                        result.setResult(ProbeResult.Result.Success);
                    }else{
                        result.setResult(ProbeResult.Result.Failure)
                                .setError(Error.newBuilder()
                                            .setReason("STATUS_CODE_NOT_SUCCESS")
                                            .setData(Any.pack(UInt32Value.newBuilder().setValue(responseCode).build()))
                                            .setFatal(false)
                                            .build());
                    }
                }
            } catch (MalformedURLException | ProtocolException e) {
                time = Instant.now();
                result.setResult(ProbeResult.Result.Failure)
                        .setError(Error.newBuilder()
                        .setReason("MALFORMED_URL")
                        .setData(Any.pack(StringValue.newBuilder().setValue(e.getMessage()).build()))
                        .setFatal(true));
            } catch (SocketException e) {
                time = Instant.now();
                result.setResult(ProbeResult.Result.Failure)
                        .setError(Error.newBuilder()
                        .setReason("TIMEOUT")
                        .setData(Any.pack(StringValue.newBuilder().setValue(e.getMessage()).build()))
                        .setFatal(false)
                        .build());
            } catch (IOException e) {
                time = Instant.now();

                result.setResult(ProbeResult.Result.Failure)
                    .setError(Error.newBuilder()
                    .setReason("UNKNOWN_ERROR")
                    .setFatal(false)
                    .setData(Any.pack(StringValue.newBuilder().setValue(e.getMessage()).build()))
                    .build());
            }
            Timestamp.Builder timestamp = Timestamp.newBuilder();
            timestamp.setSeconds(time.getEpochSecond())
                    .setNanos(time.getNano());
            return result
                    .setLastProbeTime(timestamp)
                    .build();
        });
    }
}
