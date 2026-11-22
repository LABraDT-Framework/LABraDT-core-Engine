package de.labradt.dt_engine.engine.aas;

import de.labradt.dt_engine.engine.model.StateAction;
import de.labradt.dt_engine.engine.model.StateChartDefinition;
import de.labradt.dt_engine.engine.model.StateDefinition;
import de.labradt.dt_engine.engine.model.TransitionDefinition;
import lombok.extern.slf4j.Slf4j;
import de.labradt.dt_engine.engine.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.InputStream;
import java.util.*;

/**
 * Reads the state chart submodel from the AAS XML
 * and builds a StateChartDefinition object in memory.
 * Called once during INITIALIZING.
 */
@Slf4j
@Component
public class StateChartLoader {

    private static final String AAS_NS = "https://admin-shell.io/aas/3/0";

    /**
     * idShort of the submodel containing the state chart's States and
     * Transitions collections. Defaults to "StateChart_SM" but is
     * configurable through engine.statechart.submodel.idShort so an
     * AASX can rename this container without a code change. As with the
     * rules submodel, this only renames which single submodel plays the
     * role. The engine still drives exactly one state machine per
     * instance.
     *
     * The field initializer below matches the @Value default so a plain
     * "new StateChartLoader()" (as the test suite does, without a Spring
     * context) still gets "StateChart_SM" instead of null.
     */
    @Value("${engine.statechart.submodel.idShort:StateChart_SM}")
    private String stateChartSubmodelIdShort = "StateChart_SM";

    public StateChartDefinition loadStateChart(InputStream xmlStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(xmlStream);

        Element stateChartSM = findSubmodelByIdShort(doc, stateChartSubmodelIdShort);
        if (stateChartSM == null) {
            throw new IllegalStateException(
                "[StateChartLoader] " + stateChartSubmodelIdShort + " not found in XML");
        }

        Element statesCollection = findCollectionByIdShort(stateChartSM, "States");
        Element transitionsCollection = findCollectionByIdShort(stateChartSM, "Transitions");

        if (statesCollection == null || transitionsCollection == null) {
            throw new IllegalStateException(
                "[StateChartLoader] States or Transitions collection not found");
        }

        List<StateDefinition> states = parseStates(statesCollection);
        List<TransitionDefinition> transitions = parseTransitions(transitionsCollection);

        String initialState = states.stream()
            .filter(StateDefinition::isInitial)
            .map(StateDefinition::getName)
            .findFirst()
            .orElseThrow(() ->
                new IllegalStateException("[StateChartLoader] No initial state found"));

        StateChartDefinition definition = new StateChartDefinition();
        definition.setStates(states);
        definition.setTransitions(transitions);
        definition.setInitialState(initialState);

        log.info("[StateChartLoader] Loaded {} states, {} transitions. Initial: {}",
            states.size(), transitions.size(), initialState);

        return definition;
    }

    private List<StateDefinition> parseStates(Element statesCollection) {
        List<StateDefinition> states = new ArrayList<>();
        Element statesValue = getValueElement(statesCollection);
        if (statesValue == null) return states;

        NodeList children = statesValue.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element el = (Element) children.item(i);
            if (!"submodelElementCollection".equals(el.getLocalName())) continue;

            StateDefinition state = new StateDefinition();

            // name comes from the idShort direct child
            NodeList ids = el.getElementsByTagNameNS(AAS_NS, "idShort");
            if (ids.getLength() > 0) {
                state.setName(ids.item(0).getTextContent().trim());
            }

            // isInitial lives inside <value>
            state.setInitial(Boolean.parseBoolean(
                getDirectPropertyValue(el, "isInitial")));

            Element entryEl = findDirectCollectionByIdShort(el, "entryAction");
            Element exitEl  = findDirectCollectionByIdShort(el, "exitAction");

            if (entryEl != null) state.setEntryAction(parseAction(entryEl));
            if (exitEl  != null) state.setExitAction(parseAction(exitEl));

            states.add(state);
            log.info("[StateChartLoader] State: {} (initial={})",
                state.getName(), state.isInitial());
        }

