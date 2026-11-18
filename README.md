# Parked

**Parked** automatycznie zapisuje miejsce parkingowe, gdy telefon rozłączy się z wybranym
radiem samochodowym (Bluetooth). Aplikacja jest lokalna, otwarta i nie wymaga konta.

- Kotlin + Jetpack Compose + Material 3
- Room (historia i logi), DataStore (ustawienia i stan wyzwalacza)
- OpenStreetMap przez osmdroid — bez kluczy API i bez płatnych usług
- Android 10 (API 29) lub nowszy, kompilacja pod API 36

> Repozytorium jest prywatne. Gotowy APK pobierzesz z sekcji **Releases**
> (po zalogowaniu na konto z dostępem). Artefakt debug jest dostępny w każdym
> udanym uruchomieniu GitHub Actions.

---

## Funkcje

- Wybór jednego sparowanego urządzenia Bluetooth; inne urządzenia są ignorowane.
- Automatyczny zapis po rozłączeniu, z konfigurowalnym opóźnieniem (domyślnie 15 s).
  Lokalizacja jest pobierana **od razu w chwili rozłączenia**, a dopiero zapis czeka na
  koniec opóźnienia — dzięki temu punkt odpowiada miejscu, gdzie stało auto, a nie temu,
  gdzie doszedłeś przez te 15 sekund.
- Ponowne połączenie w trakcie odliczania anuluje zapis.
- Ochrona przed duplikatami: jeden zapis na cykl połączenia, unikalny `cycleId`
  w bazie oraz minimalny odstęp między automatycznymi zapisami.
- Lokalizacja: równoległy pomiar z fused Androida/Play (GPS + Wi-Fi + sieć komórkowa)
  i providerów systemowych, walidacja współrzędnych, dokładności i wieku, wybór
  najlepszego pomiaru z uwzględnieniem czasu, opcjonalnie awaryjna ostatnia znana lokalizacja.
- Historia: data, współrzędne, dokładność, źródło pomiaru (GPS/Fused/Sieć), notatka,
  opcjonalne zdjęcie.
- Ręczny zapis bieżącej lokalizacji i ręczne dodanie punktu ze współrzędnych.
- Mapa OpenStreetMap: czytelne pinezki wszystkich miejsc, wybieranie punktu dotknięciem,
  okrąg dokładności wokół wybranego punktu, bieżąca pozycja i przycisk nawigacji zewnętrznej.
- Wbudowany kompas do celu: tarcza reagująca na obrót telefonu, kierunek geograficzny
  (np. `NE`), namiar w stopniach i odległość w metrach do wybranego lub ostatniego miejsca.
- Eksport historii do **JSON**, **CSV** i **GPX** przez systemowy wybór pliku.
- Ekran diagnostyki: stan usługi, uprawnienia, test GPS, lokalne logi (max 300 wpisów).
- Brak powiadomień o zapisie i błędach. Jedyne powiadomienie to wymagane przez Androida
  ciche, niskiego priorytetu powiadomienie usługi monitorującej (można je ukryć
  w ustawieniach systemowych — monitoring nadal działa).
- Wznawianie monitorowania po restarcie telefonu i po aktualizacji aplikacji (best effort).
- Brak reklam, analityki, telemetrii, konta i wysyłania lokalizacji na serwer.

## Instalacja

