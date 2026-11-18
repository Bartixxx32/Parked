# Architektura Parked

Dokument opisuje, jak aplikacja podejmuje decyzję o zapisie, gdzie trzyma stan i jakie
są granice niezawodności na nowoczesnym Androidzie.

## Warstwy

```
UI (Compose) ── MainViewModel ── AppContainer
                                   │
      ┌────────────────────────────┼─────────────────────────────┐
      ▼                            ▼                             ▼
 SettingsRepository          ParkingRepository            DiagnosticsRepository
 TriggerStateRepository      (Room: parking_spots)        (Room: diagnostics_events)
      │                            ▲
      │                            │ ParkingSink
      ▼                            │
 BluetoothMonitorService ── ParkingCaptureEngine ── CompositeLocationSource
      │                            │                        ├─ PlatformLocationSource
      ▼                            │                        └─ FusedLocationSource (opcjonalnie)
 BluetoothRepository              LocationValidator
      │
      ├─ dynamiczne broadcasty ACL / adapter / bond
      └─ poll co 20 s + proxy profili (HFP/A2DP/LE Audio)
```

`AppContainer` jest tworzony raz w `ParkedApp.onCreate` i współdzielony przez Activity,
usługę i odbiorniki. Baza Room oraz DataStore są singletonami w procesie.

## Przechowywanie danych

### Room — `parked.db`

`ParkingSpot`:

| Kolumna | Uwagi |
|---|---|
| `id` | PK autoincrement |
| `latitude`, `longitude` | double, walidowane przed zapisem |
| `accuracyMeters` | null tylko dla ręcznego dodania |
| `savedAtEpochMs` | czas zapisu |
| `fixTimeEpochMs` | czas pomiaru (może być starszy) |
| `note`, `photoPath` | notatka max 500 znaków, zdjęcie w prywatnym katalogu |
| `source` | `AUTO`, `MANUAL`, `FALLBACK` |
| `isFallback`, `isMock` | jawne oznaczenia |
| `provider`, `deviceAddress`, `deviceName` | kontekst |
| `cycleId` | **unikalny indeks** dla automatycznych zapisów |

`DiagnosticsEvent` przechowuje max 300 ostatnich wpisów (obcinane po insercie).

Zdjęcia trafiają do `filesDir/photos`, kopiowane strumieniowo z limitem 25 MB.
Ścieżka jest usuwana razem z wpisem; `deletePhotoFile` sprawdza canonical path, żeby
nie usunąć pliku spoza katalogu aplikacji.

### DataStore

- `parked_settings` — konfiguracja użytkownika; każda wartość jest przycinana do
  zdefiniowanego zakresu przy odczycie i zapisie.
- `parked_trigger_state` — faza, `cycleId`, adres urządzenia, deadline, czas ostatniego
  automatycznego zapisu. Zapis po każdej zmianie stanu.

Backup systemowy jest wyłączony (`allowBackup=false`, reguły data extraction), więc dane
nie opuszczają urządzenia przez chmurę.

## Automat stanów wyzwalacza

`ParkingTriggerStateMachine` jest czystą logiką (bez Androida) i ma pełne testy JVM.

Fazy:

| Faza | Znaczenie |
|---|---|
| `UNARMED` | brak aktywnego cyklu; start przy rozłączonym radiu tu trafia |
| `CONNECTED` | wybrane radio jest połączone; czekamy na rozłączenie |
| `PENDING` | rozłączenie wykryte; zaplanowano zapis na `pendingAtEpochMs` |
| `CAPTURING` | trwa pobieranie i walidacja lokalizacji |
| `SAVED` | cykl zakończony sukcesem lub świadomym pominięciem duplikatu |

### Reguły

1. Tylko przejście `CONNECTED → rozłączone` uzbraja zapis.
2. Powtarzane `CONNECTED` nie zwiększa `cycleId`; powtarzane `DISCONNECTED` nie tworzy
   drugiego terminu.
3. Ponowne połączenie w `PENDING` anuluje timer i przechodzi w nowy cykl `CONNECTED`.
4. Timer przed terminem nic nie robi; po terminie przechodzi w `CAPTURING` dokładnie raz.
5. `CAPTURING` nie jest przerywane kolejnym `DISCONNECTED`.
6. Sukces → `SAVED`, porażka → `UNARMED` (wymaga nowego połączenia; brak automatycznej
   pętli retry, która mogłaby zapisać złe miejsce).
