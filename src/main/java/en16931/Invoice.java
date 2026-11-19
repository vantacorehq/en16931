package en16931;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * UBL invoice whose totals follow the EN 16931 calculation rules.
 * Not an XRechnung, and not a submission to a tax authority.
 */
public final class Invoice {
    public static final String CUSTOMIZATION = "urn:vantacorehq:invoice-totals:1";
    private static final String INV = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    private static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    private static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    private static final Map<String, Integer> SCALE = Map.ofEntries(
            Map.entry("EUR", 2), Map.entry("USD", 2), Map.entry("GBP", 2), Map.entry("CHF", 2),
            Map.entry("UAH", 2), Map.entry("PLN", 2), Map.entry("SEK", 2), Map.entry("NOK", 2),
            Map.entry("DKK", 2), Map.entry("CZK", 2), Map.entry("RON", 2), Map.entry("BGN", 2),
            Map.entry("JPY", 0));

    private Invoice() {}

    public record Party(String name, String country) {
        public Party {
            name = token(name, 140, "BAD_PARTY");
            if (country == null || !country.matches("[A-Z]{2}")) {
                throw new EnException("BAD_PARTY", "country");
            }
        }
    }

    public record Line(String id, String name, long qtyNum, long qtyDen, long priceMinor, long baseNum, long baseDen,
                        String category, int rateHundredths) {
        public Line {
            id = token(id, 35, "BAD_LINE");
            name = token(name, 140, "BAD_LINE");
            if (qtyNum <= 0 || qtyDen <= 0 || baseNum <= 0 || baseDen <= 0 || priceMinor < 0) {
                throw new EnException("BAD_LINE", "quantity or price");
            }
            long[] q = reduce(qtyNum, qtyDen);
            long[] b = reduce(baseNum, baseDen);
            qtyNum = q[0];
            qtyDen = q[1];
            baseNum = b[0];
            baseDen = b[1];
            exactDecimal(qtyNum, qtyDen);
            exactDecimal(baseNum, baseDen);
            if (category == null || !category.matches("S|Z|E")) {
                throw new EnException("BAD_TAX", "category");
            }
            if ((category.equals("Z") || category.equals("E")) && rateHundredths != 0) {
                throw new EnException("BAD_TAX", "zero rate required");
            }
            if (category.equals("S") && (rateHundredths <= 0 || rateHundredths > 10_000)) {
                throw new EnException("BAD_TAX", "rate");
            }
            if (!category.equals("S") && rateHundredths != 0) {
                throw new EnException("BAD_TAX", "rate");
            }
        }

        public long net(int ignored) {
            return mulDiv(qtyNum, priceMinor, baseDen, qtyDen, baseNum);
        }
    }

    public record Tax(String category, int rateHundredths, long taxable, long tax) {}

    public record Draft(String id, String issueDate, String currency, Party supplier, Party customer, List<Line> lines,
                         long allowanceMinor, String allowanceCategory, int allowanceRate, long prepaidMinor) {
        public Draft {
            id = token(id, 35, "BAD_ID");
            issueDate = date(issueDate);
            if (!SCALE.containsKey(currency)) {
                throw new EnException("BAD_MONEY", "currency");
            }
            if (supplier == null || customer == null || lines == null || lines.isEmpty() || lines.size() > 1000) {
                throw new EnException("BAD_LINE", "lines");
            }
            if (allowanceMinor < 0 || prepaidMinor < 0) {
                throw new EnException("BAD_MONEY", "negative");
            }
            if (allowanceMinor == 0) {
                allowanceCategory = null;
                allowanceRate = 0;
            } else {
                if (allowanceCategory == null || !allowanceCategory.matches("S|Z|E")) {
                    throw new EnException("BAD_TAX", "allowance category");
                }
                if ((allowanceCategory.equals("S") && (allowanceRate <= 0 || allowanceRate > 10_000))
                        || (!allowanceCategory.equals("S") && allowanceRate != 0)) {
                    throw new EnException("BAD_TAX", "allowance rate");
                }
            }
            lines = List.copyOf(lines);
        }
    }

    public record Built(Draft draft, long lineExtension, long taxExclusive, long taxTotal, long taxInclusive, long payable,
                         List<Tax> taxes) {}

