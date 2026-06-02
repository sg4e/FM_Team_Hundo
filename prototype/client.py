import json
from pathlib import Path

import requests

_CREDENTIAL_SEARCH_PATHS = [
    Path("credentials_FM_Team_Hundo.json"),
    Path("../middleware/credentials_FM_Team_Hundo.json"),
]


def _load_credentials():
    for path in _CREDENTIAL_SEARCH_PATHS:
        if path.is_file():
            with open(path) as f:
                config = json.load(f)
            return config["url"], config["key"]
    searched = ", ".join(str(p) for p in _CREDENTIAL_SEARCH_PATHS)
    raise FileNotFoundError(
        f"No credentials file found. Searched: {searched}"
    )


_base_url, _api_key = _load_credentials()
_base_url = _base_url.rstrip("/")

# Validate credentials against the server at import time
_validate_response = requests.get(
    f"{_base_url}/validate",
    headers={"X-API-Key": _api_key},
)
if _validate_response.status_code != 200:
    raise ImportError(
        f"Failed to validate API key: {_validate_response.text}"
    )


def send(messages, test=False):
    if not isinstance(messages, list):
        messages = [messages]

    headers = {"X-API-Key": _api_key, "Content-Type": "application/json"}
    if test:
        headers["test"] = "true"

    response = requests.post(
        f"{_base_url}/update", headers=headers, json=messages
    )
    return response.json()