7. Jeśli od ostatniego automatycznego zapisu minęło mniej niż 2 minuty, zapis jest
   pomijany (`SuppressDuplicate`) — ochrona przed flappingiem radia.
8. Start usługi:
   - `PENDING` w przyszłości → wznowienie tego samego deadline,
   - `PENDING` po terminie w oknie 10 min → natychmiastowy `CAPTURING`,
   - starszy `PENDING` → odrzucenie,
   - `CONNECTED`, ale radio fizycznie rozłączone → `UNARMED` (nie wiemy, kiedy auto
     zaparkowało; nie zapisujemy fałszywego punktu).
9. Zmiana wybranego urządzenia anuluje timer, capture i czyści stan.
10. Zatrzymanie monitorowania czyści stan i anuluje zaplanowaną pracę.

`cycleId` jest monotoniczny w obrębie bieżącego stanu; unikalny indeks w bazie jest
drugą linią obrony przy wyścigu procesów.

### Pomiar przy rozłączeniu (prefetch)

Po przejściu `CONNECTED → PENDING` usługa od razu uruchamia `ParkingCaptureEngine.acquire`
z jednym, ciągłym podejściem i limitem 20 s (`PREFETCH_MAX_SECONDS`). Gotowy,
zwalidowany fix jest trzymany w pamięci (`AcquiredFix`) i zapisywany dopiero po
upłynięciu opóźnienia przez `save` — bez ponownego pytania o lokalizację. Dzięki temu
zapisane współrzędne odpowiadają chwili rozłączenia, a nie chwili zakończenia timera.
Ponowne połączenie, zmiana urządzenia, zatrzymanie monitorowania i duplikat anulują
zarówno timer, jak i trwający pomiar. Jeśli prefetch zdąży zakończyć się błędem, wynik
jest ostateczny (brak natychmiastowego retry); jeśli z jakiegoś powodu brakuje prefetchu,
usługa wykonuje zwykłe `capture` z pełnym limitem czasu.

## Usługa monitorowania

`BluetoothMonitorService`:

- start jako foreground service z typem `connectedDevice|location`,
- jeśli `startForeground` z typem lokalizacji rzuci wyjątek (brak uprawnienia,
  ograniczenie systemu), następuje fallback do `connectedDevice`,
- powiadomienie usługi pierwszoplanowej (kanał o niskim priorytecie, ciche) z akcją
  „Zatrzymaj” — to jedyne powiadomienie w aplikacji; Parked nie wysyła powiadomień
  o zapisie ani błędach (stary kanał `parked_events` jest usuwany przy starcie),
- dynamiczny `BroadcastReceiver` na:
  - `ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`,
  - `ACTION_STATE_CHANGED` adaptera,
  - `ACTION_BOND_STATE_CHANGED`,
- filtr broadcastów: jeśli extra zawiera inne urządzenie niż wybrane, zdarzenie jest
  ignorowane,
- poll co 20 s jako uzupełnienie broadcastów (niektóre nakładki je gubią),
- `PowerManager.PARTIAL_WAKE_LOCK` tylko na czas capture (max 3 min),
- `START_STICKY`, `stopWithTask=false`,
- po restarcie procesu stan jest odtwarzany z DataStore.

Świeży stan jest propagowany do UI przez `MonitorStateHolder` (StateFlow w procesie).

### Start i ograniczenia tła

- Android 12+ zabrania startu FGS z tła bez wyjątku (np. z Activity, boot, PendingIntent).
- `BootReceiver` próbuje wznowić usługę po `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`
  i wybranych QuickBoot, ale tylko gdy monitoring jest włączony, urządzenie wybrane
  i uprawnienia nadal przyznane.
- `MonitorWatchdogWorker` co 15 min sprawdza, czy usługa żyje. Jeśli nie i brakuje
  uprawnień, zapisuje ostrzeżenie w Diagnostyce zamiast próbować w nieskończoność
  (bez powiadomienia).
- Jeśli system zabroni startu, zdarzenie trafia do logów; użytkownik wznawia monitoring,
  otwierając aplikację (start z Activity jest dozwolony).
- Żaden z tych mechanizmów nie jest gwarancją — to best effort. ColorOS może zabić
  proces mimo FGS, watchdoga i wyłączonej optymalizacji.

