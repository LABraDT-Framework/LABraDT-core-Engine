package de.labradt.dt_engine.engine.aas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.labradt.dt_engine.engine.model.Rule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Official AAS V3 REST API. Repository is durable; the executor is a runtime cache. */
@Component
public class RepositoryClient {
    public static final String LIVE_ID = "urn:guc:submodel:CPS_SM";
    private final ObjectMapper json = new ObjectMapper();
    private final RestTemplate http;
    @Value("${aas.repository.url:}") private String base = "";
    @Value("${service.execution.url:http://localhost:8082/services/trigger}") private String serviceUrl;

    public RepositoryClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        http = new RestTemplate(factory);
    }
    public boolean enabled() { return !base.isBlank(); }
    public static String encode(String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }
    public JsonNode get(String path) { return http.getForObject(base + path, JsonNode.class); }
    private void write(String path, HttpMethod method, JsonNode body) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        http.exchange(base + path, method, new HttpEntity<>(body, headers), String.class);
    }
    public ObjectNode rulesModel() {
        String cursor = null;
        do {
            JsonNode page = get("/submodels?limit=100" + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            for (JsonNode model : page.path("result")) {
                if ("Rules_SM".equals(model.path("idShort").asText())) return (ObjectNode) model;
            }
            cursor = page.path("paging_metadata").path("cursor").asText(null);
        } while (cursor != null && !cursor.isBlank());
        throw new IllegalStateException("Rules_SM is missing from AAS Repository; restore the seeded submodel.");
    }
    public List<Rule> readRules() {
        List<Rule> rules = new ArrayList<>();
        for (JsonNode element : rulesModel().path("submodelElements")) {
            if (!"SubmodelElementCollection".equals(element.path("modelType").asText())) continue;
            Rule rule = new Rule(); rule.setAttribute("idShort", element.path("idShort").asText());
            for (JsonNode property : element.path("value")) {
                if ("Property".equals(property.path("modelType").asText()))
                    rule.setAttribute(property.path("idShort").asText(), property.path("value").asText());
            }
            if (rule.getCondition() != null) rules.add(rule);
        }
        return rules;
    }
    public void saveRules(List<Rule> rules) {
        ObjectNode model = rulesModel();
        ArrayNode elements = json.createArrayNode();
        // Preserve non-rule collections and metadata, even when rules are edited.
        for (JsonNode old : model.path("submodelElements")) {
            boolean isRule = false;
            for (JsonNode p : old.path("value")) if ("condition".equals(p.path("idShort").asText())) isRule = true;
            if (!isRule) elements.add(old);
        }
        for (Rule rule : rules) {
            ObjectNode collection = null;
            for (JsonNode old : model.path("submodelElements"))
                if (rule.getIdShort().equals(old.path("idShort").asText())) collection = old.deepCopy();
            if (collection == null) collection = json.createObjectNode().put("idShort", rule.getIdShort()).put("modelType", "SubmodelElementCollection");
            ArrayNode props = json.createArrayNode();
            for (Map.Entry<String,String> attr : rule.getAttributes().entrySet()) {
                if (attr.getKey().equals("idShort")) continue;
                ObjectNode prop = null;
                for (JsonNode old : collection.path("value"))
                    if (attr.getKey().equals(old.path("idShort").asText()) && "Property".equals(old.path("modelType").asText())) prop = old.deepCopy();
                if (prop == null) prop = json.createObjectNode().put("idShort", attr.getKey()).put("modelType", "Property").put("valueType", "xs:string");
                prop.put("value", attr.getValue()); props.add(prop);
            }
            for (JsonNode old : collection.path("value"))
                if (!"Property".equals(old.path("modelType").asText())) props.add(old);
            collection.set("value", props); elements.add(collection);
        }
        model.set("submodelElements", elements);
        write("/submodels/" + encode(model.path("id").asText()), HttpMethod.PUT, model);
    }
    public JsonNode serviceRegistry() {
        return http.getForObject(serviceUrl.replaceFirst("/services/trigger$", "/registry/services"), JsonNode.class);
    }
    public void validateExecutionTargets(List<Rule> rules, String dataSubmodel) {
        JsonNode registry = serviceRegistry();
        Set<String> services = new HashSet<>();
        Map<String,JsonNode> definitions=new HashMap<>();
        for (JsonNode service : registry) {String id=service.path("serviceId").asText(service.path("id").asText());services.add(id);definitions.put(id,service);}
        JsonNode live = get("/submodels/" + encode(LIVE_ID));
        Set<String> properties = new HashSet<>();
        for (JsonNode element : live.path("submodelElements")) if ("Property".equals(element.path("modelType").asText())) properties.add(element.path("idShort").asText());
        for (Rule rule : rules) {
            if (!dataSubmodel.equals(rule.getTargetSubmodel())) throw new IllegalArgumentException("This Engine instance only evaluates the " + dataSubmodel + " telemetry channel");
            if (rule.getTargetElement() != null && !rule.getTargetElement().isBlank() && !properties.contains(rule.getTargetElement())) throw new IllegalArgumentException("Target property is not present in the live telemetry model");
            for (String action : rule.getActions()) if (!services.contains(action)) throw new IllegalArgumentException("Service is not registered: " + action);
            JsonNode parameters=RuleValidator.parameters(rule);
            parameters.fields().forEachRemaining(entry->{
                JsonNode value=entry.getValue();
                if(value.isObject() && !properties.contains(value.path("input").asText())) throw new IllegalArgumentException("Mapped parameter input does not exist: "+value.path("input").asText());
                for(String action:rule.getActions()) if(!definitions.get(action).path("defaultArgs").has(entry.getKey())) throw new IllegalArgumentException("Service parameter is not declared in defaultArgs: "+action+" / "+entry.getKey());
            });
            if ("wear_average".equals(rule.getCondition())) for (String input : rule.getAttribute("inputs").split(","))
                if (!properties.contains(input.trim())) throw new IllegalArgumentException("Input property does not exist: " + input);
        }
    }
    public void ensureLiveModel(String idShort) {
        try { get("/submodels/" + encode(LIVE_ID)); }
        catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
            ObjectNode model = json.createObjectNode().put("id", LIVE_ID).put("idShort", idShort).put("modelType", "Submodel");
            model.set("submodelElements", json.createArrayNode()); write("/submodels", HttpMethod.POST, model);
        }
        // Attach the live submodel to the seeded machine shell, without dropping its references.
        JsonNode page = get("/shells?limit=100");
        for (JsonNode item : page.path("result")) {
            boolean ownsRules = false, hasLive = false;
            String rulesId = rulesModel().path("id").asText();
            for (JsonNode reference : item.path("submodels")) for (JsonNode key : reference.path("keys")) {
                ownsRules |= rulesId.equals(key.path("value").asText());
                hasLive |= LIVE_ID.equals(key.path("value").asText());
            }
            if (!ownsRules || hasLive) continue;
            ObjectNode shell = item.deepCopy();
            ArrayNode refs = shell.has("submodels") ? (ArrayNode) shell.get("submodels") : json.createArrayNode();
            ObjectNode ref = json.createObjectNode().put("type", "ModelReference");
            ref.set("keys", json.createArrayNode().add(json.createObjectNode().put("type", "Submodel").put("value", LIVE_ID)));
            refs.add(ref); shell.set("submodels", refs);
            write("/shells/" + encode(shell.path("id").asText()), HttpMethod.PUT, shell);
        }
    }
    /** Coalesced top-level telemetry snapshot; not a replacement for general submodel CRUD. */
    public synchronized void mirrorLiveValues(Map<String,String> values) {
        if (values.isEmpty()) return;
        ObjectNode model = (ObjectNode) get("/submodels/" + encode(LIVE_ID));
        ArrayNode elements = model.has("submodelElements") ? (ArrayNode) model.get("submodelElements") : json.createArrayNode();
        for (Map.Entry<String,String> entry : values.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]*")) continue;
            ObjectNode property = null;
            for (JsonNode element : elements) if (entry.getKey().equals(element.path("idShort").asText()) && "Property".equals(element.path("modelType").asText())) property = (ObjectNode) element;
            if (property == null) { property = json.createObjectNode().put("modelType", "Property").put("idShort", entry.getKey()).put("valueType", "xs:string"); elements.add(property); }
            String type = "xs:string";
            try { if (Double.isFinite(Double.parseDouble(entry.getValue()))) type = "xs:double"; } catch (NumberFormatException ignored) {}
            if (Set.of("true", "false").contains(entry.getValue())) type = "xs:boolean";
            property.put("valueType", type);
            property.put("value", entry.getValue());
        }
        model.set("submodelElements", elements); write("/submodels/" + encode(LIVE_ID), HttpMethod.PUT, model);
    }
}
