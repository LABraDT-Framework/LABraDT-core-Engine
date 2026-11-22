package de.labradt.dt_engine;
import de.labradt.dt_engine.engine.aas.RuleValidator;
import de.labradt.dt_engine.engine.model.Rule;
import org.junit.Test;
import java.util.List;

public class RuleValidatorTest {
    private Rule valid() {
        Rule rule = new Rule();
        rule.setAttribute("idShort", "Rule_Test"); rule.setAttribute("targetSubmodel", "CPS_SM");
        rule.setAttribute("targetElement", "Temperature"); rule.setAttribute("condition", "greater_than");
        rule.setAttribute("threshold", "90"); rule.setAttribute("action", "cooling-service");
        rule.setAttribute("cooldownMs", "1000"); rule.setAttribute("enabled", "false"); return rule;
    }
    @Test public void validDisabledRule() { RuleValidator.validate(List.of(valid())); }
    @Test(expected=IllegalArgumentException.class) public void nonFiniteThreshold() { Rule r = valid(); r.setAttribute("threshold", "NaN"); RuleValidator.validate(List.of(r)); }
    @Test(expected=IllegalArgumentException.class) public void duplicateIdentity() { RuleValidator.validate(List.of(valid(), valid())); }
    @Test(expected=IllegalArgumentException.class) public void negativeCooldown() { Rule r = valid(); r.setAttribute("cooldownMs", "-1"); RuleValidator.validate(List.of(r)); }
    @Test(expected=IllegalArgumentException.class) public void invertedBounds() { Rule r = valid(); r.setAttribute("condition", "out_of_bounds"); r.setAttribute("threshold", "1,0,-1,1,-1,1"); RuleValidator.validate(List.of(r)); }
    @Test(expected=IllegalArgumentException.class) public void unknownOperator() { Rule r = valid(); r.setAttribute("condition", "invented"); RuleValidator.validate(List.of(r)); }
}