    public static Built build(Draft draft) {
        int scale = SCALE.get(draft.currency);
        long lineExtension = 0;
        Map<String, long[]> groups = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (Line line : draft.lines) {
            if (ids.contains(line.id)) {
                throw new EnException("BAD_LINE", "duplicate id");
            }
            ids.add(line.id);
            long net = line.net(scale);
            lineExtension = Math.addExact(lineExtension, net);
            String key = line.category + ":" + line.rateHundredths;
            long[] g = groups.computeIfAbsent(key, k -> new long[] {0, line.rateHundredths, line.category.charAt(0)});
            g[0] = Math.addExact(g[0], net);
        }
        if (draft.allowanceMinor > 0) {
            String key = draft.allowanceCategory + ":" + draft.allowanceRate;
            long[] g = groups.get(key);
            if (g == null || g[0] < draft.allowanceMinor) {
                throw new EnException("BAD_SUM", "allowance above the tax base");
            }
            g[0] -= draft.allowanceMinor;
        }
        long taxTotal = 0;
        List<Tax> taxes = new ArrayList<>();
        for (var e : groups.entrySet()) {
            String[] parts = e.getKey().split(":");
            long taxable = e.getValue()[0];
            int rate = Integer.parseInt(parts[1]);
            long tax = mulDiv(taxable, rate, 1, 10_000, 1);
            taxTotal = Math.addExact(taxTotal, tax);
            taxes.add(new Tax(parts[0], rate, taxable, tax));
        }
        long taxExclusive = Math.subtractExact(lineExtension, draft.allowanceMinor);
        long taxInclusive = Math.addExact(taxExclusive, taxTotal);
        if (draft.prepaidMinor > taxInclusive) {
            throw new EnException("BAD_SUM", "prepaid above the total");
        }
        long payable = taxInclusive - draft.prepaidMinor;
        return new Built(draft, lineExtension, taxExclusive, taxTotal, taxInclusive, payable, List.copyOf(taxes));
    }

