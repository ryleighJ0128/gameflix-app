# GameFlix Module 5 containerization and CI

The `module5-docker-ci` branch extends the working Module 4 authentication service with a Java 21 runtime image, MySQL 8, a Docker Compose network and persistent database volume, and a GitHub Actions pipeline. It deploys the authentication backend and database for verification; it does not deploy a complete game catalog or browser frontend.

## Files

- `Dockerfile`: packages the executable Maven JAR using a Java 21 JRE and a non-root user.
- `.dockerignore`: excludes local credentials, Git metadata, logs, and evidence while retaining the JAR in `target/`.
- `compose.yaml`: starts MySQL and the application on `app-net`, waits for a successful database query, and stores MySQL files in `mysql_data`.
- `scripts/configure-compose.py`: creates an ignored `.env` with fresh classroom credentials and a random JWT signing key. It preserves an existing file.
- `.github/workflows/ci.yml`: builds and tests against a dedicated MySQL test database, starts the separate Compose stack, and saves actual verification logs.
- `scripts/verify-compose.py`: exercises registration, duplicate rejection, login, protected access, invalid credentials, a real database SELECT, and persistence after both containers are recreated.

## Run on a computer with Docker

Use Java 21, Maven 3.9 or the included Maven wrapper, Python 3, and Docker Compose v2. Start Docker first. From the project root:

```text
python scripts/configure-compose.py
./mvnw clean package -DskipTests
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 240
python scripts/verify-compose.py
```

On Windows, use `.\mvnw.cmd` instead of `./mvnw`. The packaging command skips the integration tests because they require a separate database whose name ends in `_test`; the CI workflow runs all 15 tests against its dedicated `module4_jwt_test` database before building the image.

The application listens on `http://127.0.0.1:8080`. MySQL is exposed only on `127.0.0.1:3308`, leaving a separate local MySQL server on 3306 or 3307 available. Edit the two host-port values in `.env` before startup if necessary. The verification script assumes application port 8080. Inside the Docker network, Spring Boot reaches the database at `mysql:3306`, not `localhost`.

The existing application reads `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET_BASE64`. Compose supplies those exact variables; no application source change or committed password is required. The image contains the JAR built by Maven, so Java changes require rebuilding the JAR and the image.

## Verification and limits

Successful run: https://github.com/ryleighJ0128/gameflix-app/actions/runs/41452769405

The runner executes the actual Spring Boot and MySQL containers, tests the live HTTP endpoints, and queries the actual container database. The verification script calls `docker compose down` without `-v`, recreates the stack, then confirms the same user can log in and retains the same database ID. An earlier token also remains valid because the signing key is preserved in `.env` during recreation.

The temporary CI deployment is removed at the end of the job. The disposable CI database volume is deleted during cleanup; the workflow does not provide a persistent public application URL. For ordinary local use, `docker compose down` preserves the volume. Adding `-v` deletes the stored data and should be reserved for an intentional disposable reset. Changing initialization credentials in `.env` does not update credentials already stored in an existing volume.

The workflow implements continuous integration and a temporary verification deployment. It does not publish an image or automatically deploy GameFlix to production. Image vulnerability scanning, a registry release process, durable hosting, and frontend/catalog integration remain future work.

## AI assistance

AI was used to help guide each step and create an outline for the document. It also assisted with the implementation and wording. The build and container results were checked against the executed workflow.

## References

- Course Dockerizing Spring Boot and MySQL Complete Student Handout, Module 5.
- https://docs.docker.com/compose/how-tos/startup-order/
- https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven
