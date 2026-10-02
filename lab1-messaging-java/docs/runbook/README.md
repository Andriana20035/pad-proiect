# Pornire și verificare

1. Deschide proiectul Maven în IntelliJ; JDK 17 sau mai nou, working directory = proiect.
2. Rulează scripts/run-demo.ps1 -Mode tests sau toate testele din src/test/java.
3. Rulează DemoMain sau scripts/run-demo.ps1.
4. Caută pentru același messageId/correlationId:
   LOCAL_EFFECT_DONE → CRASH_BEFORE_ACK → RETRY → DEDUP_SKIP → ACK.
5. Verifică mesajul VERIFIED: effectCount=1, DLQ=0. Audit trebuie să aibă propriul ACK.

Scriptul salvează logul complet în data/demo.log. EXEMPLUL din example-correlated.log
este numai un fragment orientativ, nu dovada unei rulări curente.
DemoMain recreează instanța, iar ProcessCrashTest verifică oprirea reală a JVM
(exit 23), repornirea și recitirea efectului de pe disc.

## Interactiv

Pornește BrokerMain, InventoryConsumer, AuditConsumer și OrderProducer.
Meniul producerului: 1 Inventory, 2 Audit, 3 amândoi.
Introdu comanda, produsul și cantitatea pozitivă.
BrokerMain permite și consultarea cozilor (4) și DLQ (5).
Repetă aceeași comandă: PUBLISH_DEDUP, fără efect nou.
Schimbă produsul păstrând numărul: publicare respinsă.

## Cădere și restart

Pornește InventoryConsumer cu --crash-process sau scriptul cu -Mode inventory -Crash.
Trimite o comandă nouă. După CRASH_BEFORE_ACK, procesul se oprește cu codul 23.
Repornește Inventory fără argumentul de crash.
Trebuie să vezi DEDUP_SKIP, ACK și un singur efect în inventory.json.

Poți opri brokerul cu mesaje în așteptare; după restart, coada este recuperată.
Livrările în curs sunt reluate cu următoarea încercare sau trimise în DLQ la limită.
Nu șterge data/ între oprire și restart.

## Eșecuri verificate automat

- SocketPolicyTest: abonament duplicat, versiune invalidă, timeout ACK, FIFO,
  NACK permanent → DLQ imediat.
- DlqTest: prima încercare + două retry-uri → DLQ.
- RecoveryTest: broker repornit, mesaj neconfirmat recuperat înaintea următorului,
  istoricul solicitărilor și DLQ păstrate.
- ProcessCrashTest: două procese consumatoare, crash real, deduplicare persistentă,
  correlation ID identic în loguri.
- DeadlineTest: producerul nu așteaptă nelimitat un broker care nu răspunde.
- StorageFailureTest: commit eșuat fără confirmare falsă, retry sigur al efectului și
  refuzul de a înlocui datele corupte cu stare goală.

PUBLISHED confirmă acceptarea, ACK confirmă efectul fiecărui consumator.
Cozi zero nu reprezintă o dovadă suficientă fără ACK; DLQ reprezintă eșec, nu succes.
Fișierele corupte sau discul indisponibil cer remediere; aplicația nu resetează automat datele.
