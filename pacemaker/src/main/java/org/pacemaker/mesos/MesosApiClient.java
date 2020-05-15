package org.pacemaker.mesos;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import org.pacemaker.mesos.api.model.AgentState;
import org.pacemaker.utils.FutureUtils;
import org.apache.mesos.Protos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MesosApiClient {

    private Gson gson;
    private String master;
    private JsonFormat.Printer printer = JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace();

    public MesosApiClient(String master) {
        this.master = "http://" + master;
        gson = new GsonBuilder()
                .create();

    }



    private HttpRequest.BodyPublisher ofForm(Map<String,String> params){
        return HttpRequest.BodyPublishers.ofString(params.entrySet().stream().map(k -> k.getKey() + "=" + k.getValue()).collect(Collectors.joining("&")));
    }

    public CompletableFuture<JsonObject> getAgents(){
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(master + "/slaves"))
                .GET()
                .build();

        return HttpClient.newBuilder().build()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    boolean isValidResponse = r.statusCode() >= 200 && r.statusCode() < 300;
                    if(isValidResponse){
                        return r;
                    }else{
                        throw new RuntimeException();
                    }
                })
                .thenApply(HttpResponse::body).thenApply(s -> gson.fromJson(s, JsonObject.class));
    }

    public CompletableFuture<AgentState> getAgentState(String host){
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/state"))
                .GET()
                .build();

        return HttpClient.newBuilder().build()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    boolean isValidResponse = r.statusCode() >= 200 && r.statusCode() < 300;
                    if(isValidResponse){
                        return r;
                    }else{
                        throw new RuntimeException();
                    }
                })
                .thenApply(HttpResponse::body).thenApply(s -> gson.fromJson(s, AgentState.class));
    }

    public CompletableFuture<HttpResponse<String>> destroyVolume(String slaveID, Protos.Offer.Operation destroy){
        Map<String,String> params = new HashMap<>();
        String volumes = "[" + destroy.getDestroy().getVolumesList().stream()
                .filter(Protos.Resource::hasDisk)
                .map(x -> {
                    try {
                        return printer.print(x);
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.joining(",")) + "]";
        params.put("slaveId",slaveID);
        params.put("volumes",volumes);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(master + "/master/destroy-volumes"))
                .POST(ofForm(params))
                .build();

        return HttpClient.newBuilder().build().sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    public CompletableFuture<HttpResponse<String>> unreserve(String slaveID, Protos.Offer.Operation unreserve){
        Map<String,String> params = new HashMap<>();
        String resources = "[" + unreserve.getReserve().getResourcesList().stream().map(x -> {
            try {
                return printer.print(x);
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.joining(",")) + "]";
        params.put("slaveId",slaveID);
        params.put("resources",resources);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(master + "/master/unreserve"))
                .POST(ofForm(params))
                .build();

        return HttpClient.newBuilder().build().sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }



    public static void main(String[] args) {
        MesosApiClient mesosApiClient = new MesosApiClient("192.168.122.41:5050");
        List<String> persistentIds = new ArrayList<>();
        persistentIds.add("c44bcda8-28fd-4f06-bba0-ade0f7c73a67");

        Boolean allPersistence = mesosApiClient.getAgents()
                .thenApply(agents -> agents.getAsJsonArray("slaves"))
                .thenApply(JsonArray::iterator)
                .thenApply(iterator -> Stream.generate(() -> null)
                        .takeWhile(x -> iterator.hasNext())
                        .map(n -> iterator.next())
                        .map(jsonElement -> {
                            String hostname = jsonElement.getAsJsonObject().get("hostname").getAsString();
                            int port = jsonElement.getAsJsonObject().get("port").getAsInt();
                            return "http://" + hostname + ":" + port;
                        }))
                .thenApply(hosts -> hosts.map(mesosApiClient::getAgentState))
                .thenApply(xs -> xs
                        .map(f -> f.thenApply(AgentState::getReservedResources)
                                .thenApply(Map::values)
                                .thenApply(Collection::stream)
                                .thenApply(ys -> ys.flatMap(Arrays::stream))
                                .thenApply(ys -> ys.filter(x -> x.getDisk() != null))
                                .thenApply(ys -> ys.map(AgentState.Resource::getDisk))
                                .thenApply(ys -> ys.filter(x -> x.getPersistence() != null))
                                .thenApply(ys -> ys.map(AgentState.Disk::getPersistence))
                                .thenApply(ys -> ys.filter(x -> x.getId() != null))
                                .thenApply(ys -> ys.map(AgentState.Persistence::getId))
                                .thenApply(ys -> ys.filter(persistentIds::contains)))
                )
                .thenApply(xs -> xs.collect(Collectors.toList()))
                .thenCompose(FutureUtils::all)
                .thenApply(Collection::stream)
                .thenApply(xs -> xs.flatMap(x -> x))
                .thenApply(xs -> xs.noneMatch(persistentIds::contains))
                .join();

        System.out.println(allPersistence);
    }

}
