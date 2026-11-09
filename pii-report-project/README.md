# PII Tokenizer

A small Spring Boot web app that finds personal data (PII) in a PDF, replaces each value with a token such as `[NAME_1]` or `[EMAIL_2]`, and returns a new PDF you can download.

```
Input:   My name is Rahul Sharma. I live in Delhi. Email: rahul@example.com
Output:  My name is [NAME_1]. I live in [LOCATION_1]. Email: [EMAIL_1]
```

The same value always gets the same token within one document, so the output stays readable: if "Rahul Sharma" appears five times, all five become `[NAME_1]`.

## Features

- Upload a PDF from a simple web page (click or drag and drop, up to 25 MB)
- Detects and tokenizes:

  | Type       | Example                         | How it is found                                         |
  |------------|---------------------------------|---------------------------------------------------------|
  | `NAME`     | Rahul Sharma                    | Cue words: "My name is", "Name:", "Mr.", "S/O", "Dear"  |
  | `LOCATION` | Delhi, Maharashtra              | List of Indian cities/states, and "I live in ..."       |
  | `ADDRESS`  | 12 MG Road, Sector 5            | Lines starting with "Address:"                          |
  | `PINCODE`  | 110001                          | After "Pin Code:", "Pincode", "Postal Code"             |
  | `DOB`      | 12/04/1998                      | After "DOB:", "Date of Birth", "Born on"                |
  | `EMAIL`    | rahul@example.com               | Format                                                  |
  | `PHONE`    | +91 9876543210                  | Indian mobile number format                             |
  | `AADHAAR`  | 2345 6789 0123                  | 12-digit Aadhaar format                                 |
  | `PAN`      | ABCDE1234F                      | PAN format                                              |
  | `CARD`     | 4111 1111 1111 1111             | 13-19 digits that pass the Luhn checksum                |
  | `IFSC`     | HDFC0001234                     | IFSC format                                             |

- Shows a summary of how many values of each type were replaced
- Download the tokenized PDF with one click
- Rejects non-PDF and password-protected files with a clear message

## Tech stack

- Java 17+, Spring Boot 4 (Spring Web MVC)
- Apache PDFBox 3 for reading and writing PDFs
- Plain HTML, CSS and JavaScript frontend (no build step), served by Spring Boot

## Getting started

### Prerequisites

- JDK 17 or newer
- No separate Maven install needed; the project includes the Maven wrapper (`mvnw`)

### Run locally

```bash
./mvnw spring-boot:run
```

Then open http://localhost:8081 (the port is set by `server.port` in `src/main/resources/application.properties`).

### Build a runnable jar

```bash
./mvnw clean package
java -jar target/pii-report-project-0.0.1-SNAPSHOT.jar
```

## Using the app

1. Open the app in your browser.
2. Click the upload box (or drag a PDF onto it) and choose your file.
3. Click **Tokenize PDF**.
4. Check the summary table, then click **Download tokenized PDF**.

## API

### `POST /api/tokenize`

Upload a PDF as multipart form data with the field name `file`.

```bash
curl -F "file=@document.pdf" http://localhost:8081/api/tokenize
```

Response:

```json
{
  "fileId": "6a02122b-73aa-46e5-b7bc-5cc1d8ba99b0",
  "originalName": "document.pdf",
  "downloadName": "document_tokenized.pdf",
  "pages": 1,
  "totalReplaced": 7,
  "uniqueValues": 6,
  "countsByType": { "EMAIL": 2, "PHONE": 1, "AADHAAR": 1, "PAN": 1, "CARD": 1, "IFSC": 1 },
  "downloadUrl": "/api/tokenize/6a02122b-73aa-46e5-b7bc-5cc1d8ba99b0/download"
}
```

Errors return HTTP 400 with `{ "error": "..." }`, for example when the file is not a PDF or is password protected.

### `GET /api/tokenize/{fileId}/download`

Downloads the tokenized PDF.

```bash
curl -o out.pdf http://localhost:8081/api/tokenize/<fileId>/download
```

### `POST /api/detect`

Runs detection on plain text and returns every match with its position. Useful for testing rules.

```bash
curl -H "Content-Type: application/json" \
     -d '{"text":"My name is Rahul Sharma and I live in Pune"}' \
     http://localhost:8081/api/detect
```

### `GET /ping`

Health check. Returns `pong`.

## Project structure

```
src/main/java/org/project/piireportproject/
  PiiReportProjectApplication.java   Spring Boot entry point
  controller/
    TokenizeController.java          PDF upload and download endpoints
    DetectController.java            Text detection endpoint
    FileController.java              Raw file upload endpoint
    HealthController.java            /ping
  service/
    PiiDetector.java                 All detection rules
    PdfTokenizationService.java      Reads the PDF, replaces PII, writes the new PDF
    FileStorageService.java          Saves and loads files in the storage folder
  model/
    PiiMatch.java                    One detected value (type, value, position)
    TokenizationResult.java          Output PDF plus summary counts
src/main/resources/
  application.properties             Port, storage folder, upload size limits
  pii/locations.txt                  City and state names used for LOCATION detection
  static/index.html                  The web page
storage/                             Generated files (created at startup, not committed)
```

## Configuration

`src/main/resources/application.properties`:

| Property                                   | Default     | Meaning                                  |
|--------------------------------------------|-------------|------------------------------------------|
| `server.port`                              | `8081`      | Port the app listens on                  |
| `app.storage.dir`                          | `./storage` | Folder where tokenized PDFs are saved    |
| `spring.servlet.multipart.max-file-size`   | `25MB`      | Largest PDF that can be uploaded         |
| `spring.servlet.multipart.max-request-size`| `25MB`      | Largest upload request                   |

## Customising detection

- **Add places:** add one city, state or area per line to `src/main/resources/pii/locations.txt`. Matching ignores case.
- **Add a new PII type:** add a `Rule` in the `PiiDetector` constructor with a type name, a regex, and the capture group that holds the value.

## How it works

1. PDFBox extracts the text from each page.
2. Invisible formatting characters (for example the zero-width spaces Google Docs adds) are removed.
3. `PiiDetector` runs every rule over the text. When two matches overlap, the one that starts first (and then the longest) wins.
4. Each match is replaced with a token. A map keeps the same value on the same token across the whole document.
5. A new A4 PDF is written containing only the tokenized text.

## Limitations

- **Layout and images are not kept.** The output is a clean text-only PDF, which guarantees the original values are not hidden anywhere in it.
- **Scanned PDFs are not supported.** If a page is only an image, there is no text to read. OCR would be needed.
- **Names and places are rule-based.** A name with no cue word around it (for example "Rahul went home"), or a city not in `locations.txt`, will not be detected. An NLP model could be added for this.
- **Non-Latin text** (for example Hindi) is printed as `?` in the output because the built-in PDF font only covers Latin characters.
- **Tokenization is one-way.** The original values are not stored, so tokens cannot be converted back.

## Privacy notes

- Tokenized PDFs are saved in `storage/` so they can be downloaded. They contain no original PII, but you may still want to clear this folder regularly.
- `storage/` is listed in `.gitignore`. Never commit uploaded documents.