## Lokalizacja

`LocationSource` zwraca pojedynczy fix lub `null`; brak uprawnienia nie rzuca wyjątku.

`CompositeLocationSource` uruchamia równolegle dwa źródła:

1. `FusedLocationSource` — Play Services `getCurrentLocation` z
   `PRIORITY_HIGH_ACCURACY`, jeśli Play Services jest dostępne.
2. `PlatformLocationSource` — `LocationManager` z providerami GPS, network oraz
   (od API 31) systemowym `FUSED_PROVIDER`.

Oba źródła działają przez cały budżet czasu. Fix o dokładności ≤ 10 m i świeżości
w oknie −3…+10 s kończy oczekiwanie natychmiast. W przeciwnym razie wybierany jest
kandydat o najniższym wyniku `dokładność + 1 m/s × |czas pomiaru − start|`: dzięki
temu przyzwoity pomiar z chwili rozłączenia wygrywa z idealnym pomiarem zrobionym po
20 s, gdy użytkownik już odszedł od auta, a stary cache nie wygrywa tylko dlatego, że
ma niską deklarowaną dokładność. Fused i systemowy fused łączą GPS, Wi-Fi i sieć
komórkową, więc w miastach często biją surowy GPS. Wybrane źródło (`provider`) jest
zapisywane razem z punktem i widoczne w historii oraz na karcie mapy.

`PlatformLocationSource` kończy oczekiwanie wcześniej, gdy fix jest rzeczywiście dobry:
GPS ≤ 15 m albo dowolny provider ≤ 10 m. Dzięki temu nie zapisujemy pierwszego, często
sieciowego lub cache'owanego pomiaru. Wynik fused nie jest przyjmowany bezwarunkowo —
rywalizuje z platformą według powyższego wyniku.

`ParkingCaptureEngine`:

1. Dwa podejścia po `timeout/2` (min. 1 s); prefetch przy rozłączeniu używa jednego
   podejścia i timeoutu skróconego do 20 s.
2. Każdy fix przechodzi `LocationValidator`:
   - współrzędne skończone, w zakresach, nie `(0,0)`,
   - znana, nieujemna dokładność,
   - wiek ≤ 2 min (świeży pomiar), tolerancja 60 s na zegar z przyszłości,
   - dokładność ≤ limit użytkownika.
3. Jeśli brak świeżego fixu i fallback jest włączony: ostatnia znana lokalizacja
   (GPS/network/passive/fused), walidowana z limitem wieku fallbacku.
4. Zapis do Room z `source` i `isFallback`; wynik `Saved`, `Duplicate` lub `Failed`.
5. `acquire` i `save` są rozdzielone: fix pobrany przy rozłączeniu zachowuje swoje
   współrzędne i `fixTimeEpochMs`, a `savedAtEpochMs` odpowiada chwili faktycznego zapisu.
6. Błąd zapisu nie jest maskowany — trafia do logów (bez powiadomienia).

Walidacja odrzuca typowe „śmieciowe” punkty, ale nie jest systemem anty-spoofingowym:
mock location jest oznaczane, nie blokowane.

## Mapa i kompas

Ekran mapy używa osmdroid z kafelkami OSM. Pinezki są rysowane własnym `Overlay`
na canvasie (stały rozmiar, kolor zależny od fallbacku, halo dla wybranego punktu),
więc nie zależą od domyślnych baloników osmdroid. Wybrany punkt dostaje dodatkowo
delikatny okrąg dokładności: jego środek to dokładne współrzędne (czubek pinu),
a promień wynika z `accuracyMeters` i jest korygowany o szerokość geograficzną.
Dotknięcie pinu wybiera miejsce, a dolna karta pokazuje datę, współrzędne,
dokładność, źródło pomiaru, notatkę i przycisk nawigacji. Pozycja i zoom mapy są
zapisywane w `rememberSaveable`, więc przełączanie zakładek ich nie resetuje.

Kompas (`TargetCompass`) rysuje tarczę obróconą o bieżący azymut urządzenia i igłę
wskazującą namiar na cel. Azymut pochodzi z `TYPE_ROTATION_VECTOR`, a przy jego braku
z akcelerometru + magnetometru; uwzględniany jest obrót ekranu. Odległość i namiar
liczy `Location.distanceBetween` między bieżącą pozycją a wybranym (lub ostatnim)
miejscem. Cel jest pokazywany w metrach, stopniach i kierunku kardynalnym.

