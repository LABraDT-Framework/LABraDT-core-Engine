package de.labradt.dt_engine;

import de.labradt.dt_engine.engine.StateChartExecutor;
import de.labradt.dt_engine.engine.model.Rule;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic unit tests for the write-back safety check
 * (StateChartExecutor.isCorrectionWithinBounds).
 *
 * Rationale: the corrective value a service returns is PATCHed back to the
 * machine, so it must be validated before the write. The check derives safe
 * bounds from the INVERSE of the rule that fired (Approach A): the rule states
 * what counts as a problem, so a valid correction is one that no longer meets
 * that condition. These tests pin that behavior down deterministically, without
 * the HTTP/timing fragility of driving the full state machine end to end.
 *
 * The method under test is a pure function of (rule, correctedValue): it does
 * not touch engine state, storage, or the gateway, so a plain instance with no
 * Spring context is sufficient.
 */
public class StateChartExecutorTest {

    /** Builds a rule carrying just the condition and threshold under test. */
    private Rule rule(String condition, String threshold) {
        Rule r = new Rule();
        r.setAttribute("idShort", "TestRule");
        r.setAttribute("condition", condition);
        r.setAttribute("threshold", threshold);
        return r;
    }

    private final StateChartExecutor executor = new StateChartExecutor();

    // ── greater_than: a valid correction must be <= threshold ──────────────

    @Test
    public void greaterThan_correctionAtOrBelowThreshold_isAccepted() {
        Rule r = rule("greater_than", "80.0");
        assertTrue("72.0 is safely below 80.0", executor.isCorrectionWithinBounds(r, "72.0"));
        assertTrue("80.0 exactly at threshold is acceptable",
            executor.isCorrectionWithinBounds(r, "80.0"));
    }

    @Test
    public void greaterThan_correctionStillAboveThreshold_isRejected() {
        Rule r = rule("greater_than", "80.0");
        // The classic failure: service "corrected" an over-temperature to an
        // even more extreme value. Must NOT be written back.
        assertFalse("200.0 still exceeds 80.0 — unsafe",
            executor.isCorrectionWithinBounds(r, "200.0"));
        assertFalse("80.1 is still above threshold",
            executor.isCorrectionWithinBounds(r, "80.1"));
    }

    // ── less_than: a valid correction must be >= threshold ─────────────────

    @Test
    public void lessThan_correctionAtOrAboveThreshold_isAccepted() {
        Rule r = rule("less_than", "10.0");
        assertTrue(executor.isCorrectionWithinBounds(r, "10.0"));
        assertTrue(executor.isCorrectionWithinBounds(r, "15.0"));
    }

    @Test
    public void lessThan_correctionStillBelowThreshold_isRejected() {
        Rule r = rule("less_than", "10.0");
        assertFalse("5.0 still below 10.0 — unsafe",
            executor.isCorrectionWithinBounds(r, "5.0"));
    }

    // ── out_of_bounds: corrected x,y,z must all be back inside the bounds ──

    @Test
    public void outOfBounds_correctionInsideAllBounds_isAccepted() {
        // bounds: xMin,xMax,yMin,yMax,zMin,zMax
        Rule r = rule("out_of_bounds", "0,10,0,10,0,10");
        assertTrue(executor.isCorrectionWithinBounds(r, "5,5,5"));
        assertTrue("edges are inclusive", executor.isCorrectionWithinBounds(r, "0,10,0"));
    }

    @Test
    public void outOfBounds_correctionOutsideAnyAxis_isRejected() {
        Rule r = rule("out_of_bounds", "0,10,0,10,0,10");
        assertFalse("x out of range", executor.isCorrectionWithinBounds(r, "11,5,5"));
        assertFalse("z out of range", executor.isCorrectionWithinBounds(r, "5,5,-1"));
    }

    @Test
    public void outOfBounds_malformedCorrection_isRejected() {
        Rule r = rule("out_of_bounds", "0,10,0,10,0,10");
        assertFalse("too few components cannot be shown safe",
            executor.isCorrectionWithinBounds(r, "5,5"));
    }

    // ── non-numeric correction for a numeric rule is rejected ──────────────

    @Test
    public void numericRule_nonNumericCorrection_isRejected() {
        Rule r = rule("greater_than", "80.0");
        assertFalse("non-numeric cannot be shown safe",
            executor.isCorrectionWithinBounds(r, "hot"));
    }

    // ── conditions with no numeric inverse are accepted as-is ──────────────

    @Test
    public void equalsCondition_hasNoNumericInverse_acceptedAsIs() {
        Rule r = rule("equals", "OPEN");
        assertTrue(executor.isCorrectionWithinBounds(r, "anything"));
    }

    @Test
    public void unknownCondition_acceptedAsIs() {
        Rule r = rule("wear_average", "15.0");
        // No inverse defined for a custom multi-input operator; not blocked here.
        assertTrue(executor.isCorrectionWithinBounds(r, "3.2"));
    }

    @Test
    public void nullCondition_acceptedAsIs() {
        Rule r = new Rule();
        r.setAttribute("idShort", "NoCondRule");
        assertTrue("a rule with no condition has nothing to validate against",
            executor.isCorrectionWithinBounds(r, "123"));
    }
}