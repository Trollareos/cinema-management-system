# Cinema Management Backend (REST)

Backend σύστημα διαχείρισης “Cinema Season (Program)” και “Screenings”, με ρόλους:
- VISITOR (ανώνυμος): βλέπει/ψάχνει μόνο ANNOUNCED programs και SCHEDULED screenings
- USER (authenticated): μπορεί να γίνει program-specific PROGRAMMER/STAFF/SUBMITTER
- PROGRAMMER: διαχειρίζεται program, state transitions, members, screening decisions
- STAFF: κάνει review μόνο στα screenings που του έχουν ανατεθεί
- SUBMITTER: δημιουργεί/ενημερώνει/submits/final-submits τα δικά του screenings

## Tech
- Java + Spring Boot
- Spring Data JPA + H2 database (file-based)
- Basic Auth / form login (για development)
- Unit tests (JUnit)

## Run (NetBeans ή Maven)
1) Άνοιξε το project στο NetBeans
2) Run Project

ή με Maven:
- `mvn clean test`
- `mvn spring-boot:run`

## H2 Database
Το project (development) χρησιμοποιεί file-based H2:
- `jdbc:h2:file:./data/cinema;AUTO_SERVER=TRUE`
H2 Console:
- enabled στο `/h2` (όπως στο application.properties)

## Public endpoints (VISITOR)
- `GET /api/public/programs` (search με AND semantics)
- `GET /api/public/programs/{id}`
- `GET /api/public/programs/{id}/screenings` (μόνο SCHEDULED, μόνο αν program=ANNOUNCED)

## Auth endpoints
- `POST /api/auth/register`  (body: { "username": "...", "password": "..." })
- `GET /api/auth/me`

## Main endpoints (authenticated)
Programs:
- `POST /api/programs`
- `PATCH /api/programs/{id}`
- `GET /api/programs` (search AND semantics + role filtering + sorting)
- `GET /api/programs/{id}`
- `DELETE /api/programs/{id}` (μόνο CREATED)
- `POST /api/programs/{id}/state` (μόνο forward transitions)

Members:
- `/api/programs/{programId}/members/...` (add staff/programmers, change role, list, remove)

Screenings:
- `POST /api/screenings` (create; becomes SUBMITTER in program automatically)
- `PATCH /api/screenings/{id}` (μόνο CREATED, μόνο owner SUBMITTER)
- `POST /api/screenings/{id}/submit`
- `DELETE /api/screenings/{id}` (withdraw only if CREATED)
- `POST /api/screenings/{id}/assign-handler` (PROGRAMMER during ASSIGNMENT)
- `POST /api/screenings/{id}/review` (assigned STAFF during REVIEW)
- `POST /api/screenings/{id}/approve` (SUBMITTER during SCHEDULING)
- `POST /api/screenings/{id}/reject` (PROGRAMMER during SCHEDULING/DECISION)
- `POST /api/screenings/{id}/final-submit` (SUBMITTER during FINAL_SUBMISSION)
- `POST /api/screenings/{id}/accept` (PROGRAMMER during DECISION)

## Rate limiting
Υπάρχει απλό in-memory rate limiting για να αποφεύγεται abuse, ειδικά σε:
- screening create / submit
- program search (public & authenticated)
- screening search (public & authenticated)
Αν ξεπεραστεί, επιστρέφει 429 με `Retry-After`.

## Tests
`src/test/resources/application.properties` χρησιμοποιεί in-memory H2 ώστε τα tests να μην πειράζουν το `./data/cinema.mv.db`.
Τρέξε: `mvn test`
