package com.ryleigh.module4;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full Spring Security filter chain and real MySQL; no mocked token or persistence. */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthenticationIntegrationTest {
    private static final String EMAIL = "module4.evidence@example.com";
    private static final String PASSWORD = "Module4Throwaway!2026";
    private static final byte[] TEST_KEY = randomKey();
    private static final java.util.concurrent.atomic.AtomicInteger USERNAMES = new java.util.concurrent.atomic.AtomicInteger();
    private final List<Map<String, Object>> evidence = new ArrayList<>();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate database;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @DynamicPropertySource
    static void testSecret(DynamicPropertyRegistry properties) {
        properties.add("app.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(TEST_KEY));
    }

    @BeforeAll void requireDedicatedTestDatabase() {
        assertThat(database.queryForObject("SELECT DATABASE()", String.class)).endsWith("_test");
    }

    @BeforeEach void resetOnlyTestUsers() {
        database.update("DELETE FROM users");
    }

    @Test @Order(1) void registrationPersistsOnlyAnAdaptiveHash() throws Exception {
        register(EMAIL, PASSWORD, 201);
        String hash = database.queryForObject("SELECT password FROM users WHERE email = ?", String.class, EMAIL);
        assertThat(hash).startsWith("{bcrypt}$2").hasSize(68).doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(hash).contains("$12$");
    }

    @Test @Order(2) void databaseConstraintRejectsDuplicateAndNormalizedEmail() throws Exception {
        register(EMAIL, PASSWORD, 201);
        register(EMAIL.toUpperCase(Locale.ROOT), PASSWORD, 409);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        // Verify the actual database rejects duplicates even when HTTP validation is bypassed.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                database.update("INSERT INTO users (id,username,email,password,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
                        UUID.randomUUID().toString(), "different_username", EMAIL, "not-a-real-hash")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test @Order(3) void successfulLoginIssuesSignedShortLivedToken() throws Exception {
        register(EMAIL, PASSWORD, 201);
        String token = login(EMAIL, PASSWORD, 200).get("accessToken").asText();
        Jwt verified = decoder.decode(token);
        assertThat(verified.getExpiresAt()).isNotNull();
        assertThat(verified.getExpiresAt().getEpochSecond() - verified.getIssuedAt().getEpochSecond()).isEqualTo(900);
        assertThat(verified.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(verified.getClaims().keySet()).contains("iss", "aud", "sub", "iat", "exp")
                .doesNotContain("email", "password", "passwordHash", "secret");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("email").value(EMAIL))
                .andExpect(jsonPath("password").doesNotExist()).andExpect(jsonPath("passwordHash").doesNotExist());
    }

    @Test @Order(4) void wrongPasswordAndUnknownEmailHaveIdenticalResponses() throws Exception {
        register(EMAIL, PASSWORD, 201);
        JsonNode wrong = login(EMAIL, "WrongThrowaway!2026", 401);
        JsonNode unknown = login("unknown@example.com", PASSWORD, 401);
        assertThat(wrong).isEqualTo(unknown);
        assertThat(wrong.get("message").asText()).isEqualTo("Invalid email or password");
    }

    @Test @Order(5) void missingMalformedAndTamperedTokensAreRejected() throws Exception {
        register(EMAIL, PASSWORD, 201);
        String token = login(EMAIL, PASSWORD, 200).get("accessToken").asText();
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        protectedRequest("not.a.valid.jwt", 401);
        protectedRequest(tampered(token), 401);
    }

    @Test @Order(6) void expiredSignedTokenIsRejected() throws Exception {
        String subject = registeredSubject();
        protectedRequest(signed(Instant.now().minusSeconds(120), "module4-jwt-service", "module4-api", subject), 401);
    }

    @Test @Order(7) void missingExpirationIsRejectedEvenWithValidSignature() throws Exception {
        String subject = registeredSubject();
        protectedRequest(signed(null, "module4-jwt-service", "module4-api", subject), 401);
    }

    @Test @Order(8) void wrongIssuerAudienceAndSubjectAreRejected() throws Exception {
        String subject = registeredSubject();
        protectedRequest(signed(Instant.now().plusSeconds(900), "other-issuer", "module4-api", subject), 401);
        protectedRequest(signed(Instant.now().plusSeconds(900), "module4-jwt-service", "other-api", subject), 401);
        protectedRequest(signed(Instant.now().plusSeconds(900), "module4-jwt-service", "module4-api", "not-an-id"), 401);
    }

    @Test @Order(9) void wrongKeyAndWrongAlgorithmAreRejected() throws Exception {
        String subject = registeredSubject();
        var claims = new JWTClaimsSet.Builder().issuer("module4-jwt-service").audience("module4-api")
                .subject(subject).issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).build();
        var wrongKey = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        wrongKey.sign(new MACSigner(randomKey()));
        protectedRequest(wrongKey.serialize(), 401);
        byte[] longerKey = Arrays.copyOf(TEST_KEY, 64);
        var wrongAlgorithm = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        wrongAlgorithm.sign(new MACSigner(longerKey));
        protectedRequest(wrongAlgorithm.serialize(), 401);
    }

    @Test @Order(10) void registrationValidatesInputWithoutEchoingPasswords() throws Exception {
        register("invalid-email", PASSWORD, 400);
        register(EMAIL, "short", 400);
        register(EMAIL, "a".repeat(65), 400);
        register(EMAIL, "\u00e9".repeat(40), 400); // 40 characters but 80 UTF-8 bytes.
        register(EMAIL, "bad\u0000passwordThatIsLong", 400);
        assertThat(database.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(11) void exactCorsOriginIsAllowedAndOtherOriginsAreBlocked() throws Exception {
        mvc.perform(options("/api/users/me").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options("/api/users/me").header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test @Order(12) void concurrentDuplicateRegistrationsHaveOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            Callable<Integer> registration = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(EMAIL, PASSWORD))).andReturn().getResponse().getStatus();
            };
            Future<Integer> first = pool.submit(registration);
            Future<Integer> second = pool.submit(registration);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(database.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        }
    }

    @Test @Order(13) void passwordColumnCanHoldPrefixedHashesAndEmailHasUniqueIndex() {
        Integer length = database.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='users' AND column_name='password'", Integer.class);
        assertThat(length).isEqualTo(255);
        Integer indexes = database.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='users' AND column_name='email' AND NON_UNIQUE=0", Integer.class);
        assertThat(indexes).isGreaterThanOrEqualTo(1);
    }

    @Test @Order(14) void deletedUserCannotAccessProtectedProfile() throws Exception {
        register(EMAIL, PASSWORD, 201);
        String token = login(EMAIL, PASSWORD, 200).get("accessToken").asText();
        database.update("DELETE FROM users");
        protectedRequest(token, 401);
    }

    @Test @Order(99) void captureRequiredSuccessAndFailureEvidence() throws Exception {
        record("Successful registration", "POST", "/api/auth/register", register(EMAIL, PASSWORD, 201), 201);
        record("Duplicate email rejected", "POST", "/api/auth/register", register(EMAIL, PASSWORD, 409), 409);
        JsonNode auth = login(EMAIL, PASSWORD, 200);
        record("Successful login", "POST", "/api/auth/login", auth, 200);
        record("Wrong password rejected", "POST", "/api/auth/login", login(EMAIL, "WrongThrowaway!2026", 401), 401);
        MvcResult valid = protectedRequest(auth.get("accessToken").asText(), 200);
        record("Protected route with valid token", "GET", "/api/users/me", json.readTree(valid.getResponse().getContentAsString()), 200);
        MvcResult missing = mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized()).andReturn();
        record("Protected route without token", "GET", "/api/users/me", json.readTree(missing.getResponse().getContentAsString()), 401);
        MvcResult tampered = protectedRequest(tampered(auth.get("accessToken").asText()), 401);
        record("Protected route with tampered token", "GET", "/api/users/me", json.readTree(tampered.getResponse().getContentAsString()), 401);
    }

    @AfterAll void saveActualEvidence() throws Exception {
        Path folder = Path.of("target", "evidence");
        Files.createDirectories(folder);
        json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("endpoint-results.json").toFile(), evidence);
        StringBuilder query = new StringBuilder("Actual MySQL query through JDBC after the test registration\n");
        query.append("Captured UTC: ").append(Instant.now()).append('\n');
        query.append("MySQL version: ").append(database.queryForObject("SELECT VERSION()", String.class)).append('\n');
        query.append("Database: ").append(database.queryForObject("SELECT DATABASE()", String.class)).append('\n');
        query.append("\nSELECT id, email, password FROM users;\n\n");
        database.query("SELECT id,email,password FROM users", rs -> {
            query.append("id: ").append(rs.getString("id")).append('\n');
            query.append("email: ").append(rs.getString("email")).append('\n');
            query.append("password: ").append(rs.getString("password")).append('\n');
        });
        query.append("\nThe password column contains the 68-character {bcrypt} hash.\n");
        Files.writeString(folder.resolve("mysql-query.txt"), query);
        String createTable = database.queryForMap("SHOW CREATE TABLE users").get("Create Table").toString();
        Files.writeString(folder.resolve("show-create-table.sql"), createTable + ";\n");
    }

    private String body(String email, String password) throws Exception {
        return json.writeValueAsString(Map.of("username", "module4_student_" + USERNAMES.incrementAndGet(),
                "email", email, "password", password));
    }

    private JsonNode register(String email, String password, int expected) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, password))).andExpect(status().is(expected)).andReturn();
        String response = result.getResponse().getContentAsString();
        if (!password.isBlank()) assertThat(response).doesNotContain(password);
        return json.readTree(response);
    }

    private JsonNode login(String email, String password, int expected) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, password))).andExpect(status().is(expected)).andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        if (expected == 200) {
            assertThat(response.get("tokenType").asText()).isEqualTo("Bearer");
            assertThat(response.get("expiresIn").asInt()).isEqualTo(900);
        }
        return response;
    }

    private MvcResult protectedRequest(String token, int status) throws Exception {
        return mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().is(status)).andReturn();
    }

    private String registeredSubject() throws Exception {
        String subject = register(EMAIL, PASSWORD, 201).get("id").asText();
        // Every negative JWT case has a positive control for this same existing user.
        protectedRequest(signed(Instant.now().plusSeconds(900), "module4-jwt-service", "module4-api", subject), 200);
        return subject;
    }

    private String tampered(String token) throws Exception {
        String[] parts = token.split("\\.");
        var claims = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        // Keep the existing user and every claim valid, but extend exp without resigning.
        claims.put("exp", Instant.now().plusSeconds(1800).getEpochSecond());
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
        return String.join(".", parts);
    }

    private String signed(Instant expires, String issuer, String audience, String subject) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(Instant.now().minusSeconds(300));
        if (expires != null) claims.expiresAt(expires);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private void record(String name, String method, String path, JsonNode response, int status) {
        evidence.add(Map.of("case", name, "method", method, "path", path, "status", status, "response", response));
    }
}
