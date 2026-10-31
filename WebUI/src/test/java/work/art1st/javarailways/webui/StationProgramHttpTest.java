package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class StationProgramHttpTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void authenticatedLibraryRevisionsAndBoundSnapshotsAreIndependent() throws Exception {
        HttpServer bridge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bridge.createContext("/auth/exchange", exchange -> {
            byte[] response = "{\"playerId\":\"player\",\"playerName\":\"Player\",\"worldId\":\"world\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        bridge.start();
        Javalin app = new WebUiApplication(new WebUiConfig(URI.create("http://127.0.0.1:"+bridge.getAddress().getPort()+"/"),"secret",directory,"127.0.0.1",false,0,100000,Duration.ofSeconds(2))).createApp().start(0);
        HttpClient client = HttpClient.newHttpClient();
        String root="http://127.0.0.1:"+app.port();
        String base="/api/v1/worlds/world/station-programs";
        try {
            assertEquals(401, send(client,root,base,"GET",null,null).statusCode());
            HttpResponse<String> login=send(client,root,"/auth/exchange","POST","{\"token\":\"one-time\"}",null);
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            for (String type : java.util.List.of("SPLIT", "COUPLE")) {
                ObjectNode invalid = mapper.createObjectNode().put("id", "formation").put("name", "Formation");
                var definitionNode = invalid.putObject("definition");
                definitionNode.putArray("parameters");
                var operation = definitionNode.putArray("steps").addObject().put("id", "op").put("type", type);
                operation.putObject("eventId").put("value", "event");
                var rejected = send(client, root, base, "POST", invalid.toString(), cookie);
                assertEquals(400, rejected.statusCode());
                assertTrue(rejected.body().contains("unsupported sequential program step: " + type));
                var branch = mapper.createObjectNode().put("id", "branch").put("type", "BRANCH")
                    .put("typeId", "javarailways:plan_duty_id").put("version", 1);
                branch.putObject("parameters").putObject("value").putArray("values").add("1");
                branch.putArray("thenSteps").add(operation.deepCopy());
                branch.putArray("elseSteps");
                definitionNode.withArray("steps").removeAll().add(branch);
                rejected = send(client, root, base, "POST", invalid.toString(), cookie);
                assertEquals(400, rejected.statusCode());
                assertTrue(rejected.body().contains("unsupported sequential program step: " + type));
            }
            JsonNode definition=mapper.readTree("{\"parameters\":[],\"steps\":[{\"id\":\"reverse\",\"type\":\"REVERSE\"},{\"id\":\"action\",\"type\":\"EXTENSION\",\"typeId\":\"demo:action\",\"version\":1,\"parameters\":{\"value\":{\"one\":1.0,\"zero\":-0.0,\"nested\":[null,true,0.25]}}}]}");
            var sourceStep = ((ObjectNode)definition).withArray("steps").addObject().put("id", "info").put("type", "SET_DISPLAY");
            sourceStep.putObject("unitSlots").putArray("value").add(1);
            sourceStep.putObject("values").putObject("trainInfo").put("name", "NAME").put("cars", "CAR_COUNT").put("units", "UNIT_COUNT").put("direction", "DIRECTION");
            ObjectNode create=mapper.createObjectNode().put("id","turnback").put("name","Turnback").put("estimatedDurationTicks",80);create.set("definition",definition);
            var created=send(client,root,base,"POST",create.toString(),cookie);assertEquals(201,created.statusCode());
            JsonNode first=mapper.readTree(created.body());
            assertEquals(1,first.path("revision").asInt());
            assertEquals(80,first.path("estimatedDurationTicks").asInt());
            assertEquals(409,send(client,root,base,"POST",create.toString(),cookie).statusCode());
            assertEquals("[]",send(client,root,"/api/v1/worlds/other/station-programs","GET",null,cookie).body());
            ObjectNode snapshot=mapper.createObjectNode().put("programId","turnback").put("revision",1).put("contentIdentity",first.path("contentIdentity").asText());// Browser JSON parses1.0 and-0.0 then writes1 and0. The server identity must survive.
            snapshot.put("estimatedDurationTicks",80);
            snapshot.set("definition",mapper.readTree(definition.toString().replace(":1.0", ":1").replace(":-0.0", ":0")));
            ObjectNode document=(ObjectNode)mapper.readTree("{\"formatVersion\":1,\"id\":\"weekday\",\"periodDays\":1,\"runs\":[],\"duties\":[],\"diagramLines\":[],\"arrivalOverrides\":[]}");document.putArray("boundPrograms").add(snapshot);
            String documents="/api/v1/worlds/world/editor-documents";
            assertEquals(201,send(client,root,documents,"POST",document.toString(),cookie).statusCode());
            document.put("id","weekend");
            assertEquals(201,send(client,root,documents,"POST",document.toString(),cookie).statusCode());
            document.put("id","weekday");
            ObjectNode update=mapper.createObjectNode().put("expectedRevision",1).put("name","Updated").put("estimatedDurationTicks",140);update.set("definition",mapper.readTree("{\"parameters\":[],\"steps\":[]}"));
            assertEquals(200,send(client,root,base+"/turnback","PUT",update.toString(),cookie).statusCode());
            assertEquals(140,mapper.readTree(send(client,root,base+"/turnback","GET",null,cookie).body()).path("estimatedDurationTicks").asInt());
            assertEquals(409,send(client,root,base+"/turnback","PUT",update.toString(),cookie).statusCode());
            assertEquals(401,send(client,root,base+"/turnback","DELETE","{\"expectedRevision\":2}",null).statusCode());
            assertEquals(409,send(client,root,base+"/turnback","DELETE","{\"expectedRevision\":1}",cookie).statusCode());
            assertEquals(400,send(client,root,base+"/turnback","DELETE","{\"expectedRevision\":\"2\"}",cookie).statusCode());
            assertEquals(400,send(client,root,base+"/turnback","DELETE","{\"expectedRevision\":2,\"extra\":true}",cookie).statusCode());
            for (String invalid : java.util.List.of("{}", "[]", "{\"expectedRevision\":0}", "{\"expectedRevision\":1.5}")) {
                assertEquals(400,send(client,root,base+"/turnback","DELETE",invalid,cookie).statusCode());
            }
            assertEquals(404,send(client,root,"/api/v1/worlds/other/station-programs/turnback","DELETE","{\"expectedRevision\":2}",cookie).statusCode());
            assertEquals(200,send(client,root,base+"/turnback","GET",null,cookie).statusCode());
            var deleted=send(client,root,base+"/turnback","DELETE","{\"expectedRevision\":2}",cookie);
            assertEquals(200,deleted.statusCode());
            assertEquals(mapper.readTree("{\"removed\":true}"),mapper.readTree(deleted.body()));
            assertEquals(404,send(client,root,base+"/turnback","GET",null,cookie).statusCode());
            assertEquals(404,send(client,root,base+"/turnback","DELETE","{\"expectedRevision\":2}",cookie).statusCode());
            assertEquals("[]",send(client,root,base,"GET",null,cookie).body());
            assertFalse(java.nio.file.Files.exists(directory.resolve("station-programs/world/turnback.json")));
            JsonNode loaded=mapper.readTree(send(client,root,documents+"/weekday","GET",null,cookie).body());
            assertEquals(snapshot,loaded.path("boundPrograms").get(0));
            JsonNode second=mapper.readTree(send(client,root,documents+"/weekend","GET",null,cookie).body());
            assertEquals(snapshot,second.path("boundPrograms").get(0));
            assertFalse(loaded.toString().contains("secret"));
            assertEquals(204,send(client,root,documents+"/weekday","PUT",document.toString(),cookie).statusCode());
            create.put("id","bad").put("extra",true);
            assertEquals(400,send(client,root,base,"POST",create.toString(),cookie).statusCode());
            snapshot.put("contentIdentity","sha256:wrong");document.withArray("boundPrograms").removeAll().add(snapshot);
            assertEquals(400,send(client,root,documents+"/weekday","PUT",document.toString(),cookie).statusCode());
        } finally {app.stop();bridge.stop(0);}
    }

    private HttpResponse<String> send(HttpClient client,String root,String path,String method,String body,String cookie) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(root+path)).header("Content-Type","application/json");
        if(cookie!=null)request.header("Cookie",cookie);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void extensionJsonValuesAreFiniteBoundedAndCanonicallyNumeric() throws Exception {
        assertEquals(ProgramContentIdentity.of(mapper.readTree("{\"n\":1,\"z\":0}")), ProgramContentIdentity.of(mapper.readTree("{\"z\":-0.0,\"n\":1.0}")));
        ProgramJsonValidator.object(mapper.readTree("{}"));
        ProgramJsonValidator.object(mapper.readTree("{\"nested\":[true,null,0.25,{\"parameterId\":\"literal\"}]}"));
        for (String bad : java.util.List.of("[]", "null", "{\"n\":9007199254740992}", "{\"n\":-9007199254740993}", "{\"n\":1e309}")) {
            assertThrows(IllegalArgumentException.class, () -> ProgramJsonValidator.object(mapper.readTree(bad)));
        }
        var valid = mapper.createObjectNode();var items = valid.putArray("items");for (int i=0;i<4094;i++)items.addNull();ProgramJsonValidator.object(valid);items.addNull();assertThrows(IllegalArgumentException.class, () -> ProgramJsonValidator.object(valid));
        var strings = mapper.createObjectNode().put("s", "x".repeat(8193));assertThrows(IllegalArgumentException.class, () -> ProgramJsonValidator.object(strings));
    }

    @Test void strictDefinitionRejectsUnknownTypesParametersAndInvalidDurations() throws Exception {
        StationProgramValidator.validate(mapper.readTree("{\"parameters\":[],\"steps\":[{\"id\":\"move\",\"type\":\"MOVE\",\"destinationId\":{\"value\":\"x\"},\"viaIds\":{\"value\":[]}}]}"));
        for(String invalid:java.util.List.of(
            "{\"parameters\":[],\"steps\":[{\"id\":\"wait\",\"type\":\"WAIT_DURATION\",\"durationTicks\":{\"value\":0}}]}",
            "{\"parameters\":[],\"steps\":[{\"id\":\"wait\",\"type\":\"WAIT_UNTIL\",\"tick\":{\"value\":1.0}}]}",
            "{\"parameters\":[],\"steps\":[{\"id\":\"acquire\",\"type\":\"ACQUIRE\"}]}",
            "{\"parameters\":[{\"id\":\"p\",\"type\":\"STRING\"}],\"steps\":[{\"id\":\"move\",\"type\":\"MOVE\",\"destinationId\":{\"parameterId\":\"p\"},\"viaIds\":{\"value\":[]}}]}",
            "{\"parameters\":[],\"steps\":[],\"extra\":true}"
        ))assertThrows(IllegalArgumentException.class,()->StationProgramValidator.validate(mapper.readTree(invalid)));
    }
}