## Eksport

`ParkingExporter` jest czystym kodem JVM:

- JSON — ręczne budowanie z escapowaniem znaków kontrolnych, `schemaVersion: 1`,
- CSV — nagłówek, cytowanie przecinków/cudzysłowów/nowych linii,
- GPX 1.1 — poprawny XML, `wpt`, czas pomiaru, opis, symbol `Parking`.

Testy parsują GPX przez `DocumentBuilderFactory`, więc błędny XML wywali CI.

Eksport zapisuje przez `ContentResolver.openOutputStream(uri)` wskazany w SAF;
aplikacja nie ma uprawnienia do całej pamięci.

## Bezpieczeństwo i prywatność

- Brak `INTERNET` poza kafelkami mapy; brak wysyłania lokalizacji.
- `android:usesCleartextTraffic="false"`.
- Backup wyłączony, brak eksportu danych przez system.
- Zdjęcia i baza w prywatnym sandboxie aplikacji.
- Receiver Bluetooth zarejestrowany jako `RECEIVER_NOT_EXPORTED`.
- `BootReceiver` jest eksportowany (wymóg broadcastów systemowych), ale nie wykonuje
  nic poza sprawdzeniem własnych ustawień i próbą startu własnej usługi.
- Minimalne uprawnienia: lokalizacja, Bluetooth, FGS, boot, wake lock.
  `POST_NOTIFICATIONS` jest zadeklarowane w manifeście, ale aplikacja **nie prosi**
  o tę zgodę — wymagane powiadomienie FGS może wtedy pojawić się tylko w Menedżerze
  zadań, a monitoring nadal działa. Brak SMS, kontaktów, mikrofonu, kamery (zdjęcia
  przez SAF), dostępu do plików.

## Testy i checklista manualna

Automatyczne (JVM, uruchamiane w CI):

- stan wyzwalacza: wszystkie przejścia i przypadki brzegowe,
- walidator lokalizacji,
- silnik capture z fake source/sink (w tym zapis fixu pobranego przy rozłączeniu
  bez zmiany współrzędnych),
- eksportery,
- kompilacja wszystkich ekranów Compose i usług.

Manualne (telefon, najlepiej OnePlus/ColorOS):

1. Sparuj radio, wybierz w aplikacji, włącz monitoring.
2. Połącz → rozłącz: pomiar od razu, zapis po opóźnieniu, wpis w historii.
3. Połącz → rozłącz → połącz w czasie opóźnienia: brak zapisu, anulowanie.
4. Krótka utrata połączenia (radio poza zasięgiem) i powrót: brak duplikatu.
5. Wyłącz GPS: błąd lub fallback oznaczony jako awaryjny.
6. Ustaw limit dokładności na 10 m w pomieszczeniu: błąd zamiast złego punktu.
7. Restart telefonu z włączonym monitoringiem: usługa wznawia się (lub wpis w Diagnostyce).
8. Ubij proces z ostatnich aplikacji: FGS powinien przetrwać; jeśli nie — watchdog
   lub start przy otwarciu aplikacji.
9. Wielokrotne szybkie rozłączenia: minimum 2 min między automatycznymi zapisami.
10. Zablokowany telefon (Doze): zapis nadal następuje, jeśli system nie ubił usługi.
11. Eksport JSON/CSV/GPX i otwarcie GPX w innej aplikacji mapowej.
12. Dodanie zdjęcia, notatki i usunięcie wpisu (pliki zdjęć znikają).
13. Aktualizacja release na poprzednią wersję: instalacja przechodzi (ten sam klucz).

## CI/CD

- `android.yml`: push/PR na `main`, `workflow_dispatch`; JDK 21, SDK 36,
  testy, debug APK, weryfikacja podpisu i metadanych, artefakt, raporty testów.
- `release.yml`: tag `v*` lub ręczny dispatch; dekoduje keystore z sekretów,
  buduje `assembleRelease`, weryfikuje `apksigner`, publikuje APK i SHA-256 w Release.
- Klucz podpisujący jest stały i trzymany wyłącznie w sekretach GitHub; bez niego
  aktualizacje nie byłyby możliwe.
