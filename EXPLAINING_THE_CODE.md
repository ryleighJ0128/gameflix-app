# Explain the Module 4 service

Use this guide to trace the implementation and then explain it in your own words. The course allows AI assistance but requires you to understand the submitted code. Reading this document alone is not evidence that you have reviewed it.

## Trace three requests

**Register:** `POST /api/auth/register` is public. Bean Validation checks username, email, and password; the service normalizes the email, encodes the password with the delegating bcrypt encoder, and inserts the user through Spring Data JPA. MySQL assigns no numeric sequence: Java/JPA generates a UUID, and `created_at` records creation. Flushing the insert surfaces a database uniqueness failure before the controller returns `201`. Response DTOs omit the password hash.

**Login:** `POST /api/auth/login` looks up the normalized email and uses `PasswordEncoder.matches` against the stored prefixed hash. It does not decrypt a hash or compare two newly generated bcrypt strings, since each encoding uses its own salt. Failure returns the same `401` message whether the account is absent or the password is wrong. Success issues an HS256 JWT with the UUID subject, expected issuer/audience, issue time, and 15-minute expiration.

**Protected profile:** `GET /api/users/me` receives `Authorization: Bearer <token>`. Spring Security's resource-server filter extracts the token; Nimbus checks its signature and the configured validators check its claims. Only after authentication succeeds does the controller use the subject to retrieve that user's public profile. A deleted account is rejected even if its token remains cryptographically valid.

## Explain the security choices

| Question | Explanation to understand |
| --- | --- |
| Why bcrypt instead of SHA-256? | An adaptive password hash deliberately makes guesses expensive. A fast general-purpose hash lets an attacker try many more guesses. |
| What does cost 12 mean? | Bcrypt's work grows approximately as `2^cost`; raising cost from 10 to 12 increases work by about four times. It is a deliberate local choice; production hardware must be measured. |
| Why a delegating encoder? | `{bcrypt}` records the algorithm. The bcrypt part contains the version, factor, salt, and result, so old encodings can be recognized when algorithms change. |
| Why `VARCHAR(255)`? | A bcrypt hash is 60 characters and the `{bcrypt}` prefix adds eight. A 60-character column cannot hold the chosen 68-character representation. Extra room supports longer algorithms later. |
| Why both 64 characters and 72 bytes? | The assignment asks for a 64-character maximum, but bcrypt's real input boundary is bytes. Forty `é` characters occupy 80 UTF-8 bytes. |
| Why reject NUL? | It removes an input ambiguity associated with bcrypt implementations that treat NUL as a terminator. |
| Why a database unique constraint? | Two requests may both see no existing email, then both insert. The database serializes enforcement of uniqueness; exactly one insertion succeeds. |
| Why generic login errors? | Giving a different message for an unknown email exposes account existence. A dummy bcrypt check also reduces a simple timing distinction. |
| Does JWT signing hide claims? | No. Header and payload are Base64URL-encoded and readable. HS256 is a message authentication code that detects modifications using the shared secret. |
| Can someone edit `sub` to become another user? | They can edit the text, but cannot create a valid signature without the key. The resource server rejects that altered token. |
| Why validate `exp`, `iss`, and `aud`? | A valid signature alone does not make a token timely or appropriate for this service. The code also requires an expiration and a valid UUID subject. |
| Why 15 minutes? | A short lifetime reduces the exposure from a stolen token while giving time for the class workflow. It requires logging in again after expiry. |
| Why an environment secret? | Committed source is shared with graders and potentially the public. A signing key must be kept separately and generated unpredictably. |
| Is Base64 encoding the key protection? | No. It only transports random bytes as text. The key still needs to be kept private. |
| Does CORS block all attackers? | No. It is a browser policy. Authorization must still reject invalid bearer tokens from every client. |
| Why disable CSRF? | This API does not automatically authenticate with cookies; callers explicitly supply a bearer header. A future switch to cookie authentication would require revisiting CSRF. |
| What does stateless mean here? | Authentication does not use a server-side HTTP session. User records still persist in MySQL, and the protected route checks the current user record. |
| Why `ddl-auto=update`? | It is convenient for this local assignment and preserves users between runs. Production schema changes should use reviewed migrations. |

## Relate it to Module 3

The original GameFlix ERD used `user_id`, `username`, `email`, `password_hash`, and `created_at`. The build keeps the logical model, including UUID IDs and unique username/email, while changing two physical names to `id` and `password` for the required database evidence query. `password` stores a hash; it is never plaintext. The current-user endpoint replaces the need to trust a caller-supplied profile ID.

## What the automated tests establish

The tests boot the application with MockMvc, run the real security filter chain, and connect to a MySQL database whose name ends in `_test`. They verify the six required success/failure demonstrations and further failures such as expired tokens, missing expiration, wrong audience or key, concurrency, password boundaries, and CORS. A test directly inspects the stored value and database metadata, and another bypasses HTTP to prove the unique constraint itself rejects duplicates.

The tests generate their own random signing key in memory. Their token strings are throwaway evidence from that run; a freshly started server with another signing key will reject them. To produce a token for the next JWT.io assignment, log into the running service and copy the new `accessToken`; remember that decoding it does not validate it, and never disclose the signing secret.

## Practice before submitting

1. Locate the security configuration and point to the two public routes and protected fallback.
2. Locate the bcrypt cost, password byte check, and encoder prefix. Explain why neither plaintext nor a truncated hash can work correctly.
3. Follow a duplicate email from `saveAndFlush` to MySQL's unique constraint and the `409` handler.
4. Locate the signing-key configuration, HS256 encoder/decoder, and every claim validator.
5. Register a throwaway user, run the required SQL query, log in, and call the protected route.
6. Alter one character in the token, remove it, and let it expire. Explain why each call fails.
7. Explain the specific AI suggestions that were changed and why; the README records those changes candidly.

Official references: [Spring Security 6.5 password storage](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html), [JWT resource server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html), and [OWASP password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
