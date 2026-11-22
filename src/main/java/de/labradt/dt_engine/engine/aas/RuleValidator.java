package de.labradt.dt_engine.engine.aas;

import de.labradt.dt_engine.engine.model.Rule;
import java.util.*;
import com.fasterxml.jackson.databind.*;

public final class RuleValidator {
    private RuleValidator() {}
    public static void validate(List<Rule> rules) {
        Set<String> ids = new HashSet<>();
        for (Rule rule : rules) {
            String id = rule.getIdShort();
            require(id != null && id.matches("[A-Za-z][A-Za-z0-9_]{0,127}") && ids.add(id), "Rule idShort must be valid and unique");
            for (String key : rule.getAttributes().keySet()) require(key.matches("[A-Za-z][A-Za-z0-9_]{0,127}"), "Invalid rule property idShort");
            require(rule.getTargetSubmodel() != null && rule.getTargetSubmodel().matches("[A-Za-z][A-Za-z0-9_]*"), "A valid targetSubmodel is required");
            require(Set.of("greater_than", "less_than", "equals", "out_of_bounds", "wear_average").contains(Objects.toString(rule.getCondition(), "")), "Unknown condition operator");
            require(rule.getThreshold() != null && !rule.getThreshold().isBlank(), "Threshold is required");
            require(!rule.getActions().isEmpty(), "At least one Service action is required");
            require(rule.getTargetElement() != null && !rule.getTargetElement().isBlank() || "wear_average".equals(rule.getCondition()), "targetElement is required");
            String cooldown = rule.getAttribute("cooldownMs");
            if (cooldown != null) { try { require(Long.parseLong(cooldown) >= 0, "cooldownMs must be non-negative"); } catch (NumberFormatException e) { throw new IllegalArgumentException("cooldownMs must be a non-negative integer"); } }
            String enabled = rule.getAttribute("enabled");
            require(enabled == null || Set.of("true", "false").contains(enabled), "enabled must be true or false");
            if ("out_of_bounds".equals(rule.getCondition())) {
                String[] bounds = rule.getThreshold().split(","); require(bounds.length == 6, "out_of_bounds requires six bounds");
                double[] numbers = Arrays.stream(bounds).mapToDouble(RuleValidator::number).toArray();
                for (int i = 0; i < 6; i += 2) require(numbers[i] <= numbers[i+1], "Minimum bound exceeds maximum");
            } else if (!"equals".equals(rule.getCondition())) number(rule.getThreshold());
            if ("wear_average".equals(rule.getCondition())) require(rule.getAttribute("inputs") != null && rule.getAttribute("inputs").split(",").length >= 3, "wear_average requires at least three inputs");
            parameters(rule);
        }
    }
    public static JsonNode parameters(Rule rule) {
        try {
            JsonNode map=new ObjectMapper().readTree(Objects.toString(rule.getAttribute("parameters"),"{}"));
            require(map!=null && map.isObject(),"parameters must be a JSON object");
            map.fields().forEachRemaining(entry->{
                require(entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,127}") && !Set.of("callbackUrl","submodelId","elementId","testContent").contains(entry.getKey()),"Invalid/reserved Service parameter");
                JsonNode value=entry.getValue();
                require(value.isValueNode() && !value.isNull() || value.isObject() && value.size()==1 && value.path("input").isTextual() && value.path("input").asText().matches("[A-Za-z][A-Za-z0-9_]*"),"Parameter must be a literal primitive or {\"input\":\"Property\"}");
            });return map;
        }catch(IllegalArgumentException error){throw error;}
        catch(Exception error){throw new IllegalArgumentException("Invalid parameters JSON",error);}
    }
    private static double number(String value) {
        try { double number = Double.parseDouble(value.trim()); require(Double.isFinite(number), "Threshold must be finite"); return number; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid numeric threshold"); }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
