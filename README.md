# Tracker Collector

Android app that scans application sheets (credit-card proposal lists, customer lists, …),
reads the table with on-device OCR and collects:

- **SL** (serial number from the document)
- **Name** (Applicant / Customer name)
- **Designation**
- **Mobile number**

Entries are stored on the phone. The mobile number is the unique key, so the same person is
**never saved twice** even if the same sheet is scanned again.

The collected list exports to **Excel (.xlsx)** and **Word (.docx)** with the columns
`SL | Name of Customer | Customer Designation | Mobile Number`.

## How to use
1. **Scan** – opens Google's document scanner (auto edge detection, crop, multi-page, gallery import).
2. The app reads every page, finds the table header (Name / Designation / Mobile / SL) and every
   Bangladeshi mobile number (`01XXXXXXXXX`), and shows the new entries for review.
   Duplicates are skipped automatically.
3. Tap **Save**. Tap any entry later to correct or delete it, or add one manually (+).
4. Tap **Excel** or **Word** – the file is saved to `Download/TrackerCollector/` and can be opened or shared.

You can also share a photo of a sheet from any app straight into Tracker Collector.

## How the table is read
`core/TableParser.kt` (pure Kotlin, unit-testable):
- ignores sideways text (e.g. another sheet lying rotated in the photo), signatures and stamps;
- straightens tilted photos;
- uses each mobile number as a row anchor and the header row for column boundaries,
  so multi-line names like "Mohammad / Nurul Amin" stay in one row;
- works without a header too (reads each row left-to-right).

## Build
- Android Studio / AndroidIDE: open the project and run `assembleDebug`.
- Every push to `main` builds the APK with GitHub Actions and publishes it under **Releases**.
  All builds are signed with the same key (`app/tracker-debug.keystore`), so a new APK installs
  over the old one without losing data.

Tech: Kotlin, ML Kit Document Scanner, ML Kit Text Recognition (offline), SQLite,
hand-written Office Open XML (no Apache POI).
