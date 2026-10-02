package md.lab.integrationservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import md.lab.orderservice.OrderProducer;

import java.io.IOException;
import java.util.Map;
import java.util.Scanner;

/** Consola comuna: publicarea trece prin acelasi protocol TCP, inclusiv din BrokerMain. */
public final class MessageConsole {
    private MessageConsole() {}

    public static void run(OrderProducer producer, MinimalBroker broker) throws IOException {
        Scanner input = new Scanner(System.in);
        ObjectMapper mapper = new ObjectMapper();
        while (true) {
            System.out.println("\n1 = Trimite la Inventory | 2 = Trimite la Audit | 3 = Trimite la toti");
            if (broker != null) System.out.println("4 = Cozi in asteptare | 5 = Mesaje esuate (DLQ)");
            System.out.println("0 = Oprire " + (broker == null ? "producer" : "broker (datele sunt salvate pe disc)"));
            String option = read(input, "Optiune: ");
            if (option == null || option.equals("0")) return;
            if (broker != null && option.equals("4")) {
                for (String id : new String[]{"inventory-consumer", "audit-consumer"}) {
                    System.out.println(id + ": " + broker.pendingCount(id) + " mesaje in asteptare");
                }
                System.out.println("Nu include mesajele deja preluate pentru livrare; nu este istoric.");
                continue;
            }
            if (broker != null && option.equals("5")) {
                var letters = broker.deadLetters();
                System.out.println("DLQ: " + letters.size() + " livrari esuate");
                for (DeadLetter letter : letters) {
                    LogUtil.log("broker", "DLQ_ENTRY", letter.message(), "consumer=" + letter.consumerId()
                            + " attempts=" + letter.attempts() + " reason=" + letter.reason());
                }
                continue;
            }
            if (!option.equals("1") && !option.equals("2") && !option.equals("3")) {
                System.out.println("Optiune invalida.");
                continue;
            }
            String orderId = read(input, "Numar comanda (ex. ORD-1002): ");
            if (orderId == null) return;
            String product = read(input, "Produs / continut mesaj: ");
            if (product == null) return;
            String quantityText = read(input, "Cantitate (numar intreg pozitiv): ");
            if (quantityText == null) return;
            try {
                int quantity = Integer.parseInt(quantityText);
                if (orderId.isBlank() || product.isBlank() || quantity <= 0) {
                    throw new IllegalArgumentException("Comanda si produsul sunt obligatorii; cantitatea trebuie sa fie pozitiva.");
                }
                String destination = switch (option) {
                    case "1" -> "inventory-consumer";
                    case "2" -> "audit-consumer";
                    default -> null;
                };
                producer.sendOrderCreated(orderId,
                        mapper.writeValueAsString(Map.of("product", product, "quantity", quantity)), destination);
            } catch (IllegalArgumentException | IOException e) {
                System.out.println("Trimiterea nu a fost confirmata: " + e.getMessage());
            }
        }
    }

    private static String read(Scanner input, String prompt) {
        System.out.print(prompt);
        return input.hasNextLine() ? input.nextLine().trim() : null;
    }
}
