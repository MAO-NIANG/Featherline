# Data export

Settings → **Export data** writes your recorded doses and blood tests to a file
you can open elsewhere. There are four actions across two shapes:

| Action | Format | Contains |
|---|---|---|
| Export as CSV | RFC 4180 CSV | Doses and blood tests |
| Export as PDF | A4 PDF report | Doses and blood tests |
| Export as JSON | Oyama-compatible JSON | Doses and blood tests, for another HRT app |
| Export as JSON | Featherline snapshot JSON | Everything the encrypted backup holds |
| Copy JSON | Either of the two JSON shapes | Straight to the clipboard, no file |

All of these are **plaintext**. They exist to be read by a person or imported by
another app — not to restore Featherline. For that, use **Backup to file**, which
writes the encrypted container described in [backup-format.md](backup-format.md).

> **The Featherline snapshot JSON cannot be restored in this app.** It is the
> same schema as a backup, without the encryption envelope, and the restore path
> accepts only encrypted backups. It is named `featherline-backup-plaintext-…`
> to make that hard to mistake, and the format picker says so on the option
> itself.

The code lives in
[`data/export`](https://github.com/mkx173/Featherline/tree/main/app/src/main/java/com/mkx/hrttracker/data/export):
`DataExportService` aggregates the repositories, `DataExportCsvEncoder` and
`DataExportPdfRenderer` render the two human-facing files, and
`OyamaJsonExporter` builds the interchange shape.

## CSV

One table, doses and blood tests merged into a single chronological history
rather than two appended blocks, so the file reads the way the history does.

| # | Column | Dose row | Blood-test row |
|---|---|---|---|
| 1 | Record type | "Dose" | "Blood test" |
| 2 | Date and time | ISO-8601 with offset | ISO-8601 with offset |
| 3 | Time zone | The zone the dose was logged in | The zone the sample was taken in |
| 4 | Category | Estradiol, Antiandrogen, … | *(empty)* |
| 5 | Name | Medicine name | Analyte code |
| 6 | Route | Oral, Injection, … | *(empty)* |
| 7 | Dose | e.g. "1½ tablet · 1.5 mg" | *(empty)* |
| 8 | Amount (mg) | The administered mass | *(empty)* |
| 9 | Value | *(empty)* | The number as entered |
| 10 | Unit | *(empty)* | pg/mL, nmol/L, … |
| 11 | Note | *(empty)* | The panel's note |

Three decisions worth knowing:

- **A UTF-8 BOM leads the file.** Excel on Windows reads BOM-less UTF-8 as the
  ANSI codepage, which turns every non-ASCII field into mojibake — including all
  four Chinese localizations of the headers and any medicine name you typed.
  The cost is that `pandas.read_csv` needs `encoding="utf-8-sig"`; the trade is
  the right way round for a file whose audience is someone opening it in a
  spreadsheet.
- **Records are separated by CRLF**, as RFC 4180 specifies.
- **Numbers are written without a locale's decimal separator.** A device set to
  German would otherwise produce `1,5` in the amount column, where the comma is
  the field separator.

A field containing a comma, a quote or a line break is quoted, and any embedded
quote is doubled, per the RFC.

## PDF

An A4 portrait report with a title block and two tables — medication doses, then
blood-test results. The tables are kept separate because the two record types
have disjoint columns; merging them would leave roughly 40% of every row blank
on paper.

Long tables repeat their column header on each new page and number the pages
(`Page 3 / 12`). Text wraps at spaces for Latin scripts and between characters
for CJK, which has no spaces to break on. A single record too tall for one page
is truncated rather than dropped — it still appears, with the lines that fit.

Chinese, Japanese and Korean text renders through the platform font fallback
chain, so no font is bundled. On an unusual OEM build that lacks the fallback,
glyphs would appear as empty boxes; the fix would be to ship a Noto subset, which
has not been necessary.

## JSON

### Oyama-compatible

The shape [Oyama's HRT Tracker](https://github.com/xunxunProjects/Oyama-s-HRT-Tracker)
reads and writes. Anything in that vocabulary survives a trip through Oyama,
Transmtf, or this app's own **Import external data**, which is the only export
here that another app can take over from.

Oyama compares its vocabulary with exact string equality rather than normalising
it, so a mis-cased value is not a parse error: an unrecognised `route` drops the
record, and an unrecognised `ester` is **silently rewritten to estradiol**. The
exporter therefore emits Oyama's own literals and nothing else:

- `route`: `oral`, `sublingual`, `injection`, `gel`, `patchApply`, `patchRemove`
- `ester`: `E2`, `EB`, `EV`, `EC`, `EN`, `EU`, `CPA`
- lab `unit`: `pg/ml`, `pmol/l`, `ng/dl`, `nmol/l`
- `timeH`: hours since the Unix epoch — an absolute count, not an offset from
  any anchor

#### What it leaves out

The dialog reports the exact counts before you commit, and the confirmation
message repeats them. Nothing is dropped quietly.

| What | Why |
|---|---|
| Testosterone doses | The importer's Oyama path skips them |
| Oral or sublingual esters other than estradiol and estradiol valerate | Only those two are accepted by mouth |
| Spironolactone, bicalutamide, finasteride, dutasteride, SERMs, GnRH agonists | Only cyproterone acetate is accepted from an Oyama source |
| User-defined (custom) medicines | They have no Oyama code |
| Progesterone, prolactin, FSH, LH, and custom analytes | Oyama pairs an analyte with its unit, and only E2 and T pairings exist |
| Two blood panels with the same timestamp carrying the same analyte | The importer groups results into panels by timestamp and would collapse them into one on re-import. The exporter counts the loss instead of inventing a timestamp |

Two further lossy behaviours are structural rather than omissions:

- **A dose with `count` greater than one is flattened.** Oyama has no count
  field, so a triple dose becomes one row carrying three times the unit amount.
  The number is right; the shape is not.
- **The time zone is not carried.** `timeH` is an absolute instant, so the
  receiving app re-stamps imported records with the device's current zone. The
  CSV and PDF exports do keep each record's original zone, so use those if the
  zone matters.

Estradiol and testosterone blood tests **are** exported losslessly regardless of
the unit you entered them in: the exporter declares the analytic's canonical unit
(pg/mL for E2, ng/dL for T) and writes the canonical value, so a result entered
in pmol/L or ng/mL arrives correctly rather than being dropped.

Patches round-trip in both of their forms — a release rate travels in
`extras.releaseRateUGPerDay`, and a total-mass patch in `doseMG`.

### Featherline snapshot

The same JSON `BackupSnapshot` schema the encrypted backup writes, at the same
`snapshotVersion`, produced by the same serializer — just without the envelope.
It is lossless and complete: doses, labs, medicines, groups and schedules,
stock, journal entries, notes, settings and weight. See
[backup-format.md](backup-format.md#unencrypted-exports) for why it cannot be
restored.

## File names

The timestamp is local, in `yyyy-MM-dd_HH-mm-ss`. The stems are ASCII and not
localized.

| Export | Name |
|---|---|
| CSV | `featherline-data-<timestamp>.csv` |
| PDF | `featherline-data-<timestamp>.pdf` |
| JSON, Oyama-compatible | `featherline-oyama-<timestamp>.json` |
| JSON, Featherline snapshot | `featherline-backup-plaintext-<timestamp>.json` |

The two JSON shapes get different stems deliberately: only one of them can be
read back, and identically named files would be impossible to tell apart later.

## Behaviour notes

- An export is refused with "There is no data to export" when there is nothing
  to write.
- Backing out of the file picker is not an error: the prepared payload is
  discarded and no message is shown.
- If the write fails, the partially written file is left in place rather than
  deleted. `CreateDocument` has already created or replaced that document by the
  time the export runs, so removing it would destroy a file you named.
- A prepared export is rendered into the cache directory first, because the
  picker returns asynchronously. Anything left behind by a crash is swept the
  next time an export runs, after six hours.
- **Copy JSON** refuses payloads over 512,000 characters and points at the file
  export instead: Android's clipboard is Binder-backed and most devices reject
  roughly a megabyte.
