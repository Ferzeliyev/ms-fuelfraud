# ms-fuelfraud

Excel faylları şəklində yüklənən xam yanacaq sensoru ölçmələrindən **yanacaq
oğurluğunu**, **yanacaq doldurulmasını** və **sensor küyünü** aşkarlayan mikroservis.

Servis **tam stateless-dir**: heç bir verilənlər bazası istifadə olunmur, heç nə
saxlanmır. Fayl yüklənir, yerindəcə analiz olunur və tam nəticə dərhal cavabda
qaytarılır.

## Funksional icmal

- Servis Excel workbook qəbul edir.
- **Hər iş vərəqi (worksheet) bir sütundan** — yanacaq səviyyəsi dəyərlərindən ibarətdir.
- **Hər sətir bir ölçmədir**; sətirlərin ardıcıllığı xronoloji ardıcıllıqdır.
- **Timestamp yoxdur.**
- **Hər iş vərəqi müstəqil emal olunur** — məs. `DUT standard`, `FL standard`,
  `FL Steal`, `Volvo standard`.

## Layihənin axını (flow)

Bir sorğunun başdan-sona yolu:

```
POST /api/v1/fuel-fraud/analyze           (multipart, `file` sahəsi)
POST /api/v1/fuel-fraud/analyze/export    (eyni giriş, nəticə .xlsx faylı kimi)
        │
        ▼
FuelFraudController ──► FuelAnalysisService.analyze(file) / analyzeToExcel(file)
        │
        ▼
1. ExcelParsingService  — workbook SAX ilə axın şəklində (streaming) oxunur; hər
   vərəq üçün ölçmələr primitiv massivlərə (MeasurementSeries: double[] + int[])
   yığılır — hər ölçmə üçün ayrıca obyekt yaradılmır
        │
        ▼  hər iş vərəqi üçün müstəqil:
2. ThresholdCalculator  — hər vərəq üçün adaptiv hədd delta histoqramındakı
                          küy/hadisə boşluğundan (gap) hesablanır
3. FuelEventDetector    — ardıcıl sətirlər üzrə yoxlama: delta[i] = level[i] −
                          level[i−1] hədlə müqayisə olunur; yalnız bilavasitə qonşu,
                          eyni istiqamətli hədd-üstü delta-lar bir hadisəyə birləşir
        │
        ▼
4. FraudAlertClient     — hər THEFT hadisəsi üçün bildiriş göndərilir
                          (hazırkı implementasiya loglayır)
        │
        ▼
5. Cavab                — `analyze`: AnalysisResultDto (ümumi cəmlər + hər vərəq üzrə
                          hesabat + bütün hadisələr).
                          `analyze/export`: annotasiyalı .xlsx (aşağıya bax).
                          Heç nə heç yerdə saxlanmır.
```

Analiz uğursuz olarsa (yanlış fayl, oxunmayan workbook və s.),
`GlobalExceptionHandler` uyğun HTTP statusu ilə strukturlaşdırılmış `ApiError`
cavabı qaytarır.

## Texnologiya yığını

- Java 21, Spring Boot 3.3
- Gradle
- Apache POI (Excel oxunması)
- Lombok
- springdoc-openapi (Swagger UI), Spring Boot Actuator

## Arxitektura

Məsuliyyətlərin aydın ayrılması ilə təmiz, laylı arxitektura:

```
az.ady.fuelfraud
├── client       // xarici inteqrasiyalar (fırıldaq bildirişləri)
├── config       // Spring və properties konfiqurasiyası
├── controller   // nazik REST controller-lər (biznes məntiqi yoxdur)
├── dto          // API + daxili ötürmə/hesablama modelləri
├── enums        // domen enumerasiyaları
├── exception    // xüsusi exception-lar + qlobal handler
├── service      // biznes məntiqi (interfeys + impl)
│   └── filter   // qoşula bilən küy filtri strategiyaları
└── util         // stateless köməkçilər
```

Əsas dizayn qərarları:

- **Stateless dizayn** — persistensiya layı yoxdur (entity, repository, verilənlər
  bazası, migrasiya yoxdur); hər sorğu öz-özlüyündə tamdır.
