import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Test-only loopback Stripe mock and proxy-free API transport. No raw request logging. */
public class Phase5Agent {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Map<String,Integer> PORTS = Map.of("gateway",19080,"auth",19081,"product",19082,
            "order",19083,"inventory",19084,"payment",19085,"discovery",19761,"mock",18086);
    static final Path STATE = Path.of(System.getProperty("phase5.state","/state/mock.json"));
    static Map<String,Map<String,Object>> intents = new LinkedHashMap<>();
    static boolean probed;
    static String env(String key) { String v=System.getenv(key); if(v==null||v.isBlank())throw new IllegalStateException("Missing test configuration");return v; }
    static String derived(String label) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(env("PHASE5_RUN_KEY").getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(label.getBytes(StandardCharsets.UTF_8)));
    }
    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector(){ public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                    public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure){} }).build();
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object v) { return (Map<String,Object>)v; }
    static synchronized void persist() throws Exception {
        Path temporary=STATE.resolveSibling("mock.tmp");
        byte[] data=JSON.writeValueAsBytes(intents);
        Files.write(temporary,data,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(temporary,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
        Files.move(temporary,STATE,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static Map<String,Object> publicIntent(Map<String,Object> value) throws Exception {
        var copy=new LinkedHashMap<>(value);
        copy.put("client_secret","phase5_client_"+derived("client:"+value.get("id")));
        return copy;
    }
    static Map<String,String> form(String body) {
        var result=new LinkedHashMap<String,String>();
        for(String part:body.split("&")){String[] pair=part.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}
        return result;
    }
    static synchronized Object handle(String method,String path,Map<String,List<String>> headers,byte[] body) throws Exception {
        if(path.equals("/__state"))return Map.of("run",env("PHASE5_RUN_ID"),"probed",probed,"intents",intents);
        if(path.equals("/__control")) {
            if(!Objects.equals(headers.getOrDefault("X-phase5-control",List.of("")).getFirst(),derived("control")))throw new SecurityException();
            var command=JSON.readValue(body,new TypeReference<Map<String,Object>>(){});
            String id=Objects.toString(command.get("id"));
            var intent=intents.values().stream().filter(p->id.equals(p.get("id"))).findFirst().orElseThrow();
            // This test accepts only success. Unresolved intents are never force-released.
            if(!"succeeded".equals(command.get("status")))throw new SecurityException();
            intent.put("status","succeeded");intent.put("amount_received",intent.get("amount"));persist();return intent;
        }
        String auth=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Authorization")).map(e->e.getValue().getFirst()).findFirst().orElse("");
        if(!auth.equals("Bearer phase5_mock_"+derived("stripe")))throw new SecurityException();
        if(method.equals("GET")&&path.equals("/v1/payment_intents/phase5_probe")) {
            probed=true;
            return Map.of("id","phase5_probe","object","payment_intent","amount",100,"currency","inr",
                    "status","requires_payment_method","metadata",Map.of("run",env("PHASE5_RUN_ID")));
        }
        if(method.equals("POST")&&path.equals("/v1/payment_intents")) {
            var fields=form(new String(body,StandardCharsets.UTF_8));
            String key=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Idempotency-Key")).map(e->e.getValue().getFirst()).findFirst().orElseThrow();
            var metadata=new LinkedHashMap<String,String>();
            for(var entry:fields.entrySet())if(entry.getKey().startsWith("metadata["))metadata.put(entry.getKey().substring(9,entry.getKey().length()-1),entry.getValue());
            if(!metadata.keySet().equals(Set.of("paymentId","orderId","userId")))throw new SecurityException();
            for(String field:List.of("paymentId","orderId","userId"))UUID.fromString(metadata.get(field));
            if(intents.containsKey(key)) {
                var old=intents.get(key);
                if(((Number)old.get("amount")).longValue()!=Long.parseLong(fields.get("amount")) || !old.get("currency").equals(fields.get("currency")) || !old.get("metadata").equals(metadata))throw new SecurityException();
            } else {
                var value=new LinkedHashMap<String,Object>();
                value.put("id","pi_"+UUID.randomUUID().toString().replace("-",""));value.put("object","payment_intent");
                value.put("amount",Long.parseLong(fields.get("amount")));value.put("amount_received",0);
                value.put("currency",fields.get("currency"));value.put("metadata",metadata);value.put("status","requires_payment_method");
                intents.put(key,value);persist();
            }
            return publicIntent(intents.get(key));
        }
        if(method.equals("GET")&&path.startsWith("/v1/payment_intents/")) {
            String id=path.substring("/v1/payment_intents/".length());
            return publicIntent(intents.values().stream().filter(p->id.equals(p.get("id"))).findFirst().orElseThrow());
        }
        throw new NoSuchElementException();
    }
    static void server() throws Exception {
        if(Files.exists(STATE))intents=JSON.readValue(Files.readAllBytes(STATE),new TypeReference<LinkedHashMap<String,Map<String,Object>>>(){});
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",18086),0);
        server.createContext("/",exchange->{
            int status=200;byte[] data;
            try {data=JSON.writeValueAsBytes(handle(exchange.getRequestMethod(),exchange.getRequestURI().getPath(),exchange.getRequestHeaders(),exchange.getRequestBody().readAllBytes()));}
            catch(SecurityException denied){status=403;data="{\"error\":\"denied\"}".getBytes(StandardCharsets.UTF_8);}
            catch(NoSuchElementException absent){status=404;data="{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8);}
            catch(Exception invalid){status=400;data="{\"error\":\"invalid_test_request\"}".getBytes(StandardCharsets.UTF_8);}
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,data.length);
            try(var stream=exchange.getResponseBody()){stream.write(data);}exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));server.start();
    }
    static void rpc() throws Exception {
        var request=JSON.readValue(System.in,new TypeReference<Map<String,Object>>(){});
        String service=Objects.toString(request.get("service")),path=Objects.toString(request.get("path"));
        if(!PORTS.containsKey(service)||!path.startsWith("/")||path.startsWith("//")||path.contains("#")||path.contains("?"))throw new SecurityException();
        URI uri=new URI("http",null,"127.0.0.1",PORTS.get(service),path,null,null);
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        Map<String,Object> headers=map(request.getOrDefault("headers",Map.of()));
        Set<String> allowed=Set.of("Authorization","Idempotency-Key","Stripe-Signature","X-User-Id");
        for(var e:headers.entrySet()){if(!allowed.contains(e.getKey()))throw new SecurityException();builder.header(e.getKey(),Objects.toString(e.getValue()));}
        if(service.equals("mock")&&path.equals("/__control"))builder.header("X-Phase5-Control",derived("control"));
        String method=Objects.toString(request.getOrDefault("method","GET"));
        byte[] body=request.containsKey("rawBody") ? Objects.toString(request.get("rawBody")).getBytes(StandardCharsets.UTF_8) : JSON.writeValueAsBytes(request.getOrDefault("body",Map.of()));
        builder.method(method,method.equals("GET")?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var response=client().send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
        Object content;
        try{content=JSON.readValue(response.body(),Object.class);}catch(Exception text){content=new String(response.body(),StandardCharsets.UTF_8);}
        // This stdout is an RPC channel captured exclusively in the parent process's
        // memory. Never attach this invocation's stdout to a log or terminal.
        System.out.write(JSON.writeValueAsBytes(Map.of("status",response.statusCode(),"headers",response.headers().map(),"body",content)));
    }
    public static void main(String[] args) {
        try {
            if(args.length!=1)throw new IllegalArgumentException();
            if(args[0].equals("server"))server();
            else if(args[0].equals("rpc"))rpc();
            else throw new IllegalArgumentException();
        } catch(Exception failure){System.err.println("PHASE5_AGENT_FAILED");System.exit(1);}
    }
}
