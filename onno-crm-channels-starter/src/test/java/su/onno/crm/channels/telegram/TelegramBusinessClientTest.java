package su.onno.crm.channels.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TelegramBusinessClientTest {
    @Test void requestsBusinessEventsAndPreservesConnectionOnTextAndFiles() throws Exception {
        var json=new ObjectMapper();Map<String,String> bodies=new ConcurrentHashMap<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String method=exchange.getRequestURI().getPath().substring("/bottest/".length());
            bodies.put(method,new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String result=method.equals("getUpdates")?"[{\"update_id\":5,\"business_connection\":{\"id\":\"bc\"}}]":"{\"message_id\":12}";
            byte[] response=("{\"ok\":true,\"result\":"+result+"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var client=new TelegramClient("http://127.0.0.1:"+server.getAddress().getPort(),"test",json);
            var updates=client.updates(4);
            assertThat(updates.getFirst().payload().at("/business_connection/id").asText()).isEqualTo("bc");
            assertThat(json.readTree(bodies.get("getUpdates")).path("allowed_updates").toString())
                    .contains("business_connection","business_message","edited_business_message","deleted_business_messages","message");
            client.send(123,"Reply","bc");
            assertThat(json.readTree(bodies.get("sendMessage")).path("business_connection_id").asText()).isEqualTo("bc");
            client.sendFile(123,"quote.pdf","application/pdf",new byte[]{1,2,3},"Quote","bc");
            assertThat(bodies.get("sendDocument")).contains("name=\"business_connection_id\"\r\n\r\nbc");
            client.send(123,"Regular");
            assertThat(json.readTree(bodies.get("sendMessage")).has("business_connection_id")).isFalse();
        } finally {server.stop(0);}
    }
}