- **SOLID / Strategy pattern** — aşkarlama alqoritmi (`FuelEventDetector`), hədd
  hesablanması (`ThresholdCalculator`) və küy filtrləri (`NoiseFilter`) strategiya
  interfeyslərinin arxasındadır; alternativlər çağıran kodu dəyişmədən qoşula bilər.
- Hər yerdə **constructor injection** (Lombok `@RequiredArgsConstructor` vasitəsilə).
- **Controller-lərdə biznes məntiqi yoxdur** — yalnız servis layına ötürürlər.
- Mühərrikin tənzimləmələri **xaricə çıxarılıb və validasiya olunur** (`fuelfraud.*`).
- **Performans birinci dərəcəli tələbdir** — streaming oxunuş/yazılış, primitiv
  massivlər, minimal allokasiya (bax: [Performans](#performans)).

## Aşkarlama pipeline-ı

Tam **data əsaslıdır** — heç bir hədd hardcode edilmir. Hər iş vərəqinə müstəqil tətbiq
olunur:

### Mərhələ 0 — küyün təmizlənməsi (qoşula bilən filtrlər, Strategy pattern)

Hadisə aşkarlanmasından əvvəl xam seriya `NoiseFilterChain`-dən keçir. Hər
`NoiseFilter` Spring bean-i öz `@Order`-inə uyğun tətbiq olunur — yeni filtr
(Kalman, Savitzky–Golay, …) əlavə etmək üçün sadəcə yeni bean lazımdır:

1. **`MedianFilter`** (`@Order(1)`) — mərkəzləşdirilmiş median pəncərə
   (`fuelfraud.median-size`); tək nümunəlik sıçrayışları silir, real səviyyə
   dəyişikliklərini qoruyur.
2. **`MovingAverageFilter`** (`@Order(2)`) — mərkəzləşdirilmiş sürüşən orta
   (`fuelfraud.window-size`); qalıq titrəyişləri hamarlayır.

Pəncərəni `1` etmək həmin filtri söndürür.

### Mərhələ 1 — adaptiv hədd (`AdaptiveThresholdCalculator`)

1. **Delta-lar** — `delta[i] = level[i] − level[i−1]`.
2. **Mütləq delta-lar** — `|delta|`; `noiseTolerance`-dən kiçik dəyərlər dərhal atılır.
3. **Delta histoqramı** — qalan mütləq delta-lar `[0, max]` üzərində bin-lənir.
4. **Ən böyük boşluğun tapılması** — sensor küyü kiçik delta-ların sıx klasterini,
   real hadisələr isə nadir böyük delta-ları əmələ gətirir. Dolu bin-dən əvvəl gələn ən
   uzun boş bin ardıcıllığı bu ikisini ayıran boşluqdur; ardıcıllıq histoqramın əvvəlindən
   də başlaya bilər (tolerans altındakı küy delta-ları binlənmədən əvvəl atıldığı üçün küy
   klasteri konseptual olaraq diapazonun altındadır — məsələn, yeganə əhəmiyyətli delta
   tək bir oğurluq olduqda).
5. **Avtomatik hədd** — həmin boşluğun mərkəzində yerləşdirilir və
   `[minThreshold, maxThreshold]` aralığına sıxılır (clamp). Beləliklə hər dataset
   (`DUT standard`, `FL Steal`, `Volvo standard`, …) öz datasından hesablanmış hədd
   alır. Əhəmiyyətli boşluq yoxdursa (`minGapFraction`), vərəq yalnız küydən ibarət
   sayılır — heç bir hadisə qeydə alınmır.

### Mərhələ 2 — ardıcıl sətirlər üzrə hadisə aşkarlanması

Aktiv strategiya `ConsecutiveDeltaFuelEventDetector`-dir: yoxlama **ardıcıl
ölçmələr üzərində** aparılır və hadisə sıçrayışın real baş verdiyi sətirlərə
bağlanır:

6. **Ardıcıl fərq** — hər sətir üçün `delta[i] = level[i] − level[i−1]` hesablanır
   (xam seriya üzərində — hamarlama sıçrayışı qonşu sətirlərə "yaxardı" və sətir
   dəqiqliyini pozardı; küyə davamlılığı hədd təmin edir).
7. **Təsnifat** — `delta > +threshold` → `REFUEL` (əlavə edilib);
   `delta < −threshold` → `THEFT` (oğurlanıb); qalan hər şey küydür.
8. **Birləşdirmə (yalnız bilavasitə qonşu)** — yalnız **dalbadal gələn**, eyni
   istiqamətli hədd-üstü delta-lar bir hadisəyə birləşir (2–3 sətrə yayılmış bir
   doldurma bir hadisədir). Aralarında normal sətirlər olan ayrı sıçrayışlar heç
   vaxt birləşdirilmir — hər biri öz sətir aralığı ilə ayrıca hadisədir.
9. **Nəticə** — hər hadisə üçün başlanğıc/son **ardıcıl** Excel sətirləri,
   əvvəlki/sonrakı həcm və fərq qeydə alınır.

> Alternativ strategiya: `StatefulFuelEventDetector` (FSM: `IDLE → EVENT_STARTED →
> TRACKING → STABLE → FINISHED`) kod bazasında qalır — çoxlu kiçik delta-ya yayılmış
> **tədricən** oğurluq/doldurma olan datasetlər üçün nəzərdə tutulub (stabil
> səviyyələr arası fərq + anchor drift watchdog). `stabilityWindowSize`,
> `startSensitivitySigma`, `stabilitySigma` parametrləri və küy filtrləri
> (`NoiseFilterChain`) yalnız bu strategiyaya aiddir. Aktivləşdirmək üçün bean-i
> `@Primary` etmək kifayətdir.

### Konfiqurasiya (`application.yml`, `fuelfraud.*` — `FuelFraudProperties`)

Bütün açarlar flat-dır və startup-da validasiya olunur:

| Parametr | Mənası | Default |
|---|---|---|
| `fuelfraud.noise-tolerance` | bu qiymətdən (L) böyük olmayan mütləq delta-lar binlənmədən əvvəl küy kimi atılır | 0.5 |
| `fuelfraud.histogram-bin-count` | histoqram bin sayı; 0 = avtomatik (√n qaydası, min 10) | 0 |
| `fuelfraud.min-gap-fraction` | küy/hadisə boşluğunun histoqram diapazonunda minimal payı | 0.2 |
| `fuelfraud.min-threshold` | adaptiv həddin aşağı sərhədi (L) | 1.0 |
| `fuelfraud.max-threshold` | adaptiv həddin yuxarı sərhədi (L) | 100.0 |
| `fuelfraud.median-size` | median filtrin pəncərəsi (tək ədəd; 1 = söndürülüb) — yalnız FSM strategiyası | 5 |
| `fuelfraud.window-size` | sürüşən orta filtrin pəncərəsi (1 = söndürülüb) — yalnız FSM strategiyası | 5 |
| `fuelfraud.stability-window-size` | stabil səviyyə pəncərəsi (FSM) | 15 |
| `fuelfraud.start-sensitivity-sigma` | FSM başlanğıc həssaslığı (küy siqmasının misli) | 3.0 |
| `fuelfraud.stability-sigma` | FSM stabillik meyarı (siqma misli) | 2.0 |

Confidence balı sadə heuristikadır: hadisə həcminin həddə nisbəti —
`clamp(həcm / (2 × hədd), 0, 1)`; düz həddə düşən hadisə 0.5, həddin 2 mislindən
yuxarısı 1.0 alır. Bal yalnız məlumat xarakterlidir — heç bir hadisə balina görə
atılmır.

## Hesabat

`POST /api/v1/fuel-fraud/analyze` birbaşa tam nəticəni qaytarır:

- **ümumi xülasə** — fayl adı, vərəq sayı, ümumi ölçmə sayı, ümumi
  doldurma/oğurluq sayları, ümumi doldurulmuş və oğurlanmış litrlər, analiz vaxtı;
- **hər iş vərəqi üçün bir hesabat** — ölçmə sayı, hesablanmış hədd, küy səviyyəsi
  (siqma) və küy hərəkətlərinin sayı, doldurma/oğurluq sayları, doldurulmuş/oğurlanmış
  litrlər və hadisələrin siyahısı — hər biri tip (`THEFT`/`REFUEL`), başlanğıc/son
  **Excel sətri**, başlanğıc yanacaq, son yanacaq, işarəli yanacaq fərqi, qüvvədə olan
  adaptiv hədd və **etibarlılıq balı** (confidence, 0–1) ilə.

Aşkarlanmış hər `THEFT` hadisəsi üçün `FraudAlertClient` vasitəsilə bildiriş göndərilir
(hazırkı `LoggingFraudAlertClient` implementasiyası loglayır; real inteqrasiya üçün
interfeysin yeni implementasiyası kifayətdir).

Excel oxuyucusu `.xlsx` (və köhnə `.xls`) formatını çoxlu vərəqlə dəstəkləyir, ölçmələri
yalnız **A sütunundan** oxuyur (digər sütunlardakı kənar dəyərlər ölçmə sayılmır), yalnız
rəqəmsal dəyərləri oxuyur, boş sətirləri səssiz ötürür, yanlış xanaları (qeyri-rəqəm,
mənfi, NaN — sayılır və loglanır) nəzərə almır və hər ölçməni validasiya edir. Hər
oxunmuş dəyər izlənilə bilmə üçün orijinal 1-əsaslı Excel sətir nömrəsini saxlayır.

## Analiz olunmuş Excel (export)

`POST /api/v1/fuel-fraud/analyze/export` eyni faylı analiz edib nəticəni **annotasiyalı
`.xlsx` faylı** kimi qaytarır (`<ad>-analyzed.xlsx` adı ilə yüklənir):

- **hər mənbə vərəqi** bu sütunlarla təkrar yaradılır:
  `Sətir | Yanacaq (L) | Fərq (L) | Hadisə`;
- **`Fərq (L)` və `Hadisə` formuladır, statik dəyər deyil** — `Fərq` ardıcıl
  ölçmələrin fərqini hesablayır (`=B3-B2`), `Hadisə` isə vərəqin öz **hədd xanasına**
  (`G1`, redaktə oluna bilir) istinad edir: `Fərq > hədd` → `Əlavə edilib`,
  `Fərq < −hədd` → `Oğurlanıb`. Excel-də həddi dəyişən kimi bütün təsnifat **avtomatik
  yenilənir**;
- **sətir rəngləməsi conditional formatting ilə** eyni hədd xanasına bağlıdır —
  yaşıl = əlavə edilib (doldurma), qırmızı = oğurlanıb; hədd dəyişəndə rənglər də
  dərhal yenilənir;
- hər vərəqin `G1` xanasına həmin vərəq üçün **adaptiv hesablanmış hədd** yazılır
  (küy/hadisə boşluğu analizindən); hədd tapılmayanda (yalnız küy) `G1 = 0` olur və
  formulalar/rəngləmə qəsdən heç nəyi işarələmir — Excel-də `G1`-ə müsbət qiymət
  yazan kimi təsnifat aktivləşir;
- əvvəldə **`Summary` vərəqi** — bütün aşkarlanmış hadisələrin siyahısı:
  `Vərəq | Başlanğıc sətir | Son sətir | Əvvəlki həcm (L) | Sonrakı həcm (L) |
  Hadisə | Dəyişən həcm (L)`; hər sətir hadisə tipinə görə rənglənir
  (yaşıl `Əlavə edilib` / qırmızı `Oğurlanıb`).

Workbook **SXSSF ilə streaming** yazılır — milyonlarla sətirli vərəqlərdə də yaddaş
sabit qalır.

## API

| Metod | Yol | Təsvir |
|---|---|---|
| `POST` | `/api/v1/fuel-fraud/analyze` | Multipart Excel yükləməsi (`file` sahəsi); **tam analiz nəticəsini** JSON qaytarır |
| `POST` | `/api/v1/fuel-fraud/analyze/export` | Eyni giriş; **annotasiyalı `.xlsx`** qaytarır (rənglənmiş sətirlər + Summary vərəqi) |

Nəticə saxlanmadığı üçün ayrıca sorğu (id ilə axtarış, tarixçə və s.) endpointləri
yoxdur — hər şey `analyze` / `analyze/export` cavabındadır.

Tam OpenAPI sənədləşməsi (sorğu/cavab sxemləri, xəta kodları) springdoc tərəfindən
generasiya olunur (default port `8090`):

- Swagger UI: `http://localhost:8090/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8090/v3/api-docs`
- Health: `http://localhost:8090/actuator/health`

Nümunə:

```bash
# JSON nəticə
curl -F "file=@sensors.xlsx" http://localhost:8090/api/v1/fuel-fraud/analyze

# Annotasiyalı Excel
curl -F "file=@sensors.xlsx" -o sensors-analyzed.xlsx \
     http://localhost:8090/api/v1/fuel-fraud/analyze/export
```

```json
{
  "fileName": "sensors.xlsx",
  "sheetCount": 2,
  "totalMeasurements": 4300,
  "refuelCount": 3,
  "theftCount": 1,
  "totalRefueledLiters": 312.5,
  "totalStolenLiters": 47.2,
  "analyzedAt": "2026-07-06T12:34:56Z",
  "reports": [
    {
      "sheetName": "FL Steal",
      "measurementCount": 2150,
      "thresholdLiters": 8.4,
      "noiseLevelLiters": 1.2,
      "noiseMovementCount": 214,
      "refuelCount": 1,
      "theftCount": 1,
      "totalRefueledLiters": 105.0,
      "totalStolenLiters": 47.2,
      "events": [
        {
          "sheetName": "FL Steal",
          "eventType": "THEFT",
          "startRow": 1042,
          "endRow": 1051,
          "startFuel": 231.4,
          "endFuel": 184.2,
          "fuelDifference": -47.2,
          "threshold": 8.4,
          "confidenceScore": 0.97
        }
      ]
    }
  ]
}
```

## Performans

Tələb: **1 milyon ölçmə ≤ 5 saniyədə, ≤ 512 MB heap ilə** emal olunmalıdır.
Bunun üçün pipeline başdan-sona axın (streaming) və primitiv massivlər üzərində
qurulub:

- **Streaming oxunuş** — `.xlsx` vərəqləri SAX event API (`XSSFReader`) ilə oxunur;
  workbook DOM-u heç vaxt yaddaşa yığılmır, sətir sayından asılı olmayaraq yaddaş
  sabitdir.
- **Obyektsiz data modeli** — ölçmələr `MeasurementSeries` içində primitiv
  massivlərdə (`double[]` səviyyələr + `int[]` sətir nömrələri) daşınır; milyon
  ölçmə üçün milyon obyekt yaradılmır, boxing yoxdur.
- **Allokasiyasız filtrlər** — median filtr tək təkrar istifadə olunan pəncərə
  buferi ilə, sürüşən orta prefiks cəmlərlə O(n) işləyir.
- **Streaming yazılış** — annotasiyalı workbook `SXSSFWorkbook` ilə pəncərə-pəncərə
  diskə axıdılır.

### Benchmark

```bash
gradle benchmark                     # 1M nümunə, heap 512 MB-a sıxılıb
gradle benchmark -Psamples=2000000   # fərqli nümunə sayı
```

Benchmark (`src/test/.../DetectionBenchmark`) iki ssenarini ayrıca ölçür: yalnız
aşkarlama (filtrlər + hədd + FSM) və tam yol (streaming parse → detect → streaming
export), pik heap istifadəsi ilə birlikdə. Ölçülmüş nəticələr (dev maşını, 1M nümunə,
512 MB heap):

| Mərhələ | Vaxt |
|---|---|
| Aşkarlama (filtrlər + hədd + FSM) | ~160 ms |
| Streaming parse (1M sətirlik .xlsx) | ~1.2 s |
| **Parse + detect (emal cəmi)** | **~1.4 s — 5 s büdcəsi daxilində** |
| Pik heap | ~480 MB ≤ 512 MB |

## İşə salınma

> Gradle wrapper JAR-ı repoya daxil edilməyib. Bir dəfə `gradle wrapper` əmri ilə
> (və ya IDE vasitəsilə) yaradın, sonra `./gradlew` istifadə edin.

Lokal (verilənlər bazası tələb olunmur):

```bash
gradle bootRun
# http://localhost:8090
```

Docker ilə (konteynerdə port `8080`-dir):

```bash
docker compose up --build
# http://localhost:8080
```

Konfiqurasiya environment dəyişənləri ilə: `SERVER_PORT` (default `8090`),
`SPRING_PROFILES_ACTIVE` (default `dev`).
