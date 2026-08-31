package engine.impl.xml;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import dto.EventStatus;
import engine.domain.CommissionMode;
import engine.domain.Event;
import engine.domain.EventOption;
import engine.domain.MarketMakerAccount;
import engine.domain.User;
import exception.XmlValidationException;

// Loads, parses, and validates an events XML file into a list of fresh domain Events; never touches EngineImpl's live state itself.
public final class EventsFileLoader {

    private static final String XML_EXTENSION = ".xml";

    // The spec text calls this element "commission"; every real sample file (and the real XSD) actually uses "comision".
    // Only this lookup is dual — everywhere else in the codebase the field/variable name is always "commission".
    private static final String COMMISSION_TAG = "commission";
    private static final String COMMISSION_TAG_LEGACY = "comision";

    private static final String TAG_GM_EVENT = "GM-event";
    private static final String TAG_ID = "id";
    private static final String TAG_DESCRIPTION = "description";
    private static final String TAG_GM_OPTIONS = "GM-options";
    private static final String TAG_GM_OPTION = "GM-option";
    private static final String TAG_GM_METHOD = "GM-method";
    private static final String TAG_GM_LMSR = "GM-LMSR";
    private static final String TAG_B = "b";
    private static final String TAG_GM_USERS = "GM-users";
    private static final String TAG_GM_USER = "GM-user";
    private static final String TAG_INITIAL_CASH = "initial-cash";
    private static final String TAG_GM_MARKET_MAKER = "GM-market-maker";
    private static final String TAG_EVENT_REF = "event";
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_TYPE = "type";
    private static final String ATTRIBUTE_ID = "id";
    private static final String COMMISSION_TYPE_ON_PURCHASE = "on-purchase";

    private static final int MIN_COMMISSION = 0;
    private static final int MAX_COMMISSION = 90;
    private static final int REQUIRED_OPTION_COUNT = 2;

    private EventsFileLoader() {
    }

    // Validates the path, parses the file, and builds a fully cross-referenced set of domain Events and Users; throws before any caller state is touched.
    public static LoadedFile load(String filePath) {
        File file = validateFilePath(filePath);
        Document document = parseDocument(file);
        List<Event> events = extractEvents(document);

        Map<Integer, Event> eventsById = new LinkedHashMap<>();
        for (Event event : events) {
            eventsById.put(event.getId(), event);
        }
        List<User> users = extractUsers(document, eventsById);
        requireEveryEventHasAMarketMaker(events);

        return new LoadedFile(events, users);
    }

    // Checks the path ends in .xml (case-insensitive) and points at an existing file, per CLAUDE.md's minimum load validation.
    private static File validateFilePath(String filePath) {
        if (filePath == null || !filePath.toLowerCase().endsWith(XML_EXTENSION)) {
            throw new XmlValidationException("The file path must end in .xml: \"" + filePath + "\"");
        }
        File file = new File(filePath);
        if (!file.isFile()) {
            throw new XmlValidationException("No file was found at path: \"" + filePath + "\"");
        }
        return file;
    }

