"""Exercise the real container stack and verify persistence after recreation."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
out = ROOT / "module5-evidence"
out.mkdir(exist_ok=True)
records = []
base = "http://127.0.0.1:8080"
account = "m5_" + uuid.uuid4().hex[:16]
email = account + "@example.com"
password = "ClassroomOnly!" + uuid.uuid4().hex


def http(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                  headers=headers, method=method)
    try:
        with urlopen(req, timeout=10) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.load(error)


def ready():
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            status, _ = http("POST", "/api/auth/login", {"email": "absent@example.com", "password": password})
            if status == 401:
                return
        except (URLError, TimeoutError, OSError):
            pass
        time.sleep(3)
    raise RuntimeError("Application did not become ready within 180 seconds")


def check(name, actual, expected):
    if actual != expected:
        raise AssertionError(f"{name}: expected {expected!r}; received {actual!r}")
    records.append({"check": name, "result": actual})
    print(f"PASS {name}: {actual}", flush=True)


def compose(*args):
    return subprocess.check_output(["docker", "compose", *args], text=True, stderr=subprocess.STDOUT)


print("Actual Docker Compose verification at " + datetime.now(timezone.utc).isoformat(), flush=True)
ready()
status, user = http("POST", "/api/auth/register", {"username": account, "email": email, "password": password})
check("registration HTTP status", status, 201)
check("registered email", user["email"], email)
status, _ = http("POST", "/api/auth/register", {"username": account, "email": email, "password": password})
check("duplicate registration HTTP status", status, 409)
status, login = http("POST", "/api/auth/login", {"email": email, "password": password})
check("login HTTP status", status, 200)
status, profile = http("GET", "/api/users/me", token=login["accessToken"])
check("authenticated profile HTTP status", status, 200)
check("authenticated user persisted by MySQL", profile["id"], user["id"])
status, _ = http("GET", "/api/users/me")
check("missing token HTTP status", status, 401)
status, _ = http("POST", "/api/auth/login", {"email": email, "password": "IncorrectClassroomPassword!"})
check("incorrect password HTTP status", status, 401)

# The account is generated above from a hexadecimal UUID, never user-provided SQL.
query = f"SELECT id,username,email FROM users WHERE username='{account}';"
sql = compose("exec", "-T", "mysql", "sh", "-c",
              'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --raw --execute="$1"',
              "mysql-proof", query)
if user["id"] not in sql or email not in sql:
    raise AssertionError("The actual MySQL SELECT did not return the registered user")
print("Actual SELECT from the MySQL container (no password columns):\n" + sql, flush=True)
(out / "mysql-query.txt").write_text(query + "\n" + sql, encoding="utf-8")
print("Container status before recreation:\n" + compose("ps"), flush=True)
print("Recreating both containers with docker compose down then up; retaining mysql_data.", flush=True)
compose("down")
compose("up", "-d", "--wait", "--wait-timeout", "240")
ready()
status, login2 = http("POST", "/api/auth/login", {"email": email, "password": password})
check("login after container recreation", status, 200)
status, profile2 = http("GET", "/api/users/me", token=login2["accessToken"])
check("profile after container recreation", status, 200)
check("same database record after recreation", profile2["id"], user["id"])
status, _ = http("GET", "/api/users/me", token=login["accessToken"])
check("original token after recreation with preserved signing key", status, 200)
print("Final container status:\n" + compose("ps"), flush=True)
summary = {"captured_utc": datetime.now(timezone.utc).isoformat(), "checks": records,
           "scope": "Spring Boot authentication backend plus MySQL on a GitHub Actions runner; no complete GameFlix UI or catalog deployed"}
(out / "verification.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
print(f"PASS all {len(records)} checks; bearer tokens and passwords omitted from evidence.", flush=True)
