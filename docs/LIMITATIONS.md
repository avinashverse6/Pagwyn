# Limitations

Pagwyn states plainly what it does not do. Nothing here is a placeholder that looks
like a working feature.

## By design

| Not supported | Reason |
|---|---|
| Annotations, highlights, drawing, form filling | Requires a PDF writer and a text-selection layer the platform renderer does not provide. Shipping a half-working editor would be worse than shipping none. |
| Text-to-speech | Without reliable text-position mapping, TTS would read whole pages from the start. Android's "Select to Speak" accessibility feature covers this use case better. |
| OCR for scanned documents | Tens of megabytes of native engine and models; incompatible with the APK-size and ARMv7 goals. Scanned pages simply have no text layer, and Pagwyn says so instead of showing an empty search. |
| Editing, reordering, merging, splitting PDFs | Pagwyn is a reader. |
| Cloud sync, accounts, bookmarks across devices | Contradicts the offline/privacy model. |
| Night-mode colour inversion of page content | Would alter the document. Pagwyn provides sepia/dark *surroundings* and a dimming overlay, never a tinted page. |

## Platform constraints

### Encrypted documents

`android.graphics.pdf.PdfRenderer` gives applications no API to supply a password.
The system's own "Open with" flow can prompt, but a third-party app cannot inject
credentials. Pagwyn asks the user for the password and attempts to open the document,
then reports honestly when the platform refuses it, and offers **Open in another app**.

Documents protected only by owner restrictions (no open password) usually open
normally.

### Table of contents

The platform does not expose a PDF's `/Outlines` tree to apps. Pagwyn derives a
contents list from the document's text layer by detecting heading-like lines. For
documents with unusual typography the outline may be sparse or absent; for scanned
documents it is absent, and the sheet says "This document has no table of contents".

### Text extraction fidelity

The built-in extractor handles the common cases (Flate/ASCII streams, `Tj`/`TJ`
operators, standard single-byte encodings, UTF-16BE strings). It does **not** decode:

- fonts with custom `/Differences` glyph maps (digits and letters usually still
  appear, but accent handling may be approximate),
- Type0/CID fonts without a `ToUnicode` map (common in CJK documents),
- text rendered as vector outlines (behaves like a scanned page).

Consequence: search and copy work well for typical Latin-script documents and may be
limited or unavailable for the cases above. Rendering is unaffected — rendering is
done by the platform and is always accurate.

### Very large documents

- Dimensions of the first 300 pages are primed when a document opens; the rest are
  measured as you scroll. This keeps opening instant, but a jump to page 2,500 of a
  huge file may briefly show a placeholder before the page is measured.
- Text extraction scans at most 4,000 pages and 24 MB per stream, with a hard time
  budget enforced by cancellation. Search in extremely large documents is best-effort.

### Rotation

Page rotation is a view-level operation (0/90/180/270), which keeps it instant. It is
remembered per document. It does not modify the PDF file.

## Environment-specific

| Situation | Behaviour |
|---|---|
| Document moved or deleted outside Pagwyn | The library entry shows "File is no longer accessible. Re-open it from its folder." The entry is not silently deleted. |
| Content provider revokes persisted access | Same as above; re-open from the picker restores it. |
| Non-file-backed provider (e.g. a network stream) | Rendering works through the file descriptor; text search and the table of contents are unavailable because they need random access to a real file. Stated in the UI as "no selectable text". |
| Device with a very small heap (< 64 MB) | Bitmap budget shrinks accordingly; pages may stay at preview resolution rather than crash. |
