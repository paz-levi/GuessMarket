package engine.impl.xml;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_TYPE = "type";
    private static final String COMMISSION_TYPE_ON_PURCHASE = "on-purchase";

    private static final int MIN_COMMISSION = 0;
    private static final int MAX_COMMISSION = 90;
    private static final int REQUIRED_OPTION_COUNT = 2;

    private EventsFileLoader() {
    }

    // Validates the path, parses the file, and builds a fully-validated list of domain Events; throws before any caller state is touched.
    public static List<Event> load(String filePath) {
        File file = validateFilePath(filePath);
        Document document = parseDocument(file);
        return extractEvents(document);
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
        int liquidityParameter = parseIntContent(firstChildElementByTag(lmsrElement, TAG_B));
        MarketMakerAccount marketMakerAccount = new MarketMakerAccount(computeInitialSubsidy(liquidityParameter));

        return new Event(id, name, description, optionOne, optionTwo, commissionRate, commissionMode,
                liquidityParameter, marketMakerAccount, EventStatus.ACTIVE);
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

    // Computes the LMSR initial subsidy C(0,0) = b * ln(2), the cost function's value before any shares are bought.
    private static double computeInitialSubsidy(int liquidityParameter) {
        return liquidityParameter * Math.log(2);
    }
}