1. Wejdź w [Releases](https://github.com/Bartixxx32/Parked/releases/latest) i pobierz
   `Parked-<wersja>.apk`.
2. Otwórz plik na telefonie. Android może poprosić o zgodę na instalację z tego źródła.
3. Zainstaluj i uruchom aplikację.

Build debug ma identyfikator `io.github.bartixxx32.parked.debug`, więc może współistnieć
z wersją release. Aktualizacje release instalują się na poprzednią wersję tylko wtedy,
gdy są podpisane tym samym kluczem — oficjalne wydania są.

## Pierwsze uruchomienie

1. **Połącz telefon z radiem samochodowym** w systemowych ustawieniach Bluetooth.
2. W aplikacji: **Ustawienia → Radio samochodowe → odśwież** i wybierz swoje radio.
3. Przyznaj uprawnienia:
   - Bluetooth (`BLUETOOTH_CONNECT` na Androidzie 12+),
   - lokalizacja dokładna lub przybliżona,
   - **lokalizacja w tle → „Zawsze pozwalaj”**.
   Aplikacja nie prosi o zgodę na powiadomienia.
4. Wyłącz optymalizację baterii i włącz autostart (patrz niżej).
5. Na pulpicie włącz **Monitorowanie**.

Od tej chwili po rozłączeniu z radiem aplikacja od razu rozpoczyna pomiar lokalizacji
(max. 20 s), a następnie po ustawionym czasie zapisuje ten punkt. Jeśli w międzyczasie
radio znów się połączy — zapis zostanie anulowany, a pobrany punkt odrzucony.

## Ważne ustawienia

| Ustawienie | Domyślnie | Znaczenie |
|---|---:|---|
| Opóźnienie po rozłączeniu | 15 s | Czas na ponowne połączenie; wtedy zapis jest anulowany. Sam pomiar startuje natychmiast. |
| Limit czasu GPS | 45 s | Po tym czasie podejmowana jest ostatnia próba / fallback. Dotyczy także fused. |
| Maks. dokładność | 100 m | Mniej dokładny pomiar jest odrzucany. |
| Fallback ostatniej lokalizacji | włączony | Awaryjny punkt, wyraźnie oznaczony w historii. |
| Maks. wiek fallbacku | 12 h | Starszy punkt nie jest używany. |

Parked nie ma ustawienia powiadomień o zapisie — takich powiadomień po prostu nie wysyła.

Zakresy są walidowane w DataStore; wartości spoza zakresu są przycinane.

## OnePlus / ColorOS / Android 16 — niezawodność

Android coraz agresywniej ogranicza pracę w tle. Parked używa usługi pierwszoplanowej
typu `connectedDevice|location`, dynamicznych broadcastów Bluetooth, cyklicznego
sprawdzania stanu, budzika WorkManagera i restartu po boocie — ale **żadna aplikacja
nie może zagwarantować**, że system jej nie zabije.

Na OnePlus z ColorOS wykonaj dodatkowo:

1. **Ustawienia → Aplikacje → Parked → Bateria → Bez ograniczeń**
   (lub „Nie optymalizuj”).
2. **Autostart**: Ustawienia → Aplikacje → Autostart → włącz Parked.
   Przycisk „Autostart” w aplikacji otwiera właściwy ekran, jeśli producent go udostępnia.
3. W panelu ostatnich aplikacji **zablokuj Parked** (kłódka), żeby nie znikała po
   „wyczyść wszystko”.
4. Zezwól na **aktywność w tle** w szczegółach aplikacji, jeśli ColorOS pokazuje
   taką opcję.
5. Ustaw lokalizację na **„Zawsze pozwalaj”**.
6. Nie wyłączaj Bluetooth ani nie włączaj trybu samolotu na dłużej.

Jeśli system i tak zatrzyma usługę, przy następnym otwarciu aplikacji Parked spróbuje
ją wznowić, a watchdog WorkManagera podejmie próbę co ok. 15 minut. Parked nie wysyła
powiadomień o takich zdarzeniach — szczegóły znajdziesz w Diagnostyce.

**Gwarancja zapisu nie istnieje.** W razie wątpliwości użyj przycisku
„Zapisz bieżącą lokalizację” na pulpicie.

## Jak to działa

```
połączenie z radiem        rozłączenie: pomiar        po opóźnieniu: zapis
        │                         │                            │
        ▼                         ▼                            ▼
   CONNECTED ───────────────► PENDING ───────────────────► CAPTURING
        ▲                         │                            │
        │   ponowne połączenie    │ anuluje zapis i pomiar     │ zapis gotowego fixu
        └─────────────────────────┘                            ▼
                                                          SAVED / błąd
```

- Pomiar lokalizacji startuje **natychmiast po rozłączeniu** (maks. 20 s) i jest
  trzymany w pamięci. Opóźnienie odkłada wyłącznie zapis, więc 15 s marszu od auta
  nie przesuwa współrzędnych.
- Do pomiaru używane są równolegle: fused Play Services (jeśli jest) oraz systemowe
  providery GPS, sieci/Wi-Fi i — od Androida 12 — systemowy `fused`. Dzięki temu
  w zabudowie punkt może pochodzić z Wi-Fi/sieci, a nie tylko z surowego GPS.
- Po broadcastcie Bluetooth stan jest sprawdzany od razu i ponownie po 1, 3, 6 i 10 s,
  więc chwilowe opóźnienie proxy nie czeka na 20-sekundowy poll.
- Aktualizacja nie przesuwa współrzędnych już zapisanych punktów — nowy algorytm
  dotyczy wyłącznie kolejnych zapisów. Każdy punkt przechowuje też swoje źródło pomiaru.
- Start usługi przy już rozłączonym radiu **nie zapisuje** punktu — nie wiadomo,
  kiedy auto zaparkowało.
- Powtarzane broadcasty połączenia/rozłączenia są idempotentne.
- Stan (`TriggerState`) jest zapisywany w DataStore po każdej zmianie; po restarcie
  procesu oczekujący zapis jest wznawiany tylko w oknie 10 minut.
- Cykl ma unikalny `cycleId` z indeksem unique w Room — nawet równoległy zapis nie
  utworzy duplikatu.
- Dwa automatyczne zapisy dzieli co najmniej 2 minuty (ochrona przed „trzepotaniem”
  radia).

Szczegóły: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Prywatność

- Wszystkie dane (historia, notatki, zdjęcia, logi, ustawienia) są przechowywane
  wyłącznie w prywatnym magazynie aplikacji.
- Brak reklam, analityki, crashlytics, telemetrii i kont użytkownika.
- Aplikacja nie wysyła współrzędnych do żadnego serwera.
- Jedyny ruch sieciowy to pobieranie kafelków mapy z serwerów OpenStreetMap, gdy
  otwarty jest ekran mapy (serwer widzi adres IP i obszar kafelków, jak każda strona).
- Eksport tworzy plik wyłącznie tam, gdzie wskażesz w systemowym selektorze.
- Backup Androida jest wyłączony; dane nie trafiają do chmury przez systemowy backup.

## Eksport

W historii wybierz ikonę pobierania i format:

- **JSON** — pełne dane, schemat wersjonowany (`schemaVersion: 1`).
- **CSV** — wygodny dla arkuszy; notatki są poprawnie cytowane.
- **GPX** — waypointy do nawigacji, zdjęć terenowych i innych aplikacji mapowych.

Pliki nie są zapisywane w repozytorium ani wysyłane automatycznie.

## Budowanie i testy

Projekt buduje się w GitHub Actions (Ubuntu x86_64), nie na Raspberry Pi.

- CI: `.github/workflows/android.yml`
  - `testDebugUnitTest`, `assembleDebug`, weryfikacja APK przez `aapt2` i `apksigner`,
    artefakt `Parked-debug-apk` + raporty testów.
- Release: `.github/workflows/release.yml`
  - uruchamiany tagiem `v*` lub ręcznie, podpisuje APK kluczem z sekretów GitHub,
    publikuje plik w GitHub Releases.

Wymagania lokalne (opcjonalnie): JDK 17+, Android SDK 36, Gradle wrapper 8.14.3.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### Sekrety podpisywania

W ustawieniach repozytorium (Actions → Secrets) ustawiane są:

| Sekret | Znaczenie |
|---|---|
| `PARKED_KEYSTORE_BASE64` | keystore PKCS12 zakodowany base64 (bez znaków nowej linii) |
| `PARKED_KEYSTORE_PASSWORD` | hasło keystore |
| `PARKED_KEY_ALIAS` | alias klucza |
| `PARKED_KEY_PASSWORD` | hasło klucza (może być takie samo) |

Bez tych sekretów release nie powstanie — workflow kończy się błędem, zamiast
opublikować niepodpisany plik.

## Testy

Testy jednostkowe JVM obejmują m.in.:

- połączenie → rozłączenie → zapis,
- krótką utratę połączenia i ponowne połączenie (anulowanie),
- powtarzane i zdublowane zdarzenia Bluetooth,
- restart usługi z oczekującym zapisem (wznowienie i odrzucenie starego),
- start przy już rozłączonym radiu (brak fałszywego zapisu),
- zmianę urządzenia i zmianę opóźnienia,
- brak GPS, niską dokładność, nieprawidłowe współrzędne, stary fallback,
- duplikat cyklu i minimalny odstęp między zapisami,
- punkt pobrany przy rozłączeniu zapisany później bez zmiany współrzędnych,
- eksport JSON/CSV/GPX (poprawność XML i cytowanie).

Scenariusze wymagające fizycznego telefonu (reboot, ubicie procesu przez ColorOS,
rzeczywiste połączenie z radiem) opisano w [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
jako checklistę manualną — nie da się ich wiarygodnie zasymulować w testach JVM.

## Rozwiązywanie problemów

| Objaw | Co sprawdzić |
|---|---|
| Brak zapisu po rozłączeniu | Czy monitoring jest włączony? Czy wybrane radio to to samo, z którym łączy się telefon? Czy usługa działa (Diagnostyka)? |
| „Usługa nie działa” | Wyłącz optymalizację baterii, włącz autostart, otwórz aplikację i wznów monitoring. |
| Zapis bardzo niedokładny | Zmniejsz limit dokładności, sprawdź GPS na zewnątrz; fallback jest oznaczony w historii. |
| Brak lokalizacji w tle | Uprawnienie musi mieć wartość „Zawsze pozwalaj”; na Androidzie 11+ to osobny ekran ustawień. |
| Brak mapy | Sprawdź internet; kafelki OSM wymagają sieci. Piny historii działają bez sieci. |
| Kompas nie obraca się | Telefon nie ma czujnika orientacji — strzałka nadal pokazuje kierunek względem północy. |
| Nie chcę powiadomienia o monitorowaniu | To wymagane przez Androida powiadomienie usługi pierwszoplanowej. Ukryj kanał w Ustawieniach → Powiadomienia; monitoring nadal działa. |
| Zdjęcie się nie dodaje | Wybierz obraz z pamięci urządzenia; limit 25 MB. |

## Struktura projektu

```
app/src/main/java/io/github/bartixxx32/parked/
├── bluetooth/       # bonded devices, wykrywanie połączenia
├── capture/         # silnik pobrania i walidacji lokalizacji
├── data/            # Room, DataStore, repozytoria, stan wyzwalacza
├── export/          # JSON / CSV / GPX
├── location/        # LocationManager + opcjonalny fused
├── monitor/         # FGS, boot receiver, watchdog
├── notifications/   # jedyny kanał: wymagane powiadomienie FGS
├── ui/              # Compose: pulpit, historia, mapa z kompasem, ustawienia, diagnostyka
└── util/            # uprawnienia, intencje systemowe
```

## Licencja i mapa

Kod: **GNU GPL v3.0** — plik [LICENSE](LICENSE).

Mapa: dane © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright),
licencja ODbL. Kafelki: OpenStreetMap Standard. Aplikacja nie jest powiązana
z OpenStreetMap Foundation.

Nazwa „Parked” jest robocza; identyfikator aplikacji to
`io.github.bartixxx32.parked`.
