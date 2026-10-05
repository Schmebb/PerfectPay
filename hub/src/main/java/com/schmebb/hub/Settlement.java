package com.schmebb.hub;

import com.schmebb.common.PaymentStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Nets a day's accepted payments into what each bank owes or is owed. */
final class Settlement {

    /**
     * @param netPositionsCents per bank: positive means it receives that much, negative means it pays
     * @param obligations       per pair of banks, who pays whom after netting the two directions
     */
    record Report(LocalDate date, int acceptedPayments, long totalCents,
                  Map<String, Long> netPositionsCents, List<Obligation> obligations) {
    }

    record Obligation(String from, String to, long amountCents, String summary) {
    }

    private Settlement() {
    }

    static Report report(Collection<HubPayment> payments, Collection<String> banks, LocalDate date, ZoneId zone) {
        List<HubPayment> accepted = payments.stream()
                .filter(p -> p.status() == PaymentStatus.ACCEPTED)
                .filter(p -> p.decidedAt().atZone(zone).toLocalDate().equals(date))
                .toList();

        Map<String, Long> net = new TreeMap<>();
        banks.forEach(bank -> net.put(bank, 0L));
        // flows.get(a).get(b): total a paid to b.
        Map<String, Map<String, Long>> flows = new TreeMap<>();
        long total = 0;
        for (HubPayment payment : accepted) {
            String debtor = payment.instruction().debtorBank();
            String creditor = payment.instruction().creditorBank();
            long amount = payment.instruction().amountCents();
            total += amount;
            net.merge(debtor, -amount, Long::sum);
            net.merge(creditor, amount, Long::sum);
            flows.computeIfAbsent(debtor, k -> new TreeMap<>()).merge(creditor, amount, Long::sum);
        }

        List<String> sorted = net.keySet().stream().toList();
        List<Obligation> obligations = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                String a = sorted.get(i);
                String b = sorted.get(j);
                long aToB = flows.getOrDefault(a, Map.of()).getOrDefault(b, 0L)
                        - flows.getOrDefault(b, Map.of()).getOrDefault(a, 0L);
                if (aToB != 0) {
                    String from = aToB > 0 ? a : b;
                    String to = aToB > 0 ? b : a;
                    long amount = Math.abs(aToB);
                    obligations.add(new Obligation(from, to, amount, from + " owes " + to + " " + Money.format(amount)));
                }
            }
        }
        return new Report(date, accepted.size(), total, net, obligations);
    }
}