        return states;
    }

    private List<TransitionDefinition> parseTransitions(Element transitionsCollection) {
        List<TransitionDefinition> transitions = new ArrayList<>();
        Element transitionsValue = getValueElement(transitionsCollection);
        if (transitionsValue == null) return transitions;

        NodeList children = transitionsValue.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element el = (Element) children.item(i);
            if (!"submodelElementCollection".equals(el.getLocalName())) continue;

            TransitionDefinition t = new TransitionDefinition();
            t.setFrom(getDirectPropertyValue(el, "from"));
            t.setTo(getDirectPropertyValue(el, "to"));
            t.setTrigger(getDirectPropertyValue(el, "trigger"));
            t.setGuard(getDirectPropertyValue(el, "guard"));

            String actionType = getDirectPropertyValue(el, "actionType");
            if (actionType != null && !actionType.equals("NONE")) {
                StateAction action = new StateAction();
                action.setActionType(actionType);
                action.setParams(parseDirectParams(el));
                t.setAction(action);
            }

            transitions.add(t);
            log.info("[StateChartLoader] Transition: {} --{}--> {}",
                t.getFrom(), t.getTrigger(), t.getTo());
        }

        return transitions;
    }

    private StateAction parseAction(Element actionEl) {
        StateAction action = new StateAction();
        action.setActionType(getDirectPropertyValue(actionEl, "actionType"));
        action.setComponent(getDirectPropertyValue(actionEl, "component"));
        action.setParams(parseDirectParams(actionEl));
        return action;
    }

    /**
     * Gets a property value by looking inside the <value> child of parent,
     * then searching its direct property children only.
     */
    private String getDirectPropertyValue(Element parent, String propertyIdShort) {
        Element valueEl = getValueElement(parent);
        if (valueEl == null) return null;

        NodeList children = valueEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            if (!"property".equals(child.getLocalName())) continue;
            NodeList ids = child.getElementsByTagNameNS(AAS_NS, "idShort");
            if (ids.getLength() > 0 &&
                ids.item(0).getTextContent().trim().equals(propertyIdShort)) {
                NodeList values = child.getElementsByTagNameNS(AAS_NS, "value");
                return values.getLength() > 0 ?
                    values.item(0).getTextContent().trim() : "";
            }
        }
        return null;
    }

    /**
     * Parses only direct property children inside <value> into a params map.
     */
    private Map<String, String> parseDirectParams(Element el) {
        Map<String, String> params = new HashMap<>();
        Element valueEl = getValueElement(el);
        if (valueEl == null) return params;

        NodeList children = valueEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            if (!"property".equals(child.getLocalName())) continue;
            String idShort = getChildText(child, "idShort");
            String value   = getChildText(child, "value");
            if (idShort != null && !idShort.isEmpty()) {
                params.put(idShort, value);
            }
        }
        return params;
    }

    /**
     * Finds a submodelElementCollection by idShort inside the <value>
     * child of parent, one level deep only.
     */
    private Element findDirectCollectionByIdShort(Element parent, String idShort) {
        Element valueEl = getValueElement(parent);
        if (valueEl == null) return null;

        NodeList children = valueEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            if (!"submodelElementCollection".equals(child.getLocalName())) continue;
            NodeList ids = child.getElementsByTagNameNS(AAS_NS, "idShort");
            if (ids.getLength() > 0 &&
                ids.item(0).getTextContent().trim().equals(idShort)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Gets the <value> direct child element of parent.
     */
    private Element getValueElement(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            if ("value".equals(child.getLocalName())) return child;
        }
        return null;
    }

    private Element findSubmodelByIdShort(Document doc, String idShort) {
        NodeList submodels = doc.getElementsByTagNameNS(AAS_NS, "submodel");
        for (int i = 0; i < submodels.getLength(); i++) {
            Element sm = (Element) submodels.item(i);
            NodeList ids = sm.getElementsByTagNameNS(AAS_NS, "idShort");
            if (ids.getLength() > 0 &&
                ids.item(0).getTextContent().trim().equals(idShort)) {
                return sm;
            }
        }
        return null;
    }

    private Element findCollectionByIdShort(Element parent, String idShort) {
        NodeList collections = parent.getElementsByTagNameNS(
            AAS_NS, "submodelElementCollection");
        for (int i = 0; i < collections.getLength(); i++) {
            Element col = (Element) collections.item(i);
            NodeList ids = col.getElementsByTagNameNS(AAS_NS, "idShort");
            if (ids.getLength() > 0 &&
                ids.item(0).getTextContent().trim().equals(idShort)) {
                return col;
            }
        }
        return null;
    }

    private String getChildText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(AAS_NS, localName);
        if (nodes.getLength() == 0) return "";
        return nodes.item(0).getTextContent().trim();
    }
}
