#!/usr/bin/env python3
"""Check that the OpenAPI precursor matches the server API source.

This is a source-level contract check. It intentionally avoids starting the
Spring application, but it compares the committed OpenAPI document against the
controller routes, WebSocket routes, message enum values, and DTO JSON field
names that define the public API.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any
import re

import yaml

ROOT = Path(__file__).resolve().parents[2]
API_DOCS_DIR = ROOT / "api_docs"
OPENAPI_PATH = API_DOCS_DIR / "schemas" / "openapi.yaml"
API_PACKAGE = ROOT / "server/src/main/java/moe/maika/fmteamhundo/api"
CONTROLLER_PATH = API_PACKAGE / "ApiController.java"
MESSAGE_TYPE_PATH = API_PACKAGE / "MessageType.java"
WS_CONFIG_PATH = ROOT / "server/src/main/java/moe/maika/fmteamhundo/config/WsConfig.java"
PLAYER_UPDATE_PATH = ROOT / "server/src/main/java/moe/maika/fmteamhundo/data/entities/PlayerUpdate.java"
EXPECTED_OPENAPI_VERSION = "3.2.0"

RECORD_COMPONENT_SCHEMA_NAMES = {
    "CardAcquisition": "CardAcquisition",
    "CreditsResponse": "CreditsResponse",
    "EmuMessage": "EmuMessage",
    "LibraryUpdate": "LibraryUpdate",
    "PlayerJson": "PlayerJson",
    "TeamJson": "TeamJson",
    # Nested CreditsResponse records have clearer public schema names.
    "TeamCredits": "CreditsTeam",
    "PlayerCredits": "CreditsPlayer",
    "AllTeamStats": "CreditsAllTeamStats",
    "TeamStats": "CreditsTeamStats",
    "CountRow": "CreditsCountRow",
}

SUCCESS_RESPONSE_SCHEMAS = {
    ("get", "/players"): ("array", "PlayerJson"),
    ("get", "/teams"): ("array", "TeamJson"),
    ("get", "/library/{teamId}"): ("ref", "LibraryUpdate"),
    ("get", "/credits"): ("ref", "CreditsResponse"),
}

EXPECTED_FIREHOSE_SCHEMAS = {
    "/firehose/player": "#/components/schemas/PlayerUpdate",
    "/firehose/team": "#/components/schemas/LibraryUpdate",
}


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_openapi_spec() -> dict[str, Any]:
    with OPENAPI_PATH.open(encoding="utf-8") as file:
        spec = yaml.safe_load(file)
    assert isinstance(spec, dict), f"{OPENAPI_PATH} must parse to a YAML mapping"
    return spec


def validate_utf8_docs() -> None:
    for path in API_DOCS_DIR.rglob("*"):
        if path.is_file():
            read_text(path)


def normalize_mapping_path(path: str) -> str:
    return path or "/"


def extract_controller_routes(source: str) -> dict[str, set[str]]:
    mapping_pattern = re.compile(
        r"@(?P<annotation>GetMapping|PostMapping)\((?P<args>.*?)\)\s*\n\s*"
        r"public\s+ResponseEntity<(?P<return_type>.*?)>\s+(?P<method>\w+)\(",
        re.DOTALL,
    )
    routes: dict[str, set[str]] = {}
    for match in mapping_pattern.finditer(source):
        path_match = re.search(r'"([^"]+)"', match.group("args"))
        assert path_match, f"Could not find path for {match.group('method')} in ApiController"
        method = "get" if match.group("annotation") == "GetMapping" else "post"
        path = normalize_mapping_path(path_match.group(1))
        routes.setdefault(path, set()).add(method)
    return routes


def extract_request_mapping_prefix(source: str) -> str:
    match = re.search(r"@RequestMapping\(path\s*=\s*\"([^\"]+)\"", source)
    assert match, "ApiController must declare a class-level @RequestMapping path"
    return match.group(1)


def validate_routes(spec: dict[str, Any], controller_source: str) -> None:
    server_urls = [server.get("url") for server in spec.get("servers", []) if isinstance(server, dict)]
    controller_prefix = extract_request_mapping_prefix(controller_source)
    assert controller_prefix in server_urls, (
        f"OpenAPI servers must include ApiController prefix {controller_prefix!r}; got {server_urls!r}"
    )

    source_routes = extract_controller_routes(controller_source)
    spec_routes = {
        path: {method for method in operations if method in {"get", "post", "put", "patch", "delete"}}
        for path, operations in spec.get("paths", {}).items()
    }
    assert spec_routes == source_routes, (
        "OpenAPI paths/methods are out of sync with ApiController: "
        f"spec={spec_routes!r}, source={source_routes!r}"
    )


def extract_response_entity_types(source: str) -> dict[tuple[str, str], str]:
    mapping_pattern = re.compile(
        r"@(?P<annotation>GetMapping|PostMapping)\((?P<args>.*?)\)\s*\n\s*"
        r"public\s+ResponseEntity<(?P<return_type>.*?)>\s+(?P<method>\w+)\(",
        re.DOTALL,
    )
    response_types: dict[tuple[str, str], str] = {}
    for match in mapping_pattern.finditer(source):
        path_match = re.search(r'"([^"]+)"', match.group("args"))
        assert path_match, f"Could not find path for {match.group('method')} in ApiController"
        method = "get" if match.group("annotation") == "GetMapping" else "post"
        response_types[(method, normalize_mapping_path(path_match.group(1)))] = re.sub(
            r"\s+", "", match.group("return_type")
        )
    return response_types


def schema_ref_name(schema: dict[str, Any]) -> str | None:
    ref = schema.get("$ref")
    if isinstance(ref, str):
        return ref.removeprefix("#/components/schemas/")
    return None


def json_success_schema(spec: dict[str, Any], method: str, path: str) -> dict[str, Any]:
    return spec["paths"][path][method]["responses"]["200"]["content"]["application/json"]["schema"]


def validate_success_response_schemas(spec: dict[str, Any], controller_source: str) -> None:
    response_types = extract_response_entity_types(controller_source)
    for route, expected_schema in SUCCESS_RESPONSE_SCHEMAS.items():
        source_type = response_types.get(route)
        assert source_type, f"Missing source response type for {route}"
        schema = json_success_schema(spec, *route)
        shape, name = expected_schema
        if shape == "array":
            assert source_type == f"List<{name}>", f"{route} source response should be List<{name}>, got {source_type}"
            assert schema.get("type") == "array", f"{route} OpenAPI response must be an array"
            assert schema_ref_name(schema.get("items", {})) == name, f"{route} OpenAPI array item must be {name}"
        else:
            assert source_type == name, f"{route} source response should be {name}, got {source_type}"
            assert schema_ref_name(schema) == name, f"{route} OpenAPI response must reference {name}"

    update_body = spec["paths"]["/update"]["post"]["requestBody"]["content"]["application/json"]["schema"]
    assert update_body.get("type") == "array", "POST /update request body must be an array"
    assert schema_ref_name(update_body.get("items", {})) == "EmuMessage", "POST /update body items must be EmuMessage"
    assert response_types[("post", "/update")] == "Map<String,Object>", "POST /update source response type changed"


def split_parameters(parameters: str) -> list[str]:
    parts: list[str] = []
    start = 0
    angle_depth = 0
    paren_depth = 0
    in_string = False
    escaped = False
    for index, char in enumerate(parameters):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "<":
            angle_depth += 1
        elif char == ">":
            angle_depth -= 1
        elif char == "(":
            paren_depth += 1
        elif char == ")":
            paren_depth -= 1
        elif char == "," and angle_depth == 0 and paren_depth == 0:
            part = parameters[start:index].strip()
            if part:
                parts.append(part)
            start = index + 1
    tail = parameters[start:].strip()
    if tail:
        parts.append(tail)
    return parts


def find_record_parameters(source: str) -> dict[str, str]:
    records: dict[str, str] = {}
    record_pattern = re.compile(r"public\s+record\s+(\w+)\s*\(")
    for match in record_pattern.finditer(source):
        name = match.group(1)
        index = match.end()
        depth = 1
        in_string = False
        escaped = False
        while index < len(source) and depth > 0:
            char = source[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
            elif char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        assert depth == 0, f"Could not parse record parameters for {name}"
        records[name] = source[match.end(): index - 1]
    return records


def json_name_for_record_parameter(parameter: str) -> str | None:
    if "@JsonIgnore" in parameter:
        return None
    json_property = re.search(r"@JsonProperty\(\s*\"([^\"]+)\"\s*\)", parameter)
    cleaned = re.sub(r"@\w+(?:\([^)]*\))?", "", parameter).strip()
    name_match = re.search(r"(\w+)\s*$", cleaned)
    assert name_match, f"Could not parse record parameter name from {parameter!r}"
    return json_property.group(1) if json_property else name_match.group(1)


def record_json_fields(java_path: Path) -> dict[str, set[str]]:
    source = read_text(java_path)
    return {
        record_name: {
            field_name
            for field_name in (json_name_for_record_parameter(param) for param in split_parameters(params))
            if field_name is not None
        }
        for record_name, params in find_record_parameters(source).items()
    }


def player_update_json_fields() -> set[str]:
    fields: set[str] = set()
    ignore_next = False
    for raw_line in read_text(PLAYER_UPDATE_PATH).splitlines():
        line = raw_line.strip()
        if line.startswith("@JsonIgnore"):
            ignore_next = True
            continue
        field_match = re.match(r"private\s+[\w<>]+\s+(\w+);", line)
        if not field_match:
            continue
        if ignore_next:
            ignore_next = False
            continue
        fields.add(field_match.group(1))
    return fields


def schema_properties(spec: dict[str, Any], schema_name: str) -> set[str]:
    schema = spec["components"]["schemas"][schema_name]
    properties = schema.get("properties")
    assert isinstance(properties, dict), f"Schema {schema_name} must declare properties"
    return set(properties)


def validate_dto_schemas(spec: dict[str, Any]) -> None:
    source_fields: dict[str, set[str]] = {}
    for java_path in API_PACKAGE.glob("*.java"):
        for record_name, fields in record_json_fields(java_path).items():
            schema_name = RECORD_COMPONENT_SCHEMA_NAMES.get(record_name)
            if schema_name:
                source_fields[schema_name] = fields
    source_fields["PlayerUpdate"] = player_update_json_fields()

    for schema_name, fields in sorted(source_fields.items()):
        spec_fields = schema_properties(spec, schema_name)
        assert spec_fields == fields, (
            f"Schema {schema_name} properties are out of sync with Java source: "
            f"spec={sorted(spec_fields)!r}, source={sorted(fields)!r}"
        )


def validate_message_type_enum(spec: dict[str, Any]) -> None:
    source_values = set(re.findall(r"@JsonProperty\(\s*\"([^\"]+)\"\s*\)", read_text(MESSAGE_TYPE_PATH)))
    spec_values = set(spec["components"]["schemas"]["MessageType"].get("enum", []))
    assert spec_values == source_values, (
        f"MessageType enum is out of sync: spec={sorted(spec_values)!r}, source={sorted(source_values)!r}"
    )


def validate_websocket_firehoses(spec: dict[str, Any]) -> None:
    source_paths = set(
        re.findall(
            r"private\s+static\s+final\s+String\s+\w+\s*=\s*\"([^\"]+)\";",
            read_text(WS_CONFIG_PATH),
        )
    )
    spec_firehoses = spec.get("x-websocket-firehoses")
    assert isinstance(spec_firehoses, list), "OpenAPI must declare x-websocket-firehoses list"
    spec_paths = {item.get("path") for item in spec_firehoses if isinstance(item, dict)}
    assert spec_paths == source_paths, f"Firehose paths out of sync: spec={spec_paths!r}, source={source_paths!r}"

    for firehose in spec_firehoses:
        assert isinstance(firehose, dict), "Each firehose metadata entry must be a mapping"
        path = firehose.get("path")
        expected_schema = EXPECTED_FIREHOSE_SCHEMAS.get(path)
        assert expected_schema, f"Unexpected firehose path in OpenAPI: {path!r}"
        assert firehose.get("messageSchema") == expected_schema, (
            f"{path} messageSchema should be {expected_schema}, got {firehose.get('messageSchema')!r}"
        )


def main() -> None:
    spec = load_openapi_spec()
    controller_source = read_text(CONTROLLER_PATH)
    assert spec.get("openapi") == EXPECTED_OPENAPI_VERSION, (
        f"Expected OpenAPI {EXPECTED_OPENAPI_VERSION}, got {spec.get('openapi')!r}"
    )
    validate_utf8_docs()
    validate_routes(spec, controller_source)
    validate_success_response_schemas(spec, controller_source)
    validate_dto_schemas(spec)
    validate_message_type_enum(spec)
    validate_websocket_firehoses(spec)
    print("OpenAPI spec is in sync with server API source")


if __name__ == "__main__":
    main()
