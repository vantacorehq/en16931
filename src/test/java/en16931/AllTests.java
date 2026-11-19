package en16931;

import java.util.List;

public final class AllTests {
    static int checks;

    public static void main(String[] args) {
        totals();
        rounding();
        xml();
        rejects();
        System.out.println("en16931: " + checks + " checks passed");
    }

    static void totals() {
        Invoice.Built b = Invoice.build(draft(List.of(line("1", "Widget", 1, 1, 10_000, "S", 1900)), 0, null, 0, 0));
        check(b.lineExtension() == 10_000, "line");
        check(b.taxTotal() == 1_900, "vat 19");
        check(b.taxExclusive() == 10_000, "exclusive");
        check(b.taxInclusive() == 11_900, "inclusive");
        check(b.payable() == 11_900, "payable");

        Invoice.Built zero = Invoice.build(draft(List.of(
                line("1", "Rated", 1, 1, 10_000, "S", 1900),
                line("2", "Free", 1, 1, 5_000, "Z", 0)), 0, null, 0, 0));
        check(zero.lineExtension() == 15_000, "mixed base");
        check(zero.taxTotal() == 1_900, "mixed tax");
        check(zero.taxes().size() == 2, "two bands");

        Invoice.Built allow = Invoice.build(draft(List.of(line("1", "Widget", 1, 1, 10_000, "S", 1900)),
                1_000, "S", 1900, 500));
        check(allow.taxExclusive() == 9_000, "after allowance");
        check(allow.taxTotal() == 1_710, "tax on 90");
        check(allow.payable() == 9_000 + 1_710 - 500, "prepaid");
    }

    static void rounding() {
        Invoice.Line cents = line("1", "Nail", 3, 1, 10, "S", 1900);
        check(cents.net(2) == 30, "three dimes");
        Invoice.Built b = Invoice.build(draft(List.of(cents), 0, null, 0, 0));
        check(b.taxTotal() == 6, "0.30 at 19 percent is 0.06");

        Invoice.Line tiny = line("1", "Bit", 1, 1, 3, "S", 1900);
        Invoice.Built three = Invoice.build(draft(List.of(tiny, line("2", "Bit", 1, 1, 3, "S", 1900),
                line("3", "Bit", 1, 1, 3, "S", 1900)), 0, null, 0, 0));
        check(three.lineExtension() == 9, "nine cents");
        check(three.taxTotal() == 2, "group rounds to 2 not 3");

        Invoice.Line half = new Invoice.Line("1", "Hour", 3, 2, 1_000, 1, 1, "S", 1900);
        check(half.qtyNum() == 3 && half.qtyDen() == 2, "reduced");
        check(half.net(2) == 1_500, "1.5 times 10");

        Invoice.Built jpy = Invoice.build(new Invoice.Draft("J1", "2026-11-19", "JPY",
                party("A"), party("B"), List.of(new Invoice.Line("1", "Ticket", 1, 1, 100, 1, 1, "S", 1000)),
                0, null, 0, 0));
        check(jpy.taxTotal() == 10, "jpy 10 percent");
        check(Invoice.formatMinor(jpy.payable(), 0).equals("110"), "jpy text");
    }

    static void xml() {
        Invoice.Draft d = draft(List.of(
                new Invoice.Line("1", "Alice & Bob", 3, 2, 1_000, 1, 1, "S", 1900),
                line("2", "Zero", 2, 1, 500, "Z", 0)), 0, null, 0, 0);
        Invoice.Built built = Invoice.build(d);
        String xml = Invoice.xml(built);
        check(xml.contains("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"), "ubl");
        check(xml.contains("&"), "escaped");
        check(!xml.contains("urn:cen.eu:en16931"), "not a fake CIUS id");
        Invoice.Built back = Invoice.parse(xml);
        check(back.equals(built), "roundtrip");
        check(Invoice.formatMinor(back.payable(), 2).equals("27.85"), "15.00 at 19 percent plus 10.00 at zero");
        String tampered = xml.replace(">" + Invoice.formatMinor(built.payable(), 2) + "<", ">1.00<");
        fails("BAD_SUM", () -> Invoice.parse(tampered));
        fails("BAD_XML", () -> Invoice.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"));
    }

    static void rejects() {
        fails("BAD_TAX", () -> line("1", "A", 1, 1, 100, "S", 0));
        fails("BAD_TAX", () -> line("1", "A", 1, 1, 100, "Z", 1900));
        fails("BAD_LINE", () -> new Invoice.Line("1", "A", 1, 3, 100, 1, 1, "S", 1900));
        fails("BAD_DATE", () -> Invoice.build(new Invoice.Draft("I", "2023-02-29", "EUR", party("A"), party("B"),
                List.of(line("1", "A", 1, 1, 100, "S", 1900)), 0, null, 0, 0)));
        fails("BAD_SUM", () -> Invoice.build(draft(List.of(line("1", "A", 1, 1, 100, "S", 1900)),
                500, "S", 1900, 0)));
        fails("BAD_LINE", () -> Invoice.build(draft(List.of(line("1", "A", 1, 1, 100, "S", 1900),
                line("1", "B", 1, 1, 100, "S", 1900)), 0, null, 0, 0)));
        fails("BAD_MONEY", () -> Invoice.parse(Invoice.xml(Invoice.build(draft(List.of(line("1", "A", 1, 1, 100, "S", 1900)),
                0, null, 0, 0))).replace("DocumentCurrencyCode>EUR<", "DocumentCurrencyCode>USD<")));
        fails("BAD_SUM", () -> Invoice.build(draft(List.of(line("1", "A", 1, 1, 100, "Z", 0)), 0, null, 0, 500)));
    }

    static Invoice.Draft draft(List<Invoice.Line> lines, long allowance, String cat, int rate, long prepaid) {
        return new Invoice.Draft("INV-1", "2026-11-19", "EUR", party("Supplier"), party("Buyer"),
                lines, allowance, cat, rate, prepaid);
    }

    static Invoice.Line line(String id, String name, long qty, long den, long price, String cat, int rate) {
        return new Invoice.Line(id, name, qty, den, price, 1, 1, cat, rate);
    }

    static Invoice.Party party(String name) {
        return new Invoice.Party(name, "DE");
    }

    static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
        checks++;
    }

    static void fails(String code, Runnable r) {
        try {
            r.run();
        } catch (EnException e) {
            check(code.equals(e.code), e.getMessage());
            return;
        }
        throw new AssertionError("expected " + code);
    }
}