    // Parses the file into a DOM Document, converting any parser failure into the engine's own exception type.
    private static Document parseDocument(File file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(file);
            document.getDocumentElement().normalize();
            return document;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new XmlValidationException("The file could not be parsed as XML: " + e.getMessage());
        }
    }

    // Walks every GM-event in document order, validating and building each one; stops at the first violation found.
    private static List<Event> extractEvents(Document document) {
        NodeList eventNodes = document.getElementsByTagName(TAG_GM_EVENT);
        if (eventNodes.getLength() == 0) {
            throw new XmlValidationException("The file does not contain any GM-event elements.");
        }
        List<Event> events = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        for (int i = 0; i < eventNodes.getLength(); i++) {
            Element eventElement = (Element) eventNodes.item(i);
            events.add(buildEvent(eventElement, seenIds));
        }
        return events;
    }

    // Validates one GM-event element's business rules and builds the corresponding domain Event, with a freshly-subsidized MM account.
    private static Event buildEvent(Element eventElement, Set<Integer> seenIds) {
        int id = parseIntContent(firstChildElementByTag(eventElement, TAG_ID));
        if (!seenIds.add(id)) {
            throw new XmlValidationException("Event id " + id + " appears more than once in the file.");
        }

        String name = eventElement.getAttribute(ATTRIBUTE_NAME).trim();
        String description = firstChildElementByTag(eventElement, TAG_DESCRIPTION).getTextContent().trim();

        List<String> optionNames = extractOptionNames(eventElement, id);
        EventOption optionOne = new EventOption(optionNames.get(0));
        EventOption optionTwo = new EventOption(optionNames.get(1));

        Element commissionElement = findCommissionElement(eventElement);
        int commissionRate = parseIntContent(commissionElement);
        if (commissionRate < MIN_COMMISSION || commissionRate > MAX_COMMISSION) {
            throw new XmlValidationException("Event id " + id + " has commission " + commissionRate
                    + ", which is outside the allowed range [" + MIN_COMMISSION + ", " + MAX_COMMISSION + "].");
        }
        CommissionMode commissionMode = COMMISSION_TYPE_ON_PURCHASE.equalsIgnoreCase(commissionElement.getAttribute(ATTRIBUTE_TYPE))
                ? CommissionMode.ON_PURCHASE
                : CommissionMode.ON_CLOSE;

        Element methodElement = firstChildElementByTag(eventElement, TAG_GM_METHOD);
        Element lmsrElement = firstChildElementByTag(methodElement, TAG_GM_LMSR);
        if (lmsrElement == null) {
            throw new XmlValidationException("Event id " + id + " does not use GM-LMSR; "
                    + "Order Book events are not yet supported in this build.");
        }
        int liquidityParameter = parseIntContent(firstChildElementByTag(lmsrElement, TAG_B));
        // Starts at 0, not the subsidy: the subsidy only moves from the MM's own balance once EngineImpl.openEvent() actually opens this event.
        MarketMakerAccount marketMakerAccount = new MarketMakerAccount(0.0);

        return new Event(id, name, description, optionOne, optionTwo, commissionRate, commissionMode,
                liquidityParameter, marketMakerAccount, EventStatus.NOT_STARTED);
    }

    // Reads and validates an event's GM-option names, enforcing the exactly-two-options business rule.
    private static List<String> extractOptionNames(Element eventElement, int id) {
        Element optionsElement = firstChildElementByTag(eventElement, TAG_GM_OPTIONS);
        NodeList optionNodes = optionsElement.getElementsByTagName(TAG_GM_OPTION);
        if (optionNodes.getLength() != REQUIRED_OPTION_COUNT) {
            throw new XmlValidationException("Event id " + id + " has " + optionNodes.getLength()
                    + " " + TAG_GM_OPTION + " entries; exactly " + REQUIRED_OPTION_COUNT + " are required.");
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < optionNodes.getLength(); i++) {
            names.add(optionNodes.item(i).getTextContent().trim());
        }
        return names;
    }

    // Looks up the commission element under either its spec-text name or the real files' legacy name — the one place this dual lookup lives.
    private static Element findCommissionElement(Element eventElement) {
        Element element = firstChildElementByTag(eventElement, COMMISSION_TAG);
        return element != null ? element : firstChildElementByTag(eventElement, COMMISSION_TAG_LEGACY);
    }

    // Requires a GM-users element, validates and builds every GM-user, and assigns each one's market-maker event references along the way.
    private static List<User> extractUsers(Document document, Map<Integer, Event> eventsById) {
        NodeList usersWrapperNodes = document.getElementsByTagName(TAG_GM_USERS);
        if (usersWrapperNodes.getLength() == 0) {
            throw new XmlValidationException("The file does not contain a " + TAG_GM_USERS + " element.");
        }
        NodeList userNodes = document.getElementsByTagName(TAG_GM_USER);
        List<User> users = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < userNodes.getLength(); i++) {
            Element userElement = (Element) userNodes.item(i);
            users.add(buildUser(userElement, seenNames, eventsById));
        }
        return users;
    }

    // Validates one GM-user element's business rules, builds the corresponding domain User, and assigns any market-maker event references it declares.
    private static User buildUser(Element userElement, Set<String> seenNames, Map<Integer, Event> eventsById) {
        String name = userElement.getAttribute(ATTRIBUTE_NAME).trim();
        if (!seenNames.add(name)) {
            throw new XmlValidationException("User name \"" + name + "\" appears more than once in the file.");
        }

        int initialCash = parseIntContent(firstChildElementByTag(userElement, TAG_INITIAL_CASH));
        if (initialCash <= 0) {
            throw new XmlValidationException("User \"" + name + "\" has initial-cash " + initialCash
                    + ", which must be greater than 0.");
        }

        Element marketMakerElement = firstChildElementByTag(userElement, TAG_GM_MARKET_MAKER);
        if (marketMakerElement != null) {
            assignMarketMakerEvents(name, marketMakerElement, eventsById);
        }

        return new User(name, initialCash);
    }

    // Assigns every event a GM-market-maker element references to the given user, validating each reference along the way.
    private static void assignMarketMakerEvents(String username, Element marketMakerElement, Map<Integer, Event> eventsById) {
        NodeList eventRefs = marketMakerElement.getElementsByTagName(TAG_EVENT_REF);
        for (int i = 0; i < eventRefs.getLength(); i++) {
            Element eventRef = (Element) eventRefs.item(i);
            int eventId = Integer.parseInt(eventRef.getAttribute(ATTRIBUTE_ID).trim());
            Event event = eventsById.get(eventId);
            if (event == null) {
                throw new XmlValidationException("User \"" + username + "\" is declared as market maker for event id "
                        + eventId + ", which does not exist.");
            }
            if (event.getMarketMakerUsername() != null) {
                throw new XmlValidationException("Event id " + eventId + " already has a market maker (\""
                        + event.getMarketMakerUsername() + "\"); an event may have exactly one market maker.");
            }
            event.assignMarketMaker(username);
        }
    }

    // Rejects any event nobody claimed as market maker — the "zero MM" half of the exactly-one-MM-per-event rule (the "more than one" half is caught eagerly in assignMarketMakerEvents).
    private static void requireEveryEventHasAMarketMaker(List<Event> events) {
        for (Event event : events) {
            if (event.getMarketMakerUsername() == null) {
                throw new XmlValidationException("Event id " + event.getId()
                        + " has no market maker assigned; every event must have exactly one market maker.");
            }
        }
    }

    // Returns the first direct child element of the given name, or null if there isn't one.
    private static Element firstChildElementByTag(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    // Trims and parses an element's text content as an integer.
    private static int parseIntContent(Element element) {
        return Integer.parseInt(element.getTextContent().trim());
    }
}
