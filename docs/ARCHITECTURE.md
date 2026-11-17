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

## Usługa monitorowania

`BluetoothMonitorService`:

- start jako foreground service z typem `connectedDevice|location`,
- jeśli `startForeground` z typem lokalizacji rzuci wyjątek (brak uprawnienia,
  ograniczenie systemu), następuje fallback do `connectedDevice`,
- powiadomienie statusowe (kanał o niskim priorytecie) z akcją „Zatrzymaj”,
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
  uprawnień, pokazuje powiadomienie zamiast próbować w nieskończoność.
- Jeśli system zabroni startu, użytkownik dostaje powiadomienie i może otworzyć aplikację
  (wtedy start z Activity jest dozwolony).
- Żaden z tych mechanizmów nie jest gwarancją — to best effort. ColorOS może zabić
  proces mimo FGS, watchdoga i wyłączonej optymalizacji.

## Lokalizacja

`LocationSource` zwraca pojedynczy fix lub `null`; brak uprawnienia nie rzuca wyjątku.

`CompositeLocationSource`:

1. Jeśli Play Services jest dostępne, próbuje fused `getCurrentLocation`
   z budżetem połowy timeoutu.
2. Równolegle/następnie używa `LocationManager` z providerami GPS i network.
3. Wybiera lepszy fix (mniejsza dokładność, potem świeżość).

`ParkingCaptureEngine`:

1. Dwa podejścia po `timeout/2` (min. 1 s).
2. Każdy fix przechodzi `LocationValidator`:
   - współrzędne skończone, w zakresach, nie `(0,0)`,
   - znana, nieujemna dokładność,
   - wiek ≤ 2 min (świeży pomiar), tolerancja 60 s na zegar z przyszłości,
   - dokładność ≤ limit użytkownika.
3. Jeśli brak świeżego fixu i fallback jest włączony: ostatnia znana lokalizacja
   (GPS/network/passive/fused), walidowana z limitem wieku fallbacku.
4. Zapis do Room z `source` i `isFallback`; wynik `Saved`, `Duplicate` lub `Failed`.
5. Błąd zapisu nie jest maskowany — trafia do logów i powiadomienia.

Walidacja odrzuca typowe „śmieciowe” punkty, ale nie jest systemem anty-spoofingowym:
mock location jest oznaczane, nie blokowane.

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
- Minimalne uprawnienia: lokalizacja, Bluetooth, powiadomienia, FGS, boot, wake lock.
  Brak SMS, kontaktów, mikrofonu, kamery (zdjęcia przez SAF), dostępu do plików.

## Testy i checklista manualna

Automatyczne (JVM, uruchamiane w CI):

- stan wyzwalacza: wszystkie przejścia i przypadki brzegowe,
- walidator lokalizacji,
- silnik capture z fake source/sink,
- eksportery,
- kompilacja wszystkich ekranów Compose i usług.

Manualne (telefon, najlepiej OnePlus/ColorOS):

1. Sparuj radio, wybierz w aplikacji, włącz monitoring.
2. Połącz → rozłącz: zapis po opóźnieniu, powiadomienie, wpis w historii.
3. Połącz → rozłącz → połącz w czasie opóźnienia: brak zapisu, anulowanie.
4. Krótka utrata połączenia (radio poza zasięgiem) i powrót: brak duplikatu.
5. Wyłącz GPS: błąd lub fallback oznaczony jako awaryjny.
6. Ustaw limit dokładności na 10 m w pomieszczeniu: błąd zamiast złego punktu.
7. Restart telefonu z włączonym monitoringiem: usługa wznawia się (lub powiadomienie).
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
