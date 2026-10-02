# Ghid pentru începători

Producerul creează un mesaj JSON din comanda introdusă și îl trimite brokerului prin TCP.
Brokerul îl salvează înainte să răspundă PUBLISHED, apoi îl livrează separat fiecărui destinatar.
Inventory salvează efectul local, Audit salvează evenimentul, iar fiecare trimite ACK după salvare.
Dacă lipsește ACK, brokerul retrimite același mesaj; consumatorul recunoaște înregistrarea
salvată și evită repetarea efectului. Eșecurile permanente sau încercările epuizate ajung în DLQ.

Datele obișnuite sunt în data/broker.json, data/inventory.json și data/audit.json.
Ele rămân după restart. Demonstrația folosește propriul subdirector data/demo-*.
Pentru aceeași solicitare folosește același număr și aceleași date; pentru una nouă,
un număr nou. Refolosirea numărului cu alt produs sau destinatar este respinsă.

Pornire, teste și harta codului: [README](../README.md).
Scenarii de verificare: [runbook](runbook/README.md).
