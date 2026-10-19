# GameFlix User Service - Module 4

Java 21, Spring Boot 3.5.7, Spring Security 6, Spring Data JPA, and **real MySQL 8**. This service registers GameFlix users, verifies passwords, issues signed JWTs, and protects the current user's profile.

Assignment source: [GameFlix `module4-jwt-assignment` branch](https://github.com/ryleighJ0128/gameflix-app/tree/module4-jwt-assignment). Use this branch when reviewing the submission; it contains the complete Module 4 project.

## API

The local API runs at `http://localhost:8080`. Send and receive JSON.

| Method and path | Request | Success | Failure examples |
| --- | --- | --- | --- |
| `POST /api/auth/register` | `username`, `email`, `password` | `201 Created`, public user information | `400` invalid input; `409` duplicate email or username |
| `POST /api/auth/login` | `email`, `password` | `200 OK`, `accessToken`, `tokenType`, `expiresIn` | `401` with `Invalid email or password` for unknown email or wrong password |
| `GET /api/users/me` | `Authorization: Bearer <token>` | `200 OK`, the authenticated user's public information | `401` missing, invalid, expired, tampered token, or missing user |

User responses contain `id`, `username`, `email`, and `createdAt`; passwords and password hashes are excluded. Usernames must contain 3-50 ASCII letters, digits, or underscores. Email is trimmed and lowercased before storage and lookup. Registration validates input, and database constraints resolve email or username conflicts, including concurrent requests. Error bodies contain `status` and `message`, with safe field-level `errors` for registration validation.

## Requirements and MySQL setup

Install **JDK 21** and **MySQL Server 8**. The included Maven wrapper downloads Maven 3.9.11 on its first run, which needs internet access. Confirm `java -version`, `.\mvnw.cmd -version`, and `mysql --version`; Maven must report Java 21. Run all following project commands from the directory containing `pom.xml`. On macOS/Linux, use `./mvnw` in place of `.\mvnw.cmd`; an existing Maven 3.9+ installation can use `mvn`.

Log into MySQL as a local administrator:

```powershell
mysql -u root -p
```

Create separate application and test databases, and a local application user. Replace the password placeholder locally with your own value; no real database password is included in this project.

```sql
CREATE DATABASE module4_jwt CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE module4_jwt_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'module4_app'@'localhost' IDENTIFIED BY 'REPLACE_WITH_YOUR_LOCAL_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON module4_jwt.* TO 'module4_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
    ON module4_jwt_test.* TO 'module4_app'@'localhost';
```

The expected database names are `module4_jwt` and `module4_jwt_test`; the example account is `module4_app` on localhost. Existing users can be used instead by setting the environment variables to their actual values. Do not point the tests at an important database: test setup deletes rows from `users`, and it refuses a database whose name does not end in `_test`.

## Configuration and running

The application requires `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET_BASE64`. `FRONTEND_ORIGIN` defaults to exactly `http://localhost:3000`, and `PORT` defaults to `8080`.

Use a PowerShell terminal with Java 21 on `PATH`:

```powershell
$credential = Get-Credential -UserName 'module4_app' -Message 'Enter your local MySQL application password'
$env:DB_USERNAME = $credential.UserName
$env:DB_PASSWORD = $credential.GetNetworkCredential().Password
$env:DB_URL = 'jdbc:mysql://localhost:3306/module4_jwt?serverTimezone=UTC'
$keyBytes = [byte[]]::new(32)
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($keyBytes)
$random.Dispose()
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($keyBytes)
$env:FRONTEND_ORIGIN = 'http://localhost:3000'
.\mvnw.cmd spring-boot:run
```

Generate a key once for a local session; keep the same secret when restarting if existing tokens should remain valid. The key is at least 32 cryptographically random bytes before Base64 encoding. Missing, invalid, or undersized signing keys prevent startup. Base64 encoding the secret is a configuration format, not encryption.

Alternatively, copy the committed `application.properties.example` to `application-local.properties`, replace its placeholders, and run `.\mvnw.cmd spring-boot:run`. The main configuration imports that file from the project directory. `.gitignore` excludes local properties, `.env` files, and `target/`; keep credentials and signing keys only in your local configuration or environment.

## Try the complete workflow

With the server running, use a second PowerShell terminal. These are deliberately disclosed throwaway credentials, not a production account or secret.

```powershell
$base = 'http://localhost:8080'
$registration = @{
    username = 'module4_demo'
    email = 'module4.demo@example.com'
    password = 'Module4Throwaway!2026'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/auth/register" -ContentType 'application/json' -Body $registration

$login = @{
    email = 'module4.demo@example.com'
    password = 'Module4Throwaway!2026'
} | ConvertTo-Json
$auth = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body $login
$auth
Invoke-RestMethod -Uri "$base/api/users/me" -Headers @{ Authorization = "Bearer $($auth.accessToken)" }
```

Repeat registration to see `409`; change the login password to see `401`; request `/api/users/me` without the Authorization header to see `401`. PowerShell reports HTTP errors as exceptions; the automated evidence records both response bodies and status codes.

For the required database check, connect to `module4_jwt` using MySQL and run:

```sql
SELECT id, email, password FROM users;
SHOW CREATE TABLE users;
```

The `password` cell should begin with `{bcrypt}$2...$12$` and contain a 68-character encoded hash, never `Module4Throwaway!2026`. The prefix identifies the encoder; the bcrypt text includes the salt and work factor. The database screenshot in the submission document displays the actual query and result.

## Schema and persistence

[`schema.sql`](schema.sql) provides the reference DDL, and `evidence/show-create-table.sql` records the actual MySQL table definition from verification. The authentication table contains:

| Column | MySQL type | Constraints |
| --- | --- | --- |
| `id` | `CHAR(36)` | UUID primary key, not null |
| `username` | `VARCHAR(50)` | not null, unique |
| `email` | `VARCHAR(254)` | not null, unique |
| `password` | `VARCHAR(255)` | not null; adaptive hash only |
| `created_at` | `TIMESTAMP(6)` | not null |

JPA uses MySQL Connector/J, with no H2 dependency or in-memory substitute. `spring.jpa.hibernate.ddl-auto=update` creates or updates the local assignment table while keeping registrations across restarts. `create-drop` was avoided because it removes persisted users and makes the required evidence difficult to reproduce. `spring.sql.init.mode=never` means `schema.sql` is reference documentation rather than a second initialization mechanism; versioned migrations would be preferable for deployment.

Email uniqueness is a **MySQL unique constraint**, backed by the JPA mapping. Registration flushes the insert before returning; a `DataIntegrityViolationException` maps to `409`, so a preliminary availability query cannot accidentally allow two concurrent inserts. Username uniqueness also follows the ERD.

## Password hashing choice

This course implementation chooses **bcrypt at work factor 12** because Spring Security includes it directly and the assignment recommends that path. The deliberately raised factor increases the cost of offline guesses over the default factor 10, while the integration run provides a practical check that authentication remains usable locally; deployment hardware would need its own latency measurements. OWASP now prefers Argon2id for new systems and documents bcrypt's minimum factor and 72-byte limit, so bcrypt here is a course-specific choice rather than a claim that it is OWASP's first choice. [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)

A `DelegatingPasswordEncoder` selects bcrypt for new hashes and writes the `{bcrypt}` prefix, preserving an algorithm identifier for future migration. The 255-character column safely holds the prefixed hash and allows room for longer encodings. Passwords must be 12-64 characters **and at most 72 UTF-8 bytes**, and cannot contain a NUL character; the separate byte check matters because non-ASCII characters may occupy multiple bytes. [Spring Security 6.5 password storage](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html)

No plaintext password is persisted, returned, or deliberately logged. SQL/bind-value logging is disabled, and validation errors do not echo supplied passwords. The tests use an explicitly disclosed throwaway password only to demonstrate hashing.

## JWT and HTTP security decisions

- **Algorithm:** HS256 with a random key read from configuration; the decoder accepts HS256 only. Spring Security's Nimbus implementation verifies the signature and validates claims before a protected controller uses the authenticated identity.
- **Claims:** `sub` is the user's UUID; `iss` is `module4-jwt-service`; `aud` is `module4-api`; `iat` and `exp` record issue and expiry times; `jti` is a fresh token identifier. No password, hash, email, or other confidential field is placed in the token.
- **Lifetime:** 900 seconds (15 minutes). This limits the useful lifetime of a stolen bearer token while allowing a short class demonstration; there is no refresh-token feature, so the user logs in again after expiry.
- **Validation:** signature, allowed algorithm, required future expiration, issue time, issuer, audience, and canonical UUID subject are checked. Issue time must not be in the future and must precede expiry; `nbf`, if present, is checked with zero clock skew. A token is insufficient if the corresponding user has been deleted.
- **Payload:** Base64URL is readable encoding. A signature detects changes; it does not encrypt the header or claims. Anyone holding a bearer token can read it, and anyone who steals a still-valid token can use it.
- **Login failures:** wrong password and unknown email both produce the same `401` body, `Invalid email or password`. A dummy bcrypt match for unknown accounts also reduces the obvious timing difference.
- **CORS:** exactly the configured front-end origin is allowed, with explicit methods/headers; `*` is not used. CORS controls browser access and does not replace authentication.
- **Sessions and CSRF:** the API is stateless and uses an explicitly supplied Authorization header rather than cookie authentication. CSRF protection is disabled for that API model. HTTPS is required when deploying beyond localhost; there is no refresh, logout denylist, or login-rate-limiting implementation in this assignment.

The configuration uses a lambda-based `SecurityFilterChain`, with Spring Security Resource Server handling bearer tokens. [Spring Security 6.5 JWT resource server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html), [Spring Security 6.5 CORS](https://docs.spring.io/spring-security/reference/6.5/servlet/integrations/cors.html)

## Automated test evidence

The project uses **`@SpringBootTest` and MockMvc with the real Spring Security filter chain and real MySQL**. Tokens are generated and cryptographically verified rather than injected as a mocked principal. The test signing key is freshly generated in memory for each test run; it is not a committed key.

**Verified result:** `mvn clean verify` completed with **BUILD SUCCESS: 15 tests, 0 failures, 0 errors, 0 skipped**, using Java **21.0.8** and MySQL **8.0.39**. The final test suite took 17.72 seconds; the complete Maven build took 27.290 seconds. The full run is saved in `evidence/test-run.log`.

From the configured PowerShell terminal, stop any existing test run and point only the test invocation at the dedicated database:

```powershell
$applicationUrl = $env:DB_URL
try {
    $env:DB_URL = 'jdbc:mysql://localhost:3306/module4_jwt_test?serverTimezone=UTC'
    .\mvnw.cmd test
} finally {
    $env:DB_URL = $applicationUrl
}
```

The suite covers the six required demonstrations plus malformed/expired/missing-expiry tokens, wrong issuer/audience/subject/key/algorithm, malformed JSON and password boundaries, exact CORS origin, database metadata, deleted users, and concurrent duplicate registrations. Both HTTP-level rejection and a direct SQL duplicate insert check exercise the database constraint.

| Assignment evidence | Project evidence |
| --- | --- |
| Register succeeds; duplicate email fails | `AuthenticationIntegrationTest` and `evidence/endpoint-results.json` |
| Login returns token; wrong password fails | Same suite and endpoint results |
| Protected route allows valid token; rejects missing/tampered token | Same suite and endpoint results |
| Automated passing-run proof | `evidence/test-run.log`; screenshot in the Word submission |
| Actual MySQL hash storage | `evidence/mysql-query.txt`; database screenshot in the Word submission |
| Actual column types and constraints | `schema.sql` and `evidence/show-create-table.sql` |

The submitted `evidence/` files record execution results rather than predicted sample output. A new test run writes fresh endpoint/database evidence to **`target/evidence/`** and leaves the demonstration row in the dedicated test database; it does not overwrite the archived submission files automatically. Maven prints its test summary in the terminal. `target/` contains build products and is excluded from the project ZIP.

## Module 3 design-change note

The source design is the GameFlix Module 3 ERD: `auth.users` had UUID `user_id`, unique `username` (50), unique `email` (254), `password_hash` (255), and `created_at`. This implementation keeps those logical fields, types, and uniqueness rules. The physical names change from `user_id` to `id` and `password_hash` to `password` so the assignment's exact `SELECT id, email, password FROM users;` works; the Java property remains `passwordHash` to communicate what is stored. MySQL stores UUIDs as `CHAR(36)`.

The dedicated `module4_jwt` database implements the User Service's database ownership rather than a separate `auth` schema. The earlier endpoint planning document used `displayName` and `/users`; this build aligns the registration field with the final ERD's `username` and uses `/api/auth/register`, `/api/auth/login`, and `/api/users/me`. The `me` route derives identity from the verified JWT, so callers cannot choose another user's ID. Catalog, reviews, preferred genres, profile editing, and subscription functionality belong to other project work and are outside the Module 4 authentication slice.

## AI assistance and code understanding

**OpenAI Codex** assisted with the initial service, integration tests, documentation, and test-evidence preparation. The initial proposed user model used a numeric ID and only email/password; that proposal was changed after retrieving the actual Module 3 ERD, restoring UUID identity, username, creation time, and the original uniqueness rules. Review also changed the generated tampering test: it now extends a valid token's expiration while preserving the other valid claims and original signature, so a rejection demonstrates signature validation rather than malformed JSON or a missing user.

[`EXPLAINING_THE_CODE.md`](EXPLAINING_THE_CODE.md) is a study guide for tracing each request and explaining these decisions. AI assistance does not replace the student's obligation to review, run, and understand the code before submitting it.

## Submission contents

Submit `Module4_Working_Output.docx`, the Word document of working-output screenshots with the repository branch URL on its first page. If submitting without GitHub, also upload the project ZIP excluding `target/`. The README, configuration example, schema, source, tests, and evidence directory travel with the project.