    public static String xml(Built built) {
        Draft d = built.draft;
        int scale = SCALE.get(d.currency);
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().newDocument();
            Element root = doc.createElementNS(INV, "Invoice");
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", INV);
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cac", CAC);
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cbc", CBC);
            doc.appendChild(root);
            cbc(doc, root, "CustomizationID", CUSTOMIZATION);
            cbc(doc, root, "ID", d.id);
            cbc(doc, root, "IssueDate", d.issueDate);
            cbc(doc, root, "InvoiceTypeCode", "380");
            cbc(doc, root, "DocumentCurrencyCode", d.currency);
            root.appendChild(party(doc, "AccountingSupplierParty", d.supplier));
            root.appendChild(party(doc, "AccountingCustomerParty", d.customer));
            if (d.allowanceMinor > 0) {
                Element a = cac(doc, "AllowanceCharge");
                root.appendChild(a);
                cbc(doc, a, "ChargeIndicator", "false");
                amount(doc, a, "Amount", d.currency, d.allowanceMinor, scale);
                a.appendChild(taxCategory(doc, d.allowanceCategory, d.allowanceRate));
            }
            Element taxTotal = cac(doc, "TaxTotal");
            root.appendChild(taxTotal);
            amount(doc, taxTotal, "TaxAmount", d.currency, built.taxTotal, scale);
            for (Tax tax : built.taxes) {
                Element sub = cac(doc, "TaxSubtotal");
                taxTotal.appendChild(sub);
                amount(doc, sub, "TaxableAmount", d.currency, tax.taxable, scale);
                amount(doc, sub, "TaxAmount", d.currency, tax.tax, scale);
                sub.appendChild(taxCategory(doc, tax.category, tax.rateHundredths));
            }
            Element legal = cac(doc, "LegalMonetaryTotal");
            root.appendChild(legal);
            amount(doc, legal, "LineExtensionAmount", d.currency, built.lineExtension, scale);
            amount(doc, legal, "TaxExclusiveAmount", d.currency, built.taxExclusive, scale);
            amount(doc, legal, "TaxInclusiveAmount", d.currency, built.taxInclusive, scale);
            if (d.prepaidMinor > 0) {
                amount(doc, legal, "PrepaidAmount", d.currency, d.prepaidMinor, scale);
            }
            amount(doc, legal, "PayableAmount", d.currency, built.payable, scale);
            for (Line line : d.lines) {
                root.appendChild(lineEl(doc, d.currency, scale, line));
            }
            return emit(doc);
        } catch (EnException e) {
            throw e;
        } catch (Exception e) {
            throw new EnException("BAD_XML", "not writable");
        }
    }

    public static Built parse(String xml) {
        Document doc = parseXml(xml);
        Element root = doc.getDocumentElement();
        if (!"Invoice".equals(root.getLocalName()) || !INV.equals(root.getNamespaceURI())) {
            throw new EnException("BAD_XML", "root");
        }
        if (!CUSTOMIZATION.equals(leaf(need(root, CBC, "CustomizationID")))) {
            throw new EnException("BAD_XML", "customization");
        }
        String id = leaf(need(root, CBC, "ID"));
        String date = leaf(need(root, CBC, "IssueDate"));
        if (!"380".equals(leaf(need(root, CBC, "InvoiceTypeCode")))) {
            throw new EnException("BAD_XML", "type");
        }
        String currency = leaf(need(root, CBC, "DocumentCurrencyCode"));
        Party supplier = readParty(need(root, CAC, "AccountingSupplierParty"));
        Party customer = readParty(need(root, CAC, "AccountingCustomerParty"));
        String allowanceCat = null;
        int allowanceRate = 0;
        long allowanceMinor = 0;
        Element allowanceEl = child(root, CAC, "AllowanceCharge");
        if (allowanceEl != null) {
            if (!"false".equals(leaf(need(allowanceEl, CBC, "ChargeIndicator")))) {
                throw new EnException("BAD_XML", "charges are not in this subset");
            }
            allowanceMinor = readMoney(need(allowanceEl, CBC, "Amount"), currency);
            Element cat = need(allowanceEl, CAC, "TaxCategory");
            allowanceCat = leaf(need(cat, CBC, "ID"));
            allowanceRate = percent(leaf(need(cat, CBC, "Percent")));
        }
        List<Line> lines = new ArrayList<>();
        for (Element line : children(root, CAC, "InvoiceLine")) {
            lines.add(readLine(line, currency));
        }
        long prepaid = 0;
        Element legal = need(root, CAC, "LegalMonetaryTotal");
        Element prepaidEl = child(legal, CBC, "PrepaidAmount");
        if (prepaidEl != null) {
            prepaid = readMoney(prepaidEl, currency);
        }
        Draft draft = new Draft(id, date, currency, supplier, customer, lines, allowanceMinor, allowanceCat, allowanceRate, prepaid);
        Built built = build(draft);
        int scale = SCALE.get(currency);
        for (int i = 0; i < lines.size(); i++) {
            Element line = children(root, CAC, "InvoiceLine").get(i);
            long stated = readMoney(need(line, CBC, "LineExtensionAmount"), currency);
            if (stated != lines.get(i).net(scale)) {
                throw new EnException("BAD_SUM", "line " + lines.get(i).id);
            }
        }
        Element taxTotal = need(root, CAC, "TaxTotal");
        if (readMoney(need(taxTotal, CBC, "TaxAmount"), currency) != built.taxTotal) {
            throw new EnException("BAD_SUM", "tax total");
        }
        List<Element> subs = children(taxTotal, CAC, "TaxSubtotal");
        if (subs.size() != built.taxes.size()) {
            throw new EnException("BAD_SUM", "tax subtotals");
        }
        for (int i = 0; i < subs.size(); i++) {
            Tax tax = built.taxes.get(i);
            if (readMoney(need(subs.get(i), CBC, "TaxableAmount"), currency) != tax.taxable
                    || readMoney(need(subs.get(i), CBC, "TaxAmount"), currency) != tax.tax) {
                throw new EnException("BAD_SUM", "tax subtotal");
            }
        }
        if (readMoney(need(legal, CBC, "LineExtensionAmount"), currency) != built.lineExtension
                || readMoney(need(legal, CBC, "TaxExclusiveAmount"), currency) != built.taxExclusive
                || readMoney(need(legal, CBC, "TaxInclusiveAmount"), currency) != built.taxInclusive
                || readMoney(need(legal, CBC, "PayableAmount"), currency) != built.payable) {
            throw new EnException("BAD_SUM", "monetary total");
        }
        return built;
    }

    private static Line readLine(Element line, String currency) {
        String id = leaf(need(line, CBC, "ID"));
        String qty = leaf(need(line, CBC, "InvoicedQuantity"));
        long[] q = rational(qty);
        Element item = need(line, CAC, "Item");
        String name = leaf(need(item, CBC, "Name"));
        Element cat = need(item, CAC, "ClassifiedTaxCategory");
        String category = leaf(need(cat, CBC, "ID"));
        int rate = percent(leaf(need(cat, CBC, "Percent")));
        Element price = need(line, CAC, "Price");
        long priceMinor = readMoney(need(price, CBC, "PriceAmount"), currency);
        long baseNum = 1;
        long baseDen = 1;
        Element base = child(price, CBC, "BaseQuantity");
        if (base != null) {
            long[] b = rational(leaf(base));
            baseNum = b[0];
            baseDen = b[1];
        }
        return new Line(id, name, q[0], q[1], priceMinor, baseNum, baseDen, category, rate);
    }

    private static Party readParty(Element wrap) {
        Element party = need(wrap, CAC, "Party");
        String name = leaf(need(need(party, CAC, "PartyName"), CBC, "Name"));
        String country = leaf(need(need(need(party, CAC, "PostalAddress"), CAC, "Country"), CBC, "IdentificationCode"));
        return new Party(name, country);
    }

    private static Element lineEl(Document doc, String currency, int scale, Line line) {
        Element e = cac(doc, "InvoiceLine");
        cbc(doc, e, "ID", line.id);
        Element qty = cbc(doc, e, "InvoicedQuantity", decimal(line.qtyNum, line.qtyDen));
        qty.setAttribute("unitCode", "C62");
        amount(doc, e, "LineExtensionAmount", currency, line.net(scale), scale);
        Element item = cac(doc, "Item");
        e.appendChild(item);
        cbc(doc, item, "Name", line.name);
        item.appendChild(classified(doc, line.category, line.rateHundredths));
        Element price = cac(doc, "Price");
        e.appendChild(price);
        amount(doc, price, "PriceAmount", currency, line.priceMinor, scale);
        Element base = cbc(doc, price, "BaseQuantity", decimal(line.baseNum, line.baseDen));
        base.setAttribute("unitCode", "C62");
        return e;
    }

    private static Element party(Document doc, String tag, Party party) {
        Element wrap = cac(doc, tag);
        Element p = cac(doc, "Party");
        wrap.appendChild(p);
        Element name = cac(doc, "PartyName");
        p.appendChild(name);
        cbc(doc, name, "Name", party.name);
        Element addr = cac(doc, "PostalAddress");
        p.appendChild(addr);
        Element country = cac(doc, "Country");
        addr.appendChild(country);
        cbc(doc, country, "IdentificationCode", party.country);
        return wrap;
    }

    private static Element taxCategory(Document doc, String category, int rate) {
        Element c = cac(doc, "TaxCategory");
        cbc(doc, c, "ID", category);
        cbc(doc, c, "Percent", percentText(rate));
        Element scheme = cac(doc, "TaxScheme");
        c.appendChild(scheme);
        cbc(doc, scheme, "ID", "VAT");
        return c;
    }

    private static Element classified(Document doc, String category, int rate) {
        Element c = cac(doc, "ClassifiedTaxCategory");
        cbc(doc, c, "ID", category);
        cbc(doc, c, "Percent", percentText(rate));
        Element scheme = cac(doc, "TaxScheme");
        c.appendChild(scheme);
        cbc(doc, scheme, "ID", "VAT");
        return c;
    }

    private static long readMoney(Element e, String currency) {
        if (!currency.equals(e.getAttribute("currencyID"))) {
            throw new EnException("BAD_MONEY", "currency");
        }
        return parseMinor(leaf(e), SCALE.get(currency));
    }

    static long parseMinor(String decimal, int scale) {
        if (decimal == null || !decimal.matches(scale == 0
                ? "0|[1-9][0-9]*"
                : "(0|[1-9][0-9]*)\\.[0-9]{" + scale + "}")) {
            throw new EnException("BAD_MONEY", decimal);
        }
        return Long.parseLong(decimal.replace(".", ""));
    }

    static String formatMinor(long minor, int scale) {
        if (minor < 0) {
            throw new EnException("BAD_MONEY", "negative");
        }
        if (scale == 0) {
            return Long.toString(minor);
        }
        String s = Long.toString(minor);
        if (s.length() <= scale) {
            s = "0".repeat(scale + 1 - s.length()) + s;
        }
        int cut = s.length() - scale;
        return s.substring(0, cut) + "." + s.substring(cut);
    }

    private static void amount(Document doc, Element parent, String name, String currency, long minor, int scale) {
        Element e = cbc(doc, parent, name, formatMinor(minor, scale));
        e.setAttribute("currencyID", currency);
    }

    private static String percentText(int hundredths) {
        return (hundredths / 100) + "." + String.format("%02d", hundredths % 100);
    }

    private static int percent(String text) {
        if (text == null || !text.matches("(0|[1-9][0-9]*)\\.[0-9]{2}")) {
            throw new EnException("BAD_TAX", "percent");
        }
        int v = Integer.parseInt(text.replace(".", ""));
        if (v > 10_000) {
            throw new EnException("BAD_TAX", "percent");
        }
        return v;
    }

    private static long[] rational(String text) {
        if (text == null || !text.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,4})?")) {
            throw new EnException("BAD_LINE", "quantity");
        }
        if (!text.contains(".")) {
            return new long[] {Long.parseLong(text), 1};
        }
        String[] p = text.split("\\.");
        long num = Long.parseLong(p[0] + p[1]);
        long den = 1;
        for (int i = 0; i < p[1].length(); i++) {
            den *= 10;
        }
        return reduce(num, den);
    }

    private static String decimal(long num, long den) {
        try {
            BigDecimal q = new BigDecimal(BigInteger.valueOf(num))
                    .divide(new BigDecimal(BigInteger.valueOf(den)), 4, RoundingMode.UNNECESSARY);
            return q.stripTrailingZeros().toPlainString();
        } catch (ArithmeticException e) {
            throw new EnException("BAD_LINE", "quantity is not a 4-place decimal");
        }
    }

    private static long mulDiv(long a, long b, long c, long d, long e) {
        BigInteger n = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).multiply(BigInteger.valueOf(c));
        BigInteger den = BigInteger.valueOf(d).multiply(BigInteger.valueOf(e));
        BigInteger[] qr = n.divideAndRemainder(den);
        BigInteger q = qr[0];
        if (qr[1].shiftLeft(1).compareTo(den) >= 0) {
            q = q.add(BigInteger.ONE);
        }
        if (q.bitLength() > 62) {
            throw new EnException("BAD_MONEY", "overflow");
        }
        return q.longValue();
    }

    private static long[] reduce(long n, long d) {
        long g = BigInteger.valueOf(n).gcd(BigInteger.valueOf(d)).longValue();
        return new long[] {n / g, d / g};
    }

    private static String token(String raw, int max, String code) {
        if (raw == null || raw.isEmpty() || raw.length() > max || !raw.equals(raw.trim())) {
            throw new EnException(code, "length");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) < 0x20 || raw.charAt(i) == 0x7f) {
                throw new EnException(code, "control");
            }
        }
        return raw;
    }

    private static String date(String raw) {
        if (raw == null || !raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new EnException("BAD_DATE", String.valueOf(raw));
        }
        int y = Integer.parseInt(raw.substring(0, 4));
        int m = Integer.parseInt(raw.substring(5, 7));
        int d = Integer.parseInt(raw.substring(8, 10));
        int dim = switch (m) {
            case 1, 3, 5, 7, 8, 10, 12 -> 31;
            case 4, 6, 9, 11 -> 30;
            case 2 -> (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) ? 29 : 28;
            default -> 0;
        };
        if (y < 2000 || y > 2100 || d < 1 || d > dim) {
            throw new EnException("BAD_DATE", raw);
        }
        return raw;
    }

    private static void exactDecimal(long num, long den) {
        decimal(num, den);
    }

    private static Element cbc(Document doc, Element parent, String name, String value) {
        Element e = doc.createElementNS(CBC, "cbc:" + name);
        e.setTextContent(value);
        parent.appendChild(e);
        return e;
    }

    private static Element cac(Document doc, String name) {
        return doc.createElementNS(CAC, "cac:" + name);
    }

    private static Element need(Element parent, String ns, String name) {
        Element e = child(parent, ns, name);
        if (e == null) {
            throw new EnException("BAD_XML", "missing " + name);
        }
        return e;
    }

    private static Element child(Element parent, String ns, String name) {
        Element found = null;
        var kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (name.equals(e.getLocalName()) && ns.equals(e.getNamespaceURI())) {
                if (found != null) {
                    throw new EnException("BAD_XML", "duplicate " + name);
                }
                found = e;
            }
        }
        return found;
    }

    private static List<Element> children(Element parent, String ns, String name) {
        List<Element> out = new ArrayList<>();
        var kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element e && name.equals(e.getLocalName()) && ns.equals(e.getNamespaceURI())) {
                out.add(e);
            }
        }
        return out;
    }

    private static String leaf(Element e) {
        var kids = e.getChildNodes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                throw new EnException("BAD_XML", "nested in " + e.getLocalName());
            }
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    private static Document parseXml(String xml) {
        if (xml == null || xml.length() > 1_000_000) {
            throw new EnException("BAD_XML", "size");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var b = f.newDocumentBuilder();
            b.setErrorHandler(new org.xml.sax.ErrorHandler() {
                public void warning(org.xml.sax.SAXParseException e) {}
                public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            return b.parse(new org.xml.sax.InputSource(new StringReader(xml)));
        } catch (EnException e) {
            throw e;
        } catch (Exception e) {
            throw new EnException("BAD_XML", "not readable");
        }
    }

    private static String emit(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }
}
