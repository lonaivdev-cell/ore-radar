#!/usr/bin/env python3
"""Uploads release jars to Modrinth and CurseForge, one store version per entry of
modrinth/versions.json. Standard library only.

    tools/publish.py [--version 2.0.1] [--only modrinth|curseforge] [--include-paper] [--publish]

Without --publish it is a dry run: it checks the jars against dist/SHA256SUMS, resolves every
CurseForge game-version id and prints what it would upload, and sends nothing.

- Entries: those of modrinth/versions.json whose version_number starts with --version (default:
  mod_version in gradle.properties). The Paper entry is skipped unless --include-paper.
- Tokens: MODRINTH_TOKEN (a personal access token with "Create versions") and CURSEFORGE_TOKEN (an
  API token from CurseForge account settings) in the environment. Behind a proxy that adds the
  tokens itself, set both to "proxy-injected".
- CurseForge project: curseforge_project_id in modrinth/project.json, or --curseforge-project.
- Repeats: a Modrinth version with the same version_number, loaders and game versions is skipped. CurseForge has
  no way to list a project's files through this API, so uploads are recorded in
  dist/published.json and skipped on a second run from the same checkout.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODRINTH = "https://api.modrinth.com/v2"
CURSEFORGE = "https://minecraft.curseforge.com/api"
USER_AGENT = "ore-radar-publish (github.com/lonaivdev-cell/ore-radar)"
# CurseForge names the loaders differently from Modrinth.
CF_LOADERS = {"forge": "Forge", "neoforge": "NeoForge", "fabric": "Fabric", "quilt": "Quilt"}
# Modrinth project id -> CurseForge slug, for the dependencies both stores know.
CF_RELATIONS = {"P7dR8mSH": "fabric-api"}


def prop(path, key):
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(key + "="):
            value = line.split("=", 1)[1]
    return value


def request(method, url, headers=None, body=None):
    req = urllib.request.Request(url, data=body, method=method, headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as err:
        return err.code, err.read()


def multipart(fields, files):
    """fields: name -> str; files: name -> (filename, bytes). Returns (content type, body)."""
    boundary = uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        out += value.encode() + b"\r\n"
    for name, (filename, data) in files.items():
        out += (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                "Content-Type: application/java-archive\r\n\r\n").encode()
        out += data + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return f"multipart/form-data; boundary={boundary}", bytes(out)


def token(name):
    value = os.environ.get(name)
    if not value:
        sys.exit(f"publish.py: {name} is not set (use \"proxy-injected\" behind a proxy that adds it)")
    return value


def checked_jar(entry):
    jar = (REPO / "modrinth" / entry["file"]).resolve()
    if not jar.is_file():
        sys.exit(f"publish.py: missing {jar}; run ./build.sh first")
    sums = {}
    for line in (jar.parent / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        sums[name.lstrip("*")] = digest
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if sums.get(jar.name) != digest:
        sys.exit(f"publish.py: {jar.name} does not match dist/SHA256SUMS")
    return jar


# --- Modrinth ---------------------------------------------------------------------------------

def modrinth(entries, slug, publish):
    status, body = request("GET", f"{MODRINTH}/project/{slug}")
    if status != 200:
        sys.exit(f"publish.py: Modrinth project {slug}: HTTP {status} {body[:200]!r}")
    project_id = json.loads(body)["id"]
    status, body = request("GET", f"{MODRINTH}/project/{slug}/version")
    # The same version number is used for every loader and Minecraft version, so all three identify one.
    def key(v):
        return v["version_number"], tuple(sorted(v["loaders"])), tuple(sorted(v["game_versions"]))
    existing = {key(v) for v in json.loads(body)} if status == 200 else set()
    auth = {"Authorization": token("MODRINTH_TOKEN")}
    for entry in entries:
        jar = checked_jar(entry)
        if key(entry) in existing:
            print(f"modrinth: {entry['name']}: already there, skipped")
            continue
        data = {
            "project_id": project_id,
            "name": entry["name"],
            "version_number": entry["version_number"],
            "changelog": entry["changelog"],
            "dependencies": entry["dependencies"],
            "game_versions": entry["game_versions"],
            "version_type": entry["version_type"],
            "loaders": entry["loaders"],
            "featured": entry.get("featured", False),
            "file_parts": ["file"],
            "primary_file": "file",
        }
        if not publish:
            print(f"modrinth: would upload {jar.name} as {entry['version_number']} {entry['loaders']} {entry['game_versions']}")
            continue
        ctype, payload = multipart({"data": json.dumps(data)}, {"file": (jar.name, jar.read_bytes())})
        status, body = request("POST", f"{MODRINTH}/version", {**auth, "Content-Type": ctype}, payload)
        if status != 200:
            sys.exit(f"modrinth: {jar.name}: HTTP {status} {body[:500]!r}")
        print(f"modrinth: uploaded {jar.name} -> https://modrinth.com/mod/{slug}/version/{json.loads(body)['id']}")


# --- CurseForge -------------------------------------------------------------------------------

def curseforge_ids(auth):
    status, body = request("GET", f"{CURSEFORGE}/game/version-types", auth)
    if status != 200:
        sys.exit(f"curseforge: version types: HTTP {status} {body[:200]!r}")
    slugs = {t["id"]: t["slug"] for t in json.loads(body)}
    status, body = request("GET", f"{CURSEFORGE}/game/versions", auth)
    if status != 200:
        sys.exit(f"curseforge: versions: HTTP {status} {body[:200]!r}")
    ids = {}
    for v in json.loads(body):
        kind = slugs.get(v["gameVersionTypeID"], "")
        if kind.startswith("minecraft-"):
            ids[("minecraft", v["name"])] = v["id"]
        elif kind in ("modloader", "environment"):
            ids[(kind, v["name"])] = v["id"]
    return ids


def curseforge(entries, project, publish):
    auth = {"X-Api-Token": token("CURSEFORGE_TOKEN")}
    ids = curseforge_ids(auth)
    record_file = REPO / "dist" / "published.json"
    record = json.loads(record_file.read_text()) if record_file.is_file() else {}
    done = set(record.get("curseforge", []))
    for entry in entries:
        jar = checked_jar(entry)
        if jar.name in done:
            print(f"curseforge: {jar.name}: uploaded before (dist/published.json), skipped")
            continue
        wanted = [("minecraft", g) for g in entry["game_versions"]]
        wanted += [("modloader", CF_LOADERS[l]) for l in entry["loaders"]]
        if entry.get("environment") == "client_and_server":
            wanted += [("environment", "Client"), ("environment", "Server")]
        missing = [w for w in wanted if w not in ids]
        if missing:
            sys.exit(f"curseforge: {jar.name}: no CurseForge id for {missing}")
        metadata = {
            "changelog": entry["changelog"],
            "changelogType": "markdown",
            "displayName": entry["name"],
            "gameVersions": [ids[w] for w in wanted],
            "releaseType": entry["version_type"],
        }
        relations = [{"slug": CF_RELATIONS[d["project_id"]], "type": "requiredDependency"}
                     for d in entry["dependencies"] if d.get("dependency_type") == "required" and d.get("project_id") in CF_RELATIONS]
        if relations:
            metadata["relations"] = {"projects": relations}
        if not publish:
            print(f"curseforge: would upload {jar.name} as \"{entry['name']}\" "
                  f"{[w[1] for w in wanted]}{' requires ' + ', '.join(r['slug'] for r in relations) if relations else ''}")
            continue
        ctype, payload = multipart({"metadata": json.dumps(metadata)}, {"file": (jar.name, jar.read_bytes())})
        status, body = request("POST", f"{CURSEFORGE}/projects/{project}/upload-file", {**auth, "Content-Type": ctype}, payload)
        if status != 200:
            sys.exit(f"curseforge: {jar.name}: HTTP {status} {body[:500]!r}")
        print(f"curseforge: uploaded {jar.name} (file id {json.loads(body)['id']})")
        done.add(jar.name)
        record["curseforge"] = sorted(done)
        record_file.write_text(json.dumps(record, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--version", default=prop(REPO / "gradle.properties", "mod_version"))
    parser.add_argument("--only", choices=["modrinth", "curseforge"])
    parser.add_argument("--include-paper", action="store_true")
    parser.add_argument("--curseforge-project")
    parser.add_argument("--publish", action="store_true", help="really upload (default: dry run)")
    args = parser.parse_args()

    project = json.loads((REPO / "modrinth" / "project.json").read_text(encoding="utf-8"))
    entries = [e for e in json.loads((REPO / "modrinth" / "versions.json").read_text(encoding="utf-8"))
               if e["version_number"].startswith(args.version) and (args.include_paper or "paper" not in e["loaders"])]
    if not entries:
        sys.exit(f"publish.py: no entry in modrinth/versions.json for version {args.version}")
    print(f"{'Publishing' if args.publish else 'Dry run:'} {len(entries)} file(s) of {args.version}")

    if args.only in (None, "modrinth"):
        modrinth(entries, project["slug"], args.publish)
    if args.only in (None, "curseforge"):
        cf_project = args.curseforge_project or project.get("curseforge_project_id")
        if not cf_project:
            sys.exit("publish.py: set curseforge_project_id in modrinth/project.json or pass --curseforge-project")
        curseforge([e for e in entries if "paper" not in e["loaders"]], cf_project, args.publish)


if __name__ == "__main__":
    main()
