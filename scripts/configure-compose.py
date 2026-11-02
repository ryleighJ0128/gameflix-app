"""Create local classroom credentials without committing or printing secrets."""
import base64
from pathlib import Path
import secrets

root = Path(__file__).resolve().parent.parent
path = root / ".env"
if path.exists():
    raise SystemExit(".env already exists; preserving its credentials and signing key.")
values = {
    "MYSQL_DATABASE": "gameflix",
    "MYSQL_ROOT_PASSWORD": secrets.token_hex(24),
    "MYSQL_USER": "gameflix_app",
    "MYSQL_PASSWORD": secrets.token_hex(24),
    "JWT_SECRET_BASE64": base64.b64encode(secrets.token_bytes(48)).decode(),
    "MYSQL_HOST_PORT": "3308",
    "APP_HOST_PORT": "8080",
    "FRONTEND_ORIGIN": "http://localhost:3000",
}
with path.open("x", encoding="utf-8", newline="\n") as stream:
    stream.write("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
path.chmod(0o600)
print("Created ignored .env with fresh classroom credentials. Values are not printed.")
