import java.util.*;
import java.nio.file.*;

/** Direct unit calls only: never starts an HTTP server, Spring app or container. */
public class Phase5AgentTest {
    static int checks;
    static void check(boolean value) {checks++;if(!value)throw new IllegalStateException("UNIT_CHECK_FAILED");}
    static void rejects(String uri) throws Exception {
        System.setProperty("phase3.stripe-mock-url",uri);
        try {com.orderflow.payment.integration.PaymentIntegrationApplication.main(new String[0]);throw new IllegalStateException("INVALID_URI_ACCEPTED");}
        catch(IllegalArgumentException expected){checks++;}
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        rejects("https://127.0.0.1:18086");rejects("http://api.stripe.com:18086");rejects("http://127.0.0.1:1234");
        rejects("http://user@127.0.0.1:18086");rejects("http://127.0.0.1:18086?override=1");rejects("http://127.0.0.1:18086/other");
        var headers=new LinkedHashMap<String,List<String>>();headers.put("Authorization",List.of("Bearer phase5_mock_"+Phase5Agent.derived("stripe")));
        Object probe=Phase5Agent.handle("GET","/v1/payment_intents/phase5_probe",headers,new byte[0]);
        check(Phase5Agent.probed);check(((Map<String,Object>)probe).get("id").equals("phase5_probe"));
        try{Phase5Agent.handle("GET","/v1/payment_intents/phase5_probe",Map.of(),new byte[0]);throw new IllegalStateException();}catch(SecurityException expected){checks++;}
        String user="22222222-2222-4222-8222-222222222222",order="44444444-4444-4444-8444-444444444444",payment="77777777-7777-4777-8777-777777777777";
        String request="amount=4500&currency=inr&metadata%5BuserId%5D="+user+"&metadata%5BorderId%5D="+order+"&metadata%5BpaymentId%5D="+payment;
        headers.put("Idempotency-Key",List.of("unit-attempt"));
        var first=(Map<String,Object>)Phase5Agent.handle("POST","/v1/payment_intents",headers,request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var again=(Map<String,Object>)Phase5Agent.handle("POST","/v1/payment_intents",headers,request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check(first.get("id").equals(again.get("id")));check(Phase5Agent.intents.size()==1);
        check(!Files.readString(Phase5Agent.STATE).contains("client_secret"));check(!Files.readString(Phase5Agent.STATE).contains("phase5_mock_"));
        try{Phase5Agent.handle("POST","/v1/payment_intents",headers,request.replace("4500","4501").getBytes());throw new IllegalStateException();}catch(SecurityException expected){checks++;}
        var control=Map.of("X-phase5-control",List.of(Phase5Agent.derived("control")));
        var settled=(Map<String,Object>)Phase5Agent.handle("POST","/__control",control,Phase5Agent.JSON.writeValueAsBytes(Map.of("id",first.get("id"),"status","succeeded")));
        check(settled.get("status").equals("succeeded"));check(((Number)settled.get("amount_received")).longValue()==4500);
        Phase5Agent.intents=Phase5Agent.JSON.readValue(Files.readAllBytes(Phase5Agent.STATE),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Map<String,Object>>>(){});
        check(Phase5Agent.intents.get("unit-attempt").get("status").equals("succeeded"));
        System.out.println("PHASE5_AGENT_UNIT_OK checks="+checks+" (no sockets or application fixtures)");
    }
}
