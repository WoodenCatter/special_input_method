import os
import re
from pathlib import Path


def main() -> None:
    try:
        import uvicorn
        from dotenv import dotenv_values
    except ModuleNotFoundError:
        raise SystemExit(
            "Install the assistant dependencies from assistant_server/requirements.txt."
        ) from None

    env_path = Path(__file__).resolve().parents[1] / ".env"
    if not env_path.is_file():
        raise SystemExit("Copy .env.example to .env and fill in the credentials.")

    try:
        config = dotenv_values(env_path, encoding="utf-8-sig", interpolate=False)
    except (OSError, UnicodeError):
        raise SystemExit("Could not read .env. Save it as UTF-8 and try again.") from None

    api_key = (config.get("DEEPSEEK_API_KEY") or "").strip()
    access_code = (config.get("ASSISTANT_ACCESS_CODE") or "").strip()
    if not api_key:
        raise SystemExit("Fill in DEEPSEEK_API_KEY in .env.")
    if not re.fullmatch(r"[A-Za-z0-9_-]{6,64}", access_code):
        raise SystemExit(
            "ASSISTANT_ACCESS_CODE in .env must be 6-64 letters, digits, underscores, or hyphens."
        )

    os.environ["DEEPSEEK_API_KEY"] = api_key
    os.environ["ASSISTANT_ACCESS_CODE"] = access_code
    uvicorn.run("assistant_server.server:app", host="127.0.0.1", port=8765)


if __name__ == "__main__":
    main()
